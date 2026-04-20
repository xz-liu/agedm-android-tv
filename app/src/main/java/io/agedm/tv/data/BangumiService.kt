package io.agedm.tv.data

import java.io.IOException
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
            } ?: resolveSubject(animeId, title)
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
                cacheResolvedMatch(animeId, resolved)
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
            } ?: resolveSubject(animeId, title)
            if (resolved == null) {
                cached
            } else {
                val fresh = loadMetadata(
                    animeId = animeId,
                    subjectId = resolved.subjectId,
                    matchedTitle = resolved.matchedTitle.ifBlank { title },
                )
                cacheResolvedMatch(animeId, resolved)
                store.write(cacheKey(animeId), json.encodeToString(fresh))
                fresh
            }
        }.getOrElse { cached }
    }

    suspend fun listSuspiciousMatches(limit: Int = DEFAULT_REVIEW_LIMIT): List<BangumiMatchIssue> {
        return store.listKeys(matchCacheKeyPrefix())
            .mapNotNull { key ->
                val animeId = key.removePrefix(matchCacheKeyPrefix()).toLongOrNull() ?: return@mapNotNull null
                val match = readResolvedMatchCache(animeId)?.toResolvedMatch() ?: return@mapNotNull null
                val ageMetadata = readAgeLookupMetadata(animeId) ?: return@mapNotNull null
                val diagnosis = diagnoseStoredMatch(animeId, ageMetadata, match) ?: return@mapNotNull null
                if (!diagnosis.isSuspicious) return@mapNotNull null
                BangumiMatchIssue(
                    animeId = animeId,
                    ageTitle = ageMetadata.bestDisplayTitle(),
                    bangumiTitle = diagnosis.displayTitle,
                    subjectId = match.subjectId,
                    reason = diagnosis.reason,
                )
            }
            .sortedBy { it.ageTitle }
            .take(limit)
    }

    suspend fun refreshSuspiciousMatches(limit: Int = DEFAULT_REVIEW_LIMIT): List<BangumiMatchIssue> {
        val targets = listSuspiciousMatches(limit)
        targets.forEach { issue ->
            clearMatch(issue.animeId)
            fetch(issue.animeId, issue.ageTitle, forceRefresh = true)
        }
        return listSuspiciousMatches(limit)
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

    private fun resolveSubject(animeId: Long, title: String): ResolvedMatch? {
        val cached = readResolvedMatchCache(animeId)
        if (cached != null && !isResolvedMatchStale(cached)) {
            return cached.toResolvedMatch()
        }
        val resolved = searchSubject(animeId, title)
        cacheResolvedMatch(animeId, resolved)
        return resolved
    }

    private fun searchSubject(animeId: Long, title: String): ResolvedMatch? {
        val ageMetadata = readAgeLookupMetadata(animeId)?.mergeFallbackTitle(title)
            ?: AgeBangumiLookupMetadata(
                animeId = animeId,
                title = title,
                updatedAtMs = System.currentTimeMillis(),
            )
        val searchQueries = buildSearchQueries(ageMetadata)
        if (searchQueries.isEmpty()) return null

        val seenIds = mutableSetOf<Long>()
        var best: SubjectCandidateMatch? = null
        searchQueries.forEachIndexed queryLoop@{ queryIndex, query ->
            val payload = performSearch(query)
            payload.data.forEachIndexed candidateLoop@{ resultIndex, candidate ->
                if (!seenIds.add(candidate.id)) return@candidateLoop
                val evaluation = evaluateCandidate(
                    ageMetadata = ageMetadata,
                    candidate = candidate,
                    queryIndex = queryIndex,
                    resultIndex = resultIndex,
                )
                if (best == null || evaluation.totalScore > best!!.totalScore) {
                    best = evaluation
                }
            }
        }
        return best?.takeIf(::isAcceptableCandidate)?.let { candidate ->
            ResolvedMatch(
                subjectId = candidate.subjectId,
                matchedTitle = candidate.displayTitle,
            )
        }
    }

    private fun readResolvedMatchCache(animeId: Long): ResolvedMatchCacheEntry? {
        val body = store.read(matchCacheKey(animeId)) ?: return null
        return runCatching {
            json.decodeFromString<ResolvedMatchCacheEntry>(body)
        }.getOrNull()
    }

    private fun cacheResolvedMatch(animeId: Long, resolved: ResolvedMatch?) {
        val payload = if (resolved == null) {
            ResolvedMatchCacheEntry(
                updatedAtMs = System.currentTimeMillis(),
                miss = true,
            )
        } else {
            ResolvedMatchCacheEntry(
                subjectId = resolved.subjectId,
                matchedTitle = resolved.matchedTitle,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        store.write(matchCacheKey(animeId), json.encodeToString(payload))
    }

    private fun isRatingStale(metadata: BangumiMetadata): Boolean {
        val fetchedAt = metadata.fetchedAtMs
        if (fetchedAt <= 0L) return true
        return System.currentTimeMillis() - fetchedAt > RATING_TTL_MS
    }

    private fun isResolvedMatchStale(entry: ResolvedMatchCacheEntry): Boolean {
        return entry.updatedAtMs <= 0L
    }

    private fun readAgeLookupMetadata(animeId: Long): AgeBangumiLookupMetadata? {
        val body = store.read(ageLookupCacheKey(animeId)) ?: return null
        return runCatching { json.decodeFromString<AgeBangumiLookupMetadata>(body) }.getOrNull()
    }

    private fun clearMatch(animeId: Long) {
        store.remove(matchCacheKey(animeId))
        store.remove(cacheKey(animeId))
    }

    private fun diagnoseStoredMatch(
        animeId: Long,
        ageMetadata: AgeBangumiLookupMetadata,
        resolved: ResolvedMatch,
    ): MatchDiagnosis? {
        val cachedMetadata = peek(animeId)
        val candidateTitles = listOf(
            cachedMetadata?.titleCn.orEmpty(),
            cachedMetadata?.title.orEmpty(),
            resolved.matchedTitle,
        ).filter { it.isNotBlank() }
        if (candidateTitles.isEmpty()) return null
        val titleMatch = scoreTitleMatch(
            queryTitles = ageMetadata.allTitles(),
            candidateTitles = candidateTitles,
        )
        val displayTitle = candidateTitles.firstOrNull().orEmpty()
        if (titleMatch.strong || titleMatch.score >= REVIEW_TITLE_SCORE_THRESHOLD) {
            return MatchDiagnosis(
                isSuspicious = false,
                displayTitle = displayTitle,
                reason = "",
            )
        }
        return MatchDiagnosis(
            isSuspicious = true,
            displayTitle = displayTitle,
            reason = "AGE 标题与 Bangumi 标题差异过大，当前缓存可能误配",
        )
    }

    private fun buildSearchQueries(metadata: AgeBangumiLookupMetadata): List<String> {
        val candidates = linkedSetOf<String>()
        val preferredTitles = metadata.allTitles()
        val chineseLike = preferredTitles.filter(::containsHanCharacters)
        val nonJapanese = preferredTitles.filterNot(::containsJapaneseKana)
        val ordered = when {
            chineseLike.isNotEmpty() -> chineseLike + nonJapanese + preferredTitles
            nonJapanese.isNotEmpty() -> nonJapanese + preferredTitles
            else -> preferredTitles
        }
        ordered.forEach { value ->
            val query = value.trim()
            if (query.length >= 2) {
                candidates += query
            }
        }
        return candidates.take(MAX_MATCH_SEARCH_QUERIES)
    }

    private fun performSearch(query: String): BangumiSearchResponse {
        val requestBody = json.encodeToString(
            BangumiSearchRequest(
                keyword = query,
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
            return json.decodeFromString(response.body?.string().orEmpty())
        }
    }

    private fun evaluateCandidate(
        ageMetadata: AgeBangumiLookupMetadata,
        candidate: BangumiSearchSubject,
        queryIndex: Int,
        resultIndex: Int,
    ): SubjectCandidateMatch {
        val titleMatch = scoreTitleMatch(
            queryTitles = ageMetadata.allTitles(),
            candidateTitles = candidate.allTitles(),
        )
        var totalScore = titleMatch.score
        var metadataHits = 0

        if (ageMetadata.website.matchesCandidateWebsite(candidate)) {
            totalScore += WEBSITE_MATCH_SCORE
            metadataHits += 1
        }
        when (ageMetadata.premiere.dateMatchLevel(candidate)) {
            DateMatchLevel.DAY -> {
                totalScore += DATE_DAY_MATCH_SCORE
                metadataHits += 1
            }

            DateMatchLevel.MONTH -> {
                totalScore += DATE_MONTH_MATCH_SCORE
                metadataHits += 1
            }

            DateMatchLevel.YEAR -> {
                totalScore += DATE_YEAR_MATCH_SCORE
                metadataHits += 1
            }

            DateMatchLevel.NONE -> Unit
        }
        if (ageMetadata.company.matchesCandidateCompany(candidate)) {
            totalScore += COMPANY_MATCH_SCORE
            metadataHits += 1
        }
        if (ageMetadata.writer.matchesCandidateOriginalWork(candidate)) {
            totalScore += WRITER_MATCH_SCORE
            metadataHits += 1
        }
        totalScore += (MAX_MATCH_SEARCH_RESULTS - resultIndex).coerceAtLeast(0) * RESULT_RANK_SCORE
        totalScore += (MAX_MATCH_SEARCH_QUERIES - queryIndex).coerceAtLeast(0) * QUERY_RANK_SCORE

        return SubjectCandidateMatch(
            subjectId = candidate.id,
            displayTitle = candidate.displayTitle(),
            totalScore = totalScore,
            titleMatch = titleMatch,
            metadataHits = metadataHits,
        )
    }

    private fun isAcceptableCandidate(candidate: SubjectCandidateMatch): Boolean {
        return candidate.titleMatch.strong ||
            (candidate.metadataHits >= 2 && candidate.totalScore >= REVIEW_ACCEPT_SCORE_THRESHOLD)
    }

    private fun scoreTitleMatch(
        queryTitles: List<String>,
        candidateTitles: List<String>,
    ): TitleMatchScore {
        val normalizedQueries = queryTitles.map(::normalizeTitle).filter { it.isNotBlank() }.distinct()
        val normalizedCandidates = candidateTitles.map(::normalizeTitle).filter { it.isNotBlank() }.distinct()
        if (normalizedQueries.isEmpty() || normalizedCandidates.isEmpty()) {
            return TitleMatchScore()
        }

        var best = TitleMatchScore()
        for (query in normalizedQueries) {
            for (candidate in normalizedCandidates) {
                val score = scoreSingleTitlePair(query, candidate)
                if (score.score > best.score) {
                    best = score
                }
            }
        }
        return best
    }

    private fun scoreSingleTitlePair(query: String, candidate: String): TitleMatchScore {
        var score = 0
        var strong = false
        if (query == candidate) {
            score += 1000
            strong = true
        }
        val editDistance = levenshteinDistance(query, candidate)
        if (editDistance == 1 && maxOf(query.length, candidate.length) >= 3) {
            score += 520
            strong = true
        } else if (editDistance == 2 && maxOf(query.length, candidate.length) >= 6) {
            score += 180
        }
        if (candidate.contains(query) || query.contains(candidate)) {
            score += 360
            strong = true
        }
        val commonPrefix = query.zip(candidate).takeWhile { it.first == it.second }.count()
        score += commonPrefix * 8
        val querySeason = extractSeasonHint(query)
        val candidateSeason = extractSeasonHint(candidate)
        if (querySeason != null && querySeason == candidateSeason) {
            score += 140
        }
        return TitleMatchScore(score = score, strong = strong)
    }

    private fun normalizeTitle(value: String): String {
        return value.lowercase(Locale.US)
            .replace(Regex("[\\s\\u3000·・:：\\-_/\\\\|()（）\\[\\]【】《》「」『』\"'`!！?？,.，。~～〜]+"), "")
    }

    private fun extractSeasonHint(value: String): Int? {
        val numberMatch = Regex("第\\s*([0-9]+)\\s*[季期部篇]").find(value)
            ?: Regex("([0-9]+)(?:st|nd|rd|th)?\\s*(?:season|季|期|部|篇)", RegexOption.IGNORE_CASE).find(value)
        if (numberMatch != null) {
            return numberMatch.groupValues.getOrNull(1)?.toIntOrNull()
        }
        return null
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)
        for (i in left.indices) {
            current[0] = i + 1
            for (j in right.indices) {
                val substitutionCost = if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + substitutionCost,
                )
            }
            current.copyInto(previous)
        }
        return previous[right.length]
    }

    private fun normalizeHost(value: String): String {
        return value.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .removePrefix("www.")
            .lowercase(Locale.US)
    }

    private fun normalizeLooseToken(value: String): String {
        return normalizeTitle(value)
    }

    private fun normalizeDateToken(value: String): String {
        return value.trim()
            .replace("年", "-")
            .replace("月", "-")
            .replace("日", "")
            .replace('/', '-')
            .replace('.', '-')
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    private fun parseDateParts(value: String): Triple<Int?, Int?, Int?> {
        val normalized = normalizeDateToken(value)
        val parts = normalized.split('-')
        val year = parts.getOrNull(0)?.toIntOrNull()
        val month = parts.getOrNull(1)?.toIntOrNull()
        val day = parts.getOrNull(2)?.toIntOrNull()
        return Triple(year, month, day)
    }

    private fun containsHanCharacters(value: String): Boolean {
        return value.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
    }

    private fun containsJapaneseKana(value: String): Boolean {
        return value.any { ch ->
            val block = Character.UnicodeBlock.of(ch)
            block == Character.UnicodeBlock.HIRAGANA || block == Character.UnicodeBlock.KATAKANA
        }
    }

    private fun cacheKey(animeId: Long): String = "bgm_$animeId"

    private fun matchCacheKey(animeId: Long): String = "bgm_match_$animeId"

    private fun matchCacheKeyPrefix(): String = "bgm_match_"

    private fun ageLookupCacheKey(animeId: Long): String = "age_lookup_$animeId"

    private fun AgeBangumiLookupMetadata.mergeFallbackTitle(fallbackTitle: String): AgeBangumiLookupMetadata {
        return if (title.isNotBlank()) this else copy(title = fallbackTitle)
    }

    private fun AgeBangumiLookupMetadata.allTitles(): List<String> {
        return listOf(title, originalTitle) + otherTitles
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun AgeBangumiLookupMetadata.bestDisplayTitle(): String {
        return title.ifBlank { originalTitle }.ifBlank { otherTitles.firstOrNull().orEmpty() }
    }

    private fun String.matchesCandidateWebsite(candidate: BangumiSearchSubject): Boolean {
        val expectedHost = normalizeHost(this)
        if (expectedHost.isBlank()) return false
        return candidate.infoboxValuesMatching("官方网站")
            .map(::normalizeHost)
            .any { it == expectedHost }
    }

    private fun String.dateMatchLevel(candidate: BangumiSearchSubject): DateMatchLevel {
        if (this.isBlank()) return DateMatchLevel.NONE
        val expected = parseDateParts(this)
        val actual = parseDateParts(candidate.date.orEmpty())
        val expectedYear = expected.first ?: return DateMatchLevel.NONE
        val actualYear = actual.first ?: return DateMatchLevel.NONE
        if (expectedYear != actualYear) return DateMatchLevel.NONE
        val expectedMonth = expected.second
        val actualMonth = actual.second
        if (expectedMonth == null || actualMonth == null) return DateMatchLevel.YEAR
        if (expectedMonth != actualMonth) return DateMatchLevel.NONE
        val expectedDay = expected.third
        val actualDay = actual.third
        return if (expectedDay != null && actualDay != null && expectedDay == actualDay) {
            DateMatchLevel.DAY
        } else {
            DateMatchLevel.MONTH
        }
    }

    private fun String.matchesCandidateCompany(candidate: BangumiSearchSubject): Boolean {
        val expected = normalizeLooseToken(this)
        if (expected.isBlank()) return false
        val tagMatch = candidate.tags.any { normalizeLooseToken(it.name) == expected }
        if (tagMatch) return true
        return candidate.infoboxValuesMatching("制作")
            .map(::normalizeLooseToken)
            .any { it.contains(expected) || expected.contains(it) }
    }

    private fun String.matchesCandidateOriginalWork(candidate: BangumiSearchSubject): Boolean {
        val expected = normalizeLooseToken(this)
        if (expected.isBlank()) return false
        return candidate.infoboxValuesMatching("原作")
            .map(::normalizeLooseToken)
            .any { it.contains(expected) || expected.contains(it) }
    }

    private fun BangumiSearchSubject.displayTitle(): String {
        return nameCn.ifBlank { name }
    }

    private fun BangumiSearchSubject.allTitles(): List<String> {
        return listOf(nameCn, name) + infoboxValuesMatching("别名")
    }

    private fun BangumiSearchSubject.infoboxValuesMatching(keyword: String): List<String> {
        return infobox.asSequence()
            .filter { it.key.contains(keyword, ignoreCase = true) }
            .flatMap { it.values().asSequence() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun BangumiInfoboxEntry.values(): List<String> {
        return flattenJsonStrings(value)
    }

    private fun flattenJsonStrings(element: JsonElement?): List<String> {
        return when (element) {
            null -> emptyList()
            is JsonPrimitive -> listOfNotNull(
                element.content
                    .takeUnless { it == "null" }
                    ?.trim()
                    ?.takeIf { it.isNotBlank() },
            )
            is JsonArray -> element.flatMap(::flattenJsonStrings)
            is JsonObject -> {
                val direct = element["v"] ?: element["value"] ?: element["text"]
                if (direct != null) {
                    flattenJsonStrings(direct)
                } else {
                    element.values.flatMap(::flattenJsonStrings)
                }
            }
            else -> emptyList()
        }
    }

    private data class ResolvedMatch(
        val subjectId: Long,
        val matchedTitle: String,
    )

    private data class TitleMatchScore(
        val score: Int = 0,
        val strong: Boolean = false,
    )

    private data class SubjectCandidateMatch(
        val subjectId: Long,
        val displayTitle: String,
        val totalScore: Int,
        val titleMatch: TitleMatchScore,
        val metadataHits: Int,
    )

    private data class MatchDiagnosis(
        val isSuspicious: Boolean,
        val displayTitle: String,
        val reason: String,
    )

    private enum class DateMatchLevel {
        NONE,
        YEAR,
        MONTH,
        DAY,
    }

    @Serializable
    private data class ResolvedMatchCacheEntry(
        val subjectId: Long = 0L,
        val matchedTitle: String = "",
        val updatedAtMs: Long = 0L,
        val miss: Boolean = false,
    ) {
        fun toResolvedMatch(): ResolvedMatch? {
            if (miss || subjectId <= 0L) return null
            return ResolvedMatch(
                subjectId = subjectId,
                matchedTitle = matchedTitle,
            )
        }
    }

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
        val date: String? = null,
        val tags: List<BangumiTagResponse> = emptyList(),
        val infobox: List<BangumiInfoboxEntry> = emptyList(),
    )

    @Serializable
    private data class BangumiInfoboxEntry(
        val key: String = "",
        val value: JsonElement? = null,
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
        private const val MAX_MATCH_SEARCH_QUERIES = 4
        private const val MAX_MATCH_SEARCH_RESULTS = 10
        private const val DEFAULT_REVIEW_LIMIT = 60
        private const val RATING_TTL_MS = 30L * 24 * 60 * 60 * 1000L
        private const val WEBSITE_MATCH_SCORE = 320
        private const val DATE_DAY_MATCH_SCORE = 260
        private const val DATE_MONTH_MATCH_SCORE = 180
        private const val DATE_YEAR_MATCH_SCORE = 90
        private const val COMPANY_MATCH_SCORE = 220
        private const val WRITER_MATCH_SCORE = 220
        private const val RESULT_RANK_SCORE = 6
        private const val QUERY_RANK_SCORE = 12
        private const val REVIEW_TITLE_SCORE_THRESHOLD = 320
        private const val REVIEW_ACCEPT_SCORE_THRESHOLD = 420

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
