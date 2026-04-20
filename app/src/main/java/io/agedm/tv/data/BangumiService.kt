package io.agedm.tv.data

import java.io.IOException
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

internal class BangumiService(
    private val store: PersistentJsonStore,
    private val client: OkHttpClient,
    private val json: Json,
    private val alignToAge: suspend (titles: List<String>, excludeAnimeId: Long) -> AgeRelatedItem?,
) {

    fun peek(animeId: Long): BangumiMetadata? {
        val body = store.read(cacheKey(animeId)) ?: return null
        return runCatching { json.decodeFromString<BangumiMetadata>(body) }.getOrNull()
    }

    suspend fun fetchScore(animeId: Long, title: String): String? {
        val cached = peek(animeId)
        if (cached != null && !isRatingStale(cached) && cached.scoreLabel != null) {
            return cached.scoreLabel
        }
        if (title.isBlank() && cached != null) return cached.scoreLabel

        return runCatching {
            val resolved = cached?.subjectId?.takeIf { it > 0 }?.let { subjectId ->
                ResolvedMatch(subjectId = subjectId, matchedTitle = cached.matchedTitle.ifBlank { title })
            } ?: resolveSubject(title)
            if (resolved == null) {
                cached?.scoreLabel
            } else {
                val detail = fetchSubjectDetail(resolved.subjectId)
                val fresh = cached?.copy(
                    subjectId = resolved.subjectId,
                    matchedTitle = resolved.matchedTitle.ifBlank { title },
                    title = detail.name,
                    titleCn = detail.nameCn,
                    score = detail.rating?.score?.takeIf { it > 0.0 },
                    voteCount = detail.rating?.total ?: 0,
                    rank = detail.rank?.takeIf { it > 0 },
                    ratingCounts = detail.rating?.count
                        .orEmpty()
                        .mapNotNull { (key, value) -> key.toIntOrNull()?.let { it to value } }
                        .sortedByDescending { it.first }
                        .toMap(),
                    tags = detail.tags
                        .sortedByDescending { it.count }
                        .take(MAX_TAGS)
                        .map { BangumiTag(name = it.name, count = it.count) },
                    fetchedAtMs = System.currentTimeMillis(),
                ) ?: BangumiMetadata(
                    subjectId = resolved.subjectId,
                    matchedTitle = resolved.matchedTitle.ifBlank { title },
                    title = detail.name,
                    titleCn = detail.nameCn,
                    score = detail.rating?.score?.takeIf { it > 0.0 },
                    voteCount = detail.rating?.total ?: 0,
                    rank = detail.rank?.takeIf { it > 0 },
                    ratingCounts = detail.rating?.count
                        .orEmpty()
                        .mapNotNull { (key, value) -> key.toIntOrNull()?.let { it to value } }
                        .sortedByDescending { it.first }
                        .toMap(),
                    tags = detail.tags
                        .sortedByDescending { it.count }
                        .take(MAX_TAGS)
                        .map { BangumiTag(name = it.name, count = it.count) },
                    fetchedAtMs = System.currentTimeMillis(),
                    isComplete = false,
                )
                store.write(cacheKey(animeId), json.encodeToString(fresh))
                fresh.scoreLabel
            }
        }.getOrElse { cached?.scoreLabel }
    }

    suspend fun fetch(animeId: Long, title: String, forceRefresh: Boolean = false): BangumiMetadata? {
        val cached = peek(animeId)
        if (!forceRefresh && cached != null && !isRatingStale(cached) && cached.isComplete) {
            return cached
        }

        if (title.isBlank() && cached != null) return cached

        return runCatching {
            val resolved = cached?.subjectId?.takeIf { it > 0 }?.let { subjectId ->
                ResolvedMatch(subjectId = subjectId, matchedTitle = cached.matchedTitle.ifBlank { title })
            } ?: resolveSubject(title)
            if (resolved == null) {
                cached
            } else {
                val fresh = loadMetadata(
                    animeId = animeId,
                    subjectId = resolved.subjectId,
                    matchedTitle = resolved.matchedTitle.ifBlank { title },
                )
                store.write(cacheKey(animeId), json.encodeToString(fresh))
                fresh
            }
        }.getOrElse { cached }
    }

    private suspend fun loadMetadata(
        animeId: Long,
        subjectId: Long,
        matchedTitle: String,
    ): BangumiMetadata {
        val detail = fetchSubjectDetail(subjectId)
        val document = fetchSubjectDocument(subjectId)
        return BangumiMetadata(
            subjectId = subjectId,
            matchedTitle = matchedTitle,
            title = detail.name,
            titleCn = detail.nameCn,
            score = detail.rating?.score?.takeIf { it > 0.0 },
            voteCount = detail.rating?.total ?: 0,
            rank = detail.rank?.takeIf { it > 0 },
            ratingCounts = detail.rating?.count
                .orEmpty()
                .mapNotNull { (key, value) -> key.toIntOrNull()?.let { it to value } }
                .sortedByDescending { it.first }
                .toMap(),
            tags = detail.tags
                .sortedByDescending { it.count }
                .take(MAX_TAGS)
                .map { BangumiTag(name = it.name, count = it.count) },
            staff = parseStaff(document),
            comments = parseComments(document),
            similar = parseSimilar(document, animeId),
            fetchedAtMs = System.currentTimeMillis(),
            isComplete = true,
        )
    }

    private fun fetchSubjectDetail(subjectId: Long): BangumiSubjectResponse {
        val request = Request.Builder()
            .url("$API_BASE_URL/subjects/$subjectId")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Bangumi 详情请求失败 ${response.code}")
            }
            return json.decodeFromString(response.body?.string().orEmpty())
        }
    }

    private fun fetchSubjectDocument(subjectId: Long) = Jsoup.parse(fetchSubjectHtml(subjectId))

    private fun fetchSubjectHtml(subjectId: Long): String {
        val request = Request.Builder()
            .url("$WEB_BASE_URL/subject/$subjectId")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Bangumi 页面请求失败 ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun parseStaff(document: org.jsoup.nodes.Document): List<BangumiStaffRole> {
        return document.select("#infobox li")
            .mapNotNull { item ->
                val role = item.selectFirst("span.tip")
                    ?.text()
                    ?.removeSuffix(":")
                    ?.trim()
                    .orEmpty()
                if (role.isBlank() || role in NON_STAFF_INFOBOX_KEYS) {
                    return@mapNotNull null
                }
                val names = item.select("a.l")
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() }
                    .ifEmpty {
                        item.text()
                            .substringAfter(':', "")
                            .split('、', '/', '／')
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                    }
                    .distinct()
                if (names.isEmpty()) null else BangumiStaffRole(role = role, names = names.take(MAX_STAFF_NAMES))
            }
            .take(MAX_STAFF_ROLES)
    }

    private fun parseComments(document: org.jsoup.nodes.Document): List<BangumiComment> {
        return document.select("#comment_box .item")
            .mapNotNull { item ->
                val user = item.selectFirst("a.l")?.text()?.trim().orEmpty()
                val content = item.selectFirst("p.comment")?.text()?.trim().orEmpty()
                if (user.isBlank() || content.isBlank()) return@mapNotNull null
                val meta = item.select("small.grey")
                val starsClass = item.selectFirst(".starlight")
                    ?.classNames()
                    ?.firstOrNull { it.startsWith("stars") }
                    ?.substringAfter("stars")
                    ?.toIntOrNull()
                BangumiComment(
                    user = user,
                    state = meta.getOrNull(0)?.text()?.trim().orEmpty(),
                    time = meta.getOrNull(1)?.text()?.trim().orEmpty(),
                    content = content,
                    score = starsClass,
                )
            }
            .take(MAX_COMMENTS)
    }

    private suspend fun parseSimilar(
        document: org.jsoup.nodes.Document,
        animeId: Long,
    ): List<AgeRelatedItem> {
        val section = document.select("div.subject_section")
            .firstOrNull { node ->
                node.selectFirst("h2.subtitle")
                    ?.text()
                    ?.contains("会员大概会喜欢") == true
            } ?: return emptyList()

        val items = linkedMapOf<Long, AgeRelatedItem>()
        for (entry in section.select("ul.coversSmall li.clearit").take(MAX_SIMILAR)) {
            val avatarLink = entry.selectFirst("a.avatar[href*=/subject/]")
            val textLink = entry.selectFirst("p.info a[href*=/subject/]")
            val titles = listOfNotNull(
                avatarLink?.attr("title")?.trim()?.takeIf { it.isNotBlank() },
                textLink?.text()?.trim()?.takeIf { it.isNotBlank() },
            ).distinct()
            if (titles.isEmpty()) continue
            val aligned = alignToAge(titles, animeId) ?: continue
            if (aligned.animeId == animeId || items.containsKey(aligned.animeId)) continue
            items[aligned.animeId] = aligned
        }
        return items.values.toList()
    }

    private fun resolveSubject(title: String): ResolvedMatch? {
        val requestBody = json.encodeToString(
            BangumiSearchRequest(
                keyword = title,
                filter = BangumiSearchFilter(type = listOf(2)),
            ),
        )
        val request = Request.Builder()
            .url("$API_BASE_URL/search/subjects")
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Bangumi 搜索失败 ${response.code}")
            }
            val payload = json.decodeFromString<BangumiSearchResponse>(response.body?.string().orEmpty())
            return payload.data
                .maxByOrNull { candidate ->
                    scoreTitleMatch(
                        queryTitles = listOf(title),
                        candidateTitles = listOf(candidate.nameCn, candidate.name),
                    )
                }
                ?.takeIf { candidate ->
                    scoreTitleMatch(
                        queryTitles = listOf(title),
                        candidateTitles = listOf(candidate.nameCn, candidate.name),
                    ) > 0
                }
                ?.let { ResolvedMatch(it.id, it.nameCn.ifBlank { it.name }) }
        }
    }

    private fun isRatingStale(metadata: BangumiMetadata): Boolean {
        val fetchedAt = metadata.fetchedAtMs
        if (fetchedAt <= 0L) return true
        return System.currentTimeMillis() - fetchedAt > RATING_TTL_MS
    }

    private fun scoreTitleMatch(
        queryTitles: List<String>,
        candidateTitles: List<String>,
    ): Int {
        val normalizedQueries = queryTitles.map(::normalizeTitle).filter { it.isNotBlank() }
        val normalizedCandidates = candidateTitles.map(::normalizeTitle).filter { it.isNotBlank() }
        if (normalizedQueries.isEmpty() || normalizedCandidates.isEmpty()) return 0

        var best = 0
        for (query in normalizedQueries) {
            for (candidate in normalizedCandidates) {
                var score = 0
                if (query == candidate) score += 1000
                if (candidate.contains(query) || query.contains(candidate)) score += 360
                val commonPrefix = query.zip(candidate).takeWhile { it.first == it.second }.count()
                score += commonPrefix * 8
                val querySeason = extractSeasonHint(query)
                val candidateSeason = extractSeasonHint(candidate)
                if (querySeason != null && querySeason == candidateSeason) score += 140
                if (score > best) best = score
            }
        }
        return best
    }

    private fun normalizeTitle(value: String): String {
        return value.lowercase(Locale.US)
            .replace(Regex("[\\s·:：\\-_/\\\\|()（）\\[\\]【】《》\"'`!！?？,.，。]+"), "")
    }

    private fun extractSeasonHint(value: String): Int? {
        val numberMatch = Regex("第\\s*([0-9]+)\\s*[季期部篇]").find(value)
            ?: Regex("([0-9]+)(?:st|nd|rd|th)?\\s*(?:season|季|期|部|篇)", RegexOption.IGNORE_CASE).find(value)
        if (numberMatch != null) {
            return numberMatch.groupValues.getOrNull(1)?.toIntOrNull()
        }
        return null
    }

    private fun cacheKey(animeId: Long): String = "bgm_$animeId"

    private data class ResolvedMatch(
        val subjectId: Long,
        val matchedTitle: String,
    )

    @Serializable
    private data class BangumiSearchRequest(
        val keyword: String,
        val filter: BangumiSearchFilter,
    )

    @Serializable
    private data class BangumiSearchFilter(
        val type: List<Int> = emptyList(),
    )

    @Serializable
    private data class BangumiSearchResponse(
        val data: List<BangumiSearchSubject> = emptyList(),
    )

    @Serializable
    private data class BangumiSearchSubject(
        val id: Long = 0,
        val name: String = "",
        @SerialName("name_cn")
        val nameCn: String = "",
    )

    @Serializable
    private data class BangumiSubjectResponse(
        val id: Long = 0,
        val name: String = "",
        @SerialName("name_cn")
        val nameCn: String = "",
        val rank: Int? = null,
        val rating: BangumiRatingResponse? = null,
        val tags: List<BangumiTagResponse> = emptyList(),
    )

    @Serializable
    private data class BangumiRatingResponse(
        val total: Int = 0,
        val score: Double = 0.0,
        val count: Map<String, Int> = emptyMap(),
    )

    @Serializable
    private data class BangumiTagResponse(
        val name: String = "",
        val count: Int = 0,
    )

    companion object {
        private const val API_BASE_URL = "https://api.bgm.tv/v0"
        private const val WEB_BASE_URL = "https://bgm.tv"
        private const val MAX_TAGS = 18
        private const val MAX_STAFF_ROLES = 12
        private const val MAX_STAFF_NAMES = 8
        private const val MAX_COMMENTS = 8
        private const val MAX_SIMILAR = 10
        private const val RATING_TTL_MS = 30L * 24 * 60 * 60 * 1000L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val NON_STAFF_INFOBOX_KEYS = setOf(
            "中文名",
            "别名",
            "话数",
            "放送开始",
            "放送结束",
            "放送星期",
            "播放开始",
            "播放结束",
            "播放星期",
            "官方网站",
            "播放电视台",
            "其他电视台",
            "Copyright",
            "发售日",
            "开始",
            "结束",
            "集数",
        )

        private const val USER_AGENT =
            "agedm-android-tv/1.0 (+https://github.com/xz-liu/agedm-android-tv)"
    }
}
