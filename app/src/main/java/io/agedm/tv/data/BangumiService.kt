package io.agedm.tv.data

import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
    private val ensureAgeMetadata: suspend (animeId: Long) -> AgeBangumiLookupMetadata?,
) {

    private val subjectSnapshotCache = ConcurrentHashMap<Long, BangumiSubjectSnapshot>()

    @Volatile
    private var subjectSnapshotCacheLoaded = false

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
                val ageMetadata = ensureAgeMetadata(animeId) ?: return@mapNotNull null
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

    suspend fun searchManualMatchCandidates(
        animeId: Long,
        title: String,
        limit: Int = MAX_MANUAL_CANDIDATES,
    ): List<BangumiMatchCandidate> {
        val ageMetadata = ensureAgeMetadata(animeId)?.mergeFallbackTitle(title)
            ?: AgeBangumiLookupMetadata(
                animeId = animeId,
                title = title,
                updatedAtMs = System.currentTimeMillis(),
            )
        return rankedSubjectCandidates(ageMetadata)
            .take(limit)
            .map { candidate ->
                BangumiMatchCandidate(
                    subjectId = candidate.subjectId,
                    title = candidate.displayTitle,
                    subtitle = buildCandidateSubtitle(candidate),
                )
            }
    }

    suspend fun assignManualMatch(
        animeId: Long,
        title: String,
        subjectId: Long,
    ): BangumiMetadata? {
        if (animeId <= 0L || subjectId <= 0L) return null
        val detail = fetchSubjectDetail(subjectId)
        val resolved = ResolvedMatch(
            subjectId = subjectId,
            matchedTitle = detail.nameCn.ifBlank { detail.name }.ifBlank { title },
        )
        val fresh = loadMetadata(
            animeId = animeId,
            subjectId = subjectId,
            matchedTitle = resolved.matchedTitle,
        )
        cacheResolvedMatch(animeId, resolved)
        store.write(cacheKey(animeId), json.encodeToString(fresh))
        return fresh
    }

    suspend fun rebuildSubjectIndexAndRematchAll(): BangumiRematchSummary {
        val ageEntries = loadAllAgeLookupMetadata()
        if (ageEntries.isEmpty()) {
            clearSubjectSnapshots()
            return BangumiRematchSummary(
                ageEntries = 0,
                indexedSubjects = 0,
                matchedEntries = 0,
                missingEntries = 0,
            )
        }

        clearSubjectSnapshots()
        ageEntries.forEach { metadata ->
            buildSearchQueries(metadata).forEach { query ->
                runCatching { performSearch(query) }
            }
        }

        var matchedEntries = 0
        var missingEntries = 0
        ageEntries.forEach { metadata ->
            clearMatch(metadata.animeId)
            val resolved = searchSubject(ageMetadata = metadata, includeRemoteSearch = false)
            cacheResolvedMatch(metadata.animeId, resolved)
            if (resolved == null) {
                missingEntries += 1
            } else {
                matchedEntries += 1
            }
        }

        return BangumiRematchSummary(
            ageEntries = ageEntries.size,
            indexedSubjects = loadSubjectSnapshots().size,
            matchedEntries = matchedEntries,
            missingEntries = missingEntries,
        )
    }

    private suspend fun loadMetadata(
        animeId: Long,
        subjectId: Long,
        matchedTitle: String,
    ): BangumiMetadata {
        val detail = fetchSubjectDetail(subjectId)
        val document = fetchSubjectDocument(subjectId)
        cacheSubjectSnapshot(detail, document)
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
        val resolved = searchSubject(animeId, title, includeRemoteSearch = true)
        cacheResolvedMatch(animeId, resolved)
        return resolved
    }

    private fun searchSubject(
        animeId: Long,
        title: String,
        includeRemoteSearch: Boolean,
    ): ResolvedMatch? {
        val ageMetadata = readAgeLookupMetadata(animeId)?.mergeFallbackTitle(title)
            ?: AgeBangumiLookupMetadata(
                animeId = animeId,
                title = title,
                updatedAtMs = System.currentTimeMillis(),
            )
        return searchSubject(ageMetadata, includeRemoteSearch)
    }

    private fun searchSubject(
        ageMetadata: AgeBangumiLookupMetadata,
        includeRemoteSearch: Boolean,
    ): ResolvedMatch? {
        val best = rankedSubjectCandidates(ageMetadata, includeRemoteSearch).firstOrNull()
        return best?.takeIf(::isAcceptableCandidate)?.let { candidate ->
            ResolvedMatch(
                subjectId = candidate.subjectId,
                matchedTitle = candidate.displayTitle,
            )
        }
    }

    private fun rankedSubjectCandidates(
        ageMetadata: AgeBangumiLookupMetadata,
        includeRemoteSearch: Boolean = true,
    ): List<SubjectCandidateMatch> {
        val searchQueries = buildSearchQueries(ageMetadata)
        val seeds = linkedMapOf<Long, SubjectCandidateSeed>()

        loadLocalCorpusCandidateSeeds(ageMetadata).forEach { seed ->
            seeds[seed.subjectId] = mergeSubjectCandidateSeed(seeds[seed.subjectId], seed)
        }

        if (includeRemoteSearch) {
            searchQueries.forEachIndexed { queryIndex, query ->
                val payload = performSearch(query)
                payload.data.forEachIndexed candidateLoop@{ resultIndex, candidate ->
                    if (candidate.id <= 0L) return@candidateLoop
                    val seed = SubjectCandidateSeed(
                        subjectId = candidate.id,
                        candidate = candidate.toCandidateData(),
                        queryIndex = queryIndex,
                        resultIndex = resultIndex,
                        fromSearch = true,
                    )
                    seeds[candidate.id] = mergeSubjectCandidateSeed(seeds[candidate.id], seed)
                }
            }
        }

        return seeds.values
            .map { seed -> evaluateCandidate(ageMetadata, seed) }
            .sortedByDescending { it.totalScore }
    }

    private fun loadLocalCorpusCandidateSeeds(
        ageMetadata: AgeBangumiLookupMetadata,
    ): List<SubjectCandidateSeed> {
        val normalizedQueries = ageMetadata.allTitles()
            .map(::normalizeTitle)
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedQueries.isEmpty()) return emptyList()

        return loadSubjectSnapshots()
            .asSequence()
            .map { snapshot ->
                val candidate = snapshot.toCandidateData()
                val bucketScore = scoreLocalCorpusBucket(ageMetadata, normalizedQueries, candidate)
                bucketScore to candidate
            }
            .filter { (bucketScore, candidate) -> bucketScore > 0 && candidate.subjectId > 0L }
            .sortedByDescending { (bucketScore, _) -> bucketScore }
            .take(MAX_LOCAL_CORPUS_CANDIDATES)
            .map { (bucketScore, candidate) ->
                SubjectCandidateSeed(
                    subjectId = candidate.subjectId,
                    candidate = candidate,
                    fromLocalCorpus = true,
                    localCorpusScore = bucketScore,
                )
            }
            .toList()
    }

    private fun scoreLocalCorpusBucket(
        ageMetadata: AgeBangumiLookupMetadata,
        normalizedQueries: List<String>,
        candidate: BangumiSubjectCandidateData,
    ): Int {
        val normalizedCandidateTitles = candidate.allTitles()
            .map(::normalizeTitle)
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedCandidateTitles.isEmpty()) return 0

        var score = 0
        if (normalizedQueries.any { query -> query in normalizedCandidateTitles }) {
            score += 180
        }
        if (normalizedQueries.any { query ->
                normalizedCandidateTitles.any { candidateTitle ->
                    candidateTitle.contains(query) || query.contains(candidateTitle)
                }
            }
        ) {
            score += 90
        }

        val queryPrefixes = normalizedQueries.map { it.take(LOCAL_BUCKET_PREFIX_LENGTH) }.filter { it.length >= 3 }.toSet()
        if (queryPrefixes.isNotEmpty() && normalizedCandidateTitles.any { title ->
                queryPrefixes.any { prefix -> title.startsWith(prefix) }
            }
        ) {
            score += 60
        }

        if (ageMetadata.website.matchesCandidateWebsite(candidate)) {
            score += 80
        }
        if (ageMetadata.company.matchesCandidateCompany(candidate)) {
            score += 70
        }
        if (ageMetadata.writer.matchesCandidateOriginalWork(candidate)) {
            score += 70
        }
        if (ageMetadata.premiere.dateMatchLevel(candidate) != DateMatchLevel.NONE) {
            score += 80
        }
        if (ageMetadata.premiere.hasConflictingPremiereYear(candidate)) {
            score -= 120
        }

        return score
    }

    private fun mergeSubjectCandidateSeed(
        current: SubjectCandidateSeed?,
        incoming: SubjectCandidateSeed,
    ): SubjectCandidateSeed {
        if (current == null) return incoming
        return SubjectCandidateSeed(
            subjectId = current.subjectId,
            candidate = current.candidate.mergeWith(incoming.candidate),
            queryIndex = minOf(current.queryIndex, incoming.queryIndex),
            resultIndex = minOf(current.resultIndex, incoming.resultIndex),
            fromSearch = current.fromSearch || incoming.fromSearch,
            fromLocalCorpus = current.fromLocalCorpus || incoming.fromLocalCorpus,
            localCorpusScore = maxOf(current.localCorpusScore, incoming.localCorpusScore),
        )
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
        val snapshot = readSubjectSnapshot(resolved.subjectId)
        val candidateTitles = buildList {
            add(cachedMetadata?.titleCn.orEmpty())
            add(cachedMetadata?.title.orEmpty())
            addAll(snapshot?.allTitles().orEmpty())
            add(resolved.matchedTitle)
        }.filter { it.isNotBlank() }.distinct()
        if (candidateTitles.isEmpty()) return null
        val titleMatch = scoreTitleMatch(
            queryTitles = ageMetadata.allTitles(),
            candidateTitles = candidateTitles,
        )
        val displayTitle = candidateTitles.firstOrNull().orEmpty()
        if (hasExactTitleMatch(ageMetadata.allTitles(), candidateTitles) || titleMatch.score >= REVIEW_TITLE_SCORE_THRESHOLD) {
            return MatchDiagnosis(
                isSuspicious = false,
                displayTitle = displayTitle,
                reason = "",
            )
        }
        return MatchDiagnosis(
            isSuspicious = true,
            displayTitle = displayTitle,
            reason = if (titleMatch.strong) {
                "标题只有部分相似，建议人工确认"
            } else {
                "AGE 标题与 Bangumi 标题差异过大，当前缓存可能误配"
            },
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
            return json.decodeFromString<BangumiSearchResponse>(response.body?.string().orEmpty()).also { payload ->
                payload.data.forEach(::cacheSubjectSnapshot)
            }
        }
    }

    private fun evaluateCandidate(
        ageMetadata: AgeBangumiLookupMetadata,
        seed: SubjectCandidateSeed,
    ): SubjectCandidateMatch {
        val candidate = seed.candidate
        val titleMatch = scoreTitleMatch(
            queryTitles = ageMetadata.allTitles(),
            candidateTitles = candidate.allTitles(),
        )
        var totalScore = titleMatch.score + seed.localCorpusScore
        var metadataHits = 0
        val dateMatchLevel = ageMetadata.premiere.dateMatchLevel(candidate)
        val conflictingPremiereYear = ageMetadata.premiere.hasConflictingPremiereYear(candidate)

        if (ageMetadata.website.matchesCandidateWebsite(candidate)) {
            totalScore += WEBSITE_MATCH_SCORE
            metadataHits += 1
        }
        when (dateMatchLevel) {
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
        if (conflictingPremiereYear) {
            totalScore -= DATE_YEAR_MISMATCH_SCORE
        }
        if (ageMetadata.company.matchesCandidateCompany(candidate)) {
            totalScore += COMPANY_MATCH_SCORE
            metadataHits += 1
        }
        if (ageMetadata.writer.matchesCandidateOriginalWork(candidate)) {
            totalScore += WRITER_MATCH_SCORE
            metadataHits += 1
        }
        if (titleMatch.exact && metadataHits > 0) {
            totalScore += EXACT_ALIAS_METADATA_SCORE
        }
        if (titleMatch.exact && dateMatchLevel != DateMatchLevel.NONE) {
            totalScore += EXACT_ALIAS_DATE_SCORE
        }
        if (seed.fromSearch) {
            totalScore += (MAX_MATCH_SEARCH_RESULTS - seed.resultIndex).coerceAtLeast(0) * RESULT_RANK_SCORE
            totalScore += (MAX_MATCH_SEARCH_QUERIES - seed.queryIndex).coerceAtLeast(0) * QUERY_RANK_SCORE
        }
        if (seed.fromLocalCorpus && titleMatch.exact) {
            totalScore += LOCAL_CORPUS_EXACT_SCORE
        }

        return SubjectCandidateMatch(
            subjectId = candidate.subjectId,
            displayTitle = candidate.displayTitle(),
            totalScore = totalScore,
            titleMatch = titleMatch,
            metadataHits = metadataHits,
            originalTitle = candidate.name,
            date = candidate.date,
            company = candidate.companyHint(),
            reasonHint = buildCandidateReasonHint(titleMatch, metadataHits),
            conflictingPremiereYear = conflictingPremiereYear,
        )
    }

    private fun isAcceptableCandidate(candidate: SubjectCandidateMatch): Boolean {
        if (candidate.titleMatch.seasonMismatch) return false
        if (candidate.conflictingPremiereYear && candidate.metadataHits < 1) return false
        return (candidate.titleMatch.exact && candidate.totalScore >= EXACT_TITLE_ACCEPT_SCORE) ||
            (candidate.titleMatch.strong && candidate.metadataHits >= 1) ||
            (candidate.metadataHits >= 2 && candidate.totalScore >= REVIEW_ACCEPT_SCORE_THRESHOLD)
    }

    private fun buildCandidateSubtitle(candidate: SubjectCandidateMatch): String {
        return listOf(
            candidate.originalTitle,
            candidate.date.takeIf { it.isNotBlank() },
            candidate.company.takeIf { it.isNotBlank() },
            candidate.reasonHint.takeIf { it.isNotBlank() },
        )
            .filterNotNull()
            .distinct()
            .joinToString(" · ")
    }

    private fun buildCandidateReasonHint(
        titleMatch: TitleMatchScore,
        metadataHits: Int,
    ): String {
        return when {
            titleMatch.exact && metadataHits >= 2 -> "别名/标题精确匹配"
            titleMatch.exact -> "存在精确别名"
            titleMatch.strong && metadataHits >= 1 -> "标题接近且元数据命中"
            metadataHits >= 2 -> "元数据命中较多"
            else -> ""
        }
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

    private fun hasExactTitleMatch(
        queryTitles: List<String>,
        candidateTitles: List<String>,
    ): Boolean {
        val normalizedQueries = queryTitles.map(::normalizeTitle).filter { it.isNotBlank() }.toSet()
        val normalizedCandidates = candidateTitles.map(::normalizeTitle).filter { it.isNotBlank() }.toSet()
        return normalizedQueries.any { it in normalizedCandidates }
    }

    private fun scoreSingleTitlePair(query: String, candidate: String): TitleMatchScore {
        var score = 0
        var strong = false
        var exact = false
        var seasonMismatch = false
        if (query == candidate) {
            score += 1000
            strong = true
            exact = true
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
        if (querySeason != null && candidateSeason != null) {
            if (querySeason == candidateSeason) {
                score += 140
            } else {
                score -= SEASON_MISMATCH_SCORE
                seasonMismatch = true
            }
        }
        return TitleMatchScore(
            score = score,
            strong = strong,
            exact = exact,
            seasonMismatch = seasonMismatch,
        )
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

    private fun isLikelyCompanyName(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.length <= 1) return false
        return trimmed.any { it.isUpperCase() } || trimmed.any { it.isLetter() && it.code < 128 }
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

    private fun subjectCacheKey(subjectId: Long): String = "bgm_subject_$subjectId"

    private fun subjectCacheKeyPrefix(): String = "bgm_subject_"

    private fun cacheKey(animeId: Long): String = "bgm_$animeId"

    private fun matchCacheKey(animeId: Long): String = "bgm_match_$animeId"

    private fun matchCacheKeyPrefix(): String = "bgm_match_"

    private fun ageLookupCacheKey(animeId: Long): String = "age_lookup_$animeId"

    private fun ageLookupCacheKeyPrefix(): String = "age_lookup_"

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

    private fun loadSubjectSnapshots(): Collection<BangumiSubjectSnapshot> {
        if (!subjectSnapshotCacheLoaded) {
            synchronized(subjectSnapshotCache) {
                if (!subjectSnapshotCacheLoaded) {
                    store.listKeys(subjectCacheKeyPrefix())
                        .mapNotNull { key ->
                            key.removePrefix(subjectCacheKeyPrefix()).toLongOrNull()
                        }
                        .forEach { subjectId ->
                            readSubjectSnapshot(subjectId)
                        }
                    subjectSnapshotCacheLoaded = true
                }
            }
        }
        return subjectSnapshotCache.values
    }

    private fun loadAllAgeLookupMetadata(): List<AgeBangumiLookupMetadata> {
        return store.listKeys(ageLookupCacheKeyPrefix())
            .mapNotNull { key -> key.removePrefix(ageLookupCacheKeyPrefix()).toLongOrNull() }
            .mapNotNull(::readAgeLookupMetadata)
            .sortedBy { it.bestDisplayTitle() }
    }

    private fun clearSubjectSnapshots() {
        subjectSnapshotCache.clear()
        store.listKeys(subjectCacheKeyPrefix()).forEach(store::remove)
        subjectSnapshotCacheLoaded = true
    }

    private fun readSubjectSnapshot(subjectId: Long): BangumiSubjectSnapshot? {
        if (subjectId <= 0L) return null
        subjectSnapshotCache[subjectId]?.let { return it }
        val body = store.read(subjectCacheKey(subjectId)) ?: return null
        return runCatching { json.decodeFromString<BangumiSubjectSnapshot>(body) }
            .getOrNull()
            ?.also { snapshot -> subjectSnapshotCache[subjectId] = snapshot }
    }

    private fun cacheSubjectSnapshot(candidate: BangumiSearchSubject) {
        if (candidate.id <= 0L) return
        writeSubjectSnapshot(
            BangumiSubjectSnapshot(
                subjectId = candidate.id,
                name = candidate.name,
                nameCn = candidate.nameCn,
                aliases = explodeAliasValues(candidate.aliasTitles()),
                date = candidate.date.orEmpty(),
                company = candidate.infoboxValuesMatching("制作").firstOrNull().orEmpty(),
                writer = candidate.infoboxValuesMatching("原作").firstOrNull().orEmpty(),
                website = candidate.infoboxValuesMatching("官方网站").firstOrNull().orEmpty(),
                tags = candidate.tags.map { it.name }.filter { it.isNotBlank() },
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun cacheSubjectSnapshot(
        detail: BangumiSubjectResponse,
        @Suppress("UNUSED_PARAMETER") document: org.jsoup.nodes.Document,
    ) {
        if (detail.id <= 0L) return
        writeSubjectSnapshot(
            BangumiSubjectSnapshot(
                subjectId = detail.id,
                name = detail.name,
                nameCn = detail.nameCn,
                tags = detail.tags.map { it.name }.filter { it.isNotBlank() },
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun writeSubjectSnapshot(snapshot: BangumiSubjectSnapshot) {
        if (snapshot.subjectId <= 0L) return
        val merged = readSubjectSnapshot(snapshot.subjectId).mergeWith(snapshot)
        subjectSnapshotCache[snapshot.subjectId] = merged
        store.write(subjectCacheKey(snapshot.subjectId), json.encodeToString(merged))
    }

    private fun String.matchesCandidateWebsite(candidate: BangumiSubjectCandidateData): Boolean {
        val expectedHost = normalizeHost(this)
        if (expectedHost.isBlank()) return false
        return normalizeHost(candidate.website) == expectedHost
    }

    private fun String.dateMatchLevel(candidate: BangumiSubjectCandidateData): DateMatchLevel {
        if (this.isBlank()) return DateMatchLevel.NONE
        val expected = parseDateParts(this)
        val actual = parseDateParts(candidate.date)
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

    private fun String.hasConflictingPremiereYear(candidate: BangumiSubjectCandidateData): Boolean {
        if (this.isBlank() || candidate.date.isBlank()) return false
        val expectedYear = parseDateParts(this).first ?: return false
        val actualYear = parseDateParts(candidate.date).first ?: return false
        return expectedYear != actualYear
    }

    private fun String.matchesCandidateCompany(candidate: BangumiSubjectCandidateData): Boolean {
        val expected = normalizeLooseToken(this)
        if (expected.isBlank()) return false
        val tagMatch = candidate.tags.any { normalizeLooseToken(it) == expected }
        if (tagMatch) return true
        return candidate.company
            .splitAliasLikeValues()
            .map(::normalizeLooseToken)
            .any { it.contains(expected) || expected.contains(it) }
    }

    private fun String.matchesCandidateOriginalWork(candidate: BangumiSubjectCandidateData): Boolean {
        val expected = normalizeLooseToken(this)
        if (expected.isBlank()) return false
        return candidate.writer
            .splitAliasLikeValues()
            .map(::normalizeLooseToken)
            .any { it.contains(expected) || expected.contains(it) }
    }

    private fun BangumiSubjectCandidateData.displayTitle(): String {
        return nameCn.ifBlank { name }
    }

    private fun BangumiSubjectCandidateData.companyHint(): String {
        return company.splitAliasLikeValues().firstOrNull { it.isNotBlank() }
            ?: tags.firstOrNull { it.isNotBlank() && isLikelyCompanyName(it) }.orEmpty()
    }

    private fun BangumiSubjectCandidateData.allTitles(): List<String> {
        return (listOf(nameCn, name) + aliases)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun BangumiSearchSubject.infoboxValuesMatching(keyword: String): List<String> {
        return infobox.asSequence()
            .filter { it.key.contains(keyword, ignoreCase = true) }
            .flatMap { it.values().asSequence() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun BangumiSearchSubject.aliasTitles(): List<String> {
        return explodeAliasValues(infoboxValuesMatching("别名"))
    }

    private fun BangumiSearchSubject.toCandidateData(): BangumiSubjectCandidateData {
        return BangumiSubjectCandidateData(
            subjectId = id,
            name = name,
            nameCn = nameCn,
            aliases = aliasTitles(),
            date = date.orEmpty(),
            tags = tags.map { it.name }.filter { it.isNotBlank() },
            company = infoboxValuesMatching("制作").firstOrNull().orEmpty(),
            writer = infoboxValuesMatching("原作").firstOrNull().orEmpty(),
            website = infoboxValuesMatching("官方网站").firstOrNull().orEmpty(),
        )
    }

    private fun BangumiSubjectSnapshot.toCandidateData(): BangumiSubjectCandidateData {
        return BangumiSubjectCandidateData(
            subjectId = subjectId,
            name = name,
            nameCn = nameCn,
            aliases = aliases,
            date = date,
            tags = tags,
            company = company,
            writer = writer,
            website = website,
        )
    }

    private fun BangumiSubjectSnapshot.allTitles(): List<String> {
        return (listOf(nameCn, name) + aliases)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun BangumiSubjectCandidateData.mergeWith(other: BangumiSubjectCandidateData): BangumiSubjectCandidateData {
        return BangumiSubjectCandidateData(
            subjectId = if (subjectId > 0L) subjectId else other.subjectId,
            name = other.name.ifBlank { name },
            nameCn = other.nameCn.ifBlank { nameCn },
            aliases = (aliases + other.aliases)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct(),
            date = other.date.ifBlank { date },
            tags = (tags + other.tags)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct(),
            company = other.company.ifBlank { company },
            writer = other.writer.ifBlank { writer },
            website = other.website.ifBlank { website },
        )
    }

    private fun BangumiSubjectSnapshot?.mergeWith(incoming: BangumiSubjectSnapshot): BangumiSubjectSnapshot {
        val current = this
        return BangumiSubjectSnapshot(
            subjectId = incoming.subjectId,
            name = incoming.name.ifBlank { current?.name.orEmpty() },
            nameCn = incoming.nameCn.ifBlank { current?.nameCn.orEmpty() },
            aliases = (current?.aliases.orEmpty() + incoming.aliases)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct(),
            date = incoming.date.ifBlank { current?.date.orEmpty() },
            company = incoming.company.ifBlank { current?.company.orEmpty() },
            writer = incoming.writer.ifBlank { current?.writer.orEmpty() },
            website = incoming.website.ifBlank { current?.website.orEmpty() },
            tags = (current?.tags.orEmpty() + incoming.tags)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct(),
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun explodeAliasValues(values: List<String>): List<String> {
        return values
            .flatMap { value -> value.splitAliasLikeValues() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun String.splitAliasLikeValues(): List<String> {
        return split(Regex("\\s*(?:/|／|\\||｜|、|；|;|\\n|\\r)+\\s*"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
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
        val exact: Boolean = false,
        val seasonMismatch: Boolean = false,
    )

    @Serializable
    private data class BangumiSubjectSnapshot(
        val subjectId: Long = 0L,
        val name: String = "",
        val nameCn: String = "",
        val aliases: List<String> = emptyList(),
        val date: String = "",
        val company: String = "",
        val writer: String = "",
        val website: String = "",
        val tags: List<String> = emptyList(),
        val updatedAtMs: Long = 0L,
    )

    private data class BangumiSubjectCandidateData(
        val subjectId: Long = 0L,
        val name: String = "",
        val nameCn: String = "",
        val aliases: List<String> = emptyList(),
        val date: String = "",
        val tags: List<String> = emptyList(),
        val company: String = "",
        val writer: String = "",
        val website: String = "",
    )

    private data class SubjectCandidateSeed(
        val subjectId: Long,
        val candidate: BangumiSubjectCandidateData,
        val queryIndex: Int = MAX_MATCH_SEARCH_QUERIES,
        val resultIndex: Int = MAX_MATCH_SEARCH_RESULTS,
        val fromSearch: Boolean = false,
        val fromLocalCorpus: Boolean = false,
        val localCorpusScore: Int = 0,
    )

    private data class SubjectCandidateMatch(
        val subjectId: Long,
        val displayTitle: String,
        val totalScore: Int,
        val titleMatch: TitleMatchScore,
        val metadataHits: Int,
        val originalTitle: String = "",
        val date: String = "",
        val company: String = "",
        val reasonHint: String = "",
        val conflictingPremiereYear: Boolean = false,
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
        private const val MAX_MANUAL_CANDIDATES = 8
        private const val DEFAULT_REVIEW_LIMIT = 60
        private const val MAX_LOCAL_CORPUS_CANDIDATES = 48
        private const val LOCAL_BUCKET_PREFIX_LENGTH = 4
        private const val RATING_TTL_MS = 30L * 24 * 60 * 60 * 1000L
        private const val WEBSITE_MATCH_SCORE = 320
        private const val DATE_DAY_MATCH_SCORE = 260
        private const val DATE_MONTH_MATCH_SCORE = 180
        private const val DATE_YEAR_MATCH_SCORE = 90
        private const val DATE_YEAR_MISMATCH_SCORE = 240
        private const val COMPANY_MATCH_SCORE = 220
        private const val WRITER_MATCH_SCORE = 220
        private const val EXACT_ALIAS_METADATA_SCORE = 220
        private const val EXACT_ALIAS_DATE_SCORE = 120
        private const val LOCAL_CORPUS_EXACT_SCORE = 90
        private const val RESULT_RANK_SCORE = 6
        private const val QUERY_RANK_SCORE = 12
        private const val EXACT_TITLE_ACCEPT_SCORE = 760
        private const val REVIEW_TITLE_SCORE_THRESHOLD = 720
        private const val REVIEW_ACCEPT_SCORE_THRESHOLD = 420
        private const val SEASON_MISMATCH_SCORE = 220

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
