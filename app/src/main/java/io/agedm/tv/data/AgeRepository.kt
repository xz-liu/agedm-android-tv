package io.agedm.tv.data

import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

@OptIn(ExperimentalSerializationApi::class)
class AgeRepository(
    private val cache: ContentCache? = null,
    private val persistentStore: PersistentJsonStore? = null,
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bangumiPrefetchJobs = ConcurrentHashMap<Long, Job>()
    private val bangumiScoreRequests = ConcurrentHashMap<Long, Deferred<String?>>()
    private val supplementalSourceService by lazy {
        SupplementalSourceService(
            cache = cache,
            client = client,
            json = json,
        )
    }
    private val bangumiService by lazy {
        persistentStore?.let { store ->
            BangumiService(
                store = store,
                client = client,
                json = json,
                alignToAge = ::alignBangumiSubjectToAge,
                ensureAgeMetadata = ::ensureAgeLookupMetadata,
            )
        }
    }
    private val _bangumiMetadataUpdates = MutableSharedFlow<Pair<Long, BangumiMetadata?>>(extraBufferCapacity = 8)
    val bangumiMetadataUpdates = _bangumiMetadataUpdates.asSharedFlow()

    suspend fun fetchDetail(animeId: Long, forceRefresh: Boolean = false): AnimeDetail = withContext(Dispatchers.IO) {
        val body = cachedGet(
            key = "detail_$animeId",
            ttlMs = TTL_DETAIL_MS,
            url = apiUrl("detail/$animeId"),
            forceRefresh = forceRefresh,
        )
        val response = json.decodeFromString<AgeDetailResponse>(body)
        cacheAgeLookupMetadata(response.video.toLookupMetadata())
        val desktopRecommendations = runCatching {
            fetchDesktopRecommendations(animeId, forceRefresh)
        }.getOrElse { emptyList() }
        val bangumi = bangumiService?.peek(animeId)
        val detail = response.toAnimeDetail(
            desktopRecommendations = desktopRecommendations,
            bangumi = bangumi,
        )
        val supplementalSources = supplementalSourceService.loadCachedSources(animeId)
        if (supplementalSources.isEmpty()) {
            detail
        } else {
            detail.copy(
                sources = detail.sources + supplementalSources.filterNot { supplemental ->
                    detail.sources.any { it.key == supplemental.key }
                },
            )
        }
    }

    suspend fun fetchHomeFeed(): HomeFeed = withContext(Dispatchers.IO) {
        decodeHomeFeed(cachedGet(homeCacheKey(), TTL_HOME_MS, apiUrl("home-list")))
    }

    suspend fun fetchRecommend(page: Int = 1, size: Int = 100): PagedCards = withContext(Dispatchers.IO) {
        decodeRecommend(
            body = cachedGet(recommendCacheKey(), TTL_LIST_MS, apiUrl("recommend")),
            page = page,
            size = size,
        )
    }

    suspend fun fetchUpdate(page: Int, size: Int = 30): PagedCards = withContext(Dispatchers.IO) {
        val url = apiUrl("update", "page" to page.toString(), "size" to size.toString())
        decodeUpdate(
            body = cachedGet(updateCacheKey(page), TTL_LIST_MS, url),
            page = page,
            size = size,
        )
    }

    suspend fun fetchCatalog(query: CatalogQuery): PagedCards = withContext(Dispatchers.IO) {
        val url = apiUrl(
            "catalog",
            "page" to query.page.toString(),
            "size" to query.size.toString(),
            "region" to query.region,
            "genre" to query.genre,
            "label" to query.label,
            "year" to query.year,
            "season" to query.season,
            "status" to query.status,
            "resource" to query.resource,
            "letter" to query.letter,
            "order" to query.order,
        )
        decodeCatalog(
            body = cachedGet(catalogCacheKey(query), TTL_LIST_MS, url),
            query = query,
        )
    }

    suspend fun search(query: String, page: Int, size: Int = 24): PagedCards = withContext(Dispatchers.IO) {
        val url = apiUrl("search", "query" to query, "page" to page.toString())
        decodeSearch(
            body = cachedGet(searchCacheKey(query, page), TTL_SEARCH_MS, url),
            page = page,
            size = size,
        )
    }

    suspend fun fetchRankSections(year: String = "all"): List<BrowseSection> = withContext(Dispatchers.IO) {
        decodeRankSections(
            body = cachedGet(rankCacheKey(year), TTL_LIST_MS, apiUrl("rank", "year" to year)),
            year = year,
        )
    }

    suspend fun fetchSupplementalSources(animeId: Long, title: String): List<EpisodeSource> = withContext(Dispatchers.IO) {
        supplementalSourceService.fetchSources(
            animeId = animeId,
            title = title,
        )
    }

    suspend fun searchSupplementalCandidates(
        providerId: String,
        title: String,
    ): List<SupplementalCandidate> = withContext(Dispatchers.IO) {
        supplementalSourceService.searchCandidates(
            providerId = providerId,
            title = title,
        )
    }

    suspend fun fetchSupplementalSourcesForCandidate(
        animeId: Long,
        candidate: SupplementalCandidate,
    ): List<EpisodeSource> = withContext(Dispatchers.IO) {
        supplementalSourceService.fetchSourcesForCandidate(
            animeId = animeId,
            candidate = candidate,
        )
    }

    suspend fun peekBangumiMetadata(animeId: Long): BangumiMetadata? = withContext(Dispatchers.IO) {
        bangumiService?.peek(animeId)
    }

    suspend fun ensureBangumiMetadata(
        animeId: Long,
        title: String,
        forceRefresh: Boolean = false,
    ): BangumiMetadata? = withContext(Dispatchers.IO) {
        rememberBangumiLookupTitle(animeId, title)
        bangumiService?.fetch(animeId, title, forceRefresh)
    }

    suspend fun ensureBangumiSubjectId(
        animeId: Long,
        title: String,
        forceRefreshMatch: Boolean = false,
    ): Long? = withContext(Dispatchers.IO) {
        rememberBangumiLookupTitle(animeId, title)
        bangumiService?.resolveSubjectId(animeId, title, forceRefreshMatch)
    }

    fun prefetchBangumiMetadata(
        animeId: Long,
        title: String,
        forceRefresh: Boolean = false,
    ) {
        if (title.isBlank()) return
        rememberBangumiLookupTitle(animeId, title)
        if (forceRefresh) {
            bangumiPrefetchJobs.remove(animeId)?.cancel()
        } else {
            bangumiPrefetchJobs[animeId]?.takeIf { it.isActive }?.let { return }
        }
        val job = repositoryScope.launch {
            val metadata = runCatching {
                bangumiService?.fetch(animeId, title, forceRefresh)
            }.getOrNull()
            _bangumiMetadataUpdates.tryEmit(animeId to metadata)
            bangumiPrefetchJobs.remove(animeId)
        }
        bangumiPrefetchJobs[animeId] = job
    }

    fun peekBangumiScore(animeId: Long): String? {
        return bangumiService?.peek(animeId)?.scoreLabel
    }

    suspend fun ensureBangumiScore(animeId: Long, title: String): String? = withContext(Dispatchers.IO) {
        rememberBangumiLookupTitle(animeId, title)
        bangumiService?.peek(animeId)?.scoreLabel?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        val service = bangumiService ?: return@withContext null
        bangumiScoreRequests[animeId]?.let { running ->
            return@withContext runCatching { running.await() }.getOrNull()
        }
        val deferred = repositoryScope.async {
            try {
                service.fetchScore(animeId, title)
            } finally {
                bangumiScoreRequests.remove(animeId)
            }
        }
        val running = bangumiScoreRequests.putIfAbsent(animeId, deferred)
        if (running != null) {
            deferred.cancel()
            return@withContext runCatching { running.await() }.getOrNull()
        }
        runCatching { deferred.await() }.getOrNull()
    }

    suspend fun listSuspiciousBangumiMatches(): List<BangumiMatchIssue> = withContext(Dispatchers.IO) {
        backfillAgeLookupMetadataForMatchedEntries()
        bangumiService?.listSuspiciousMatches().orEmpty()
    }

    suspend fun refreshSuspiciousBangumiMatches(): List<BangumiMatchIssue> = withContext(Dispatchers.IO) {
        backfillAgeLookupMetadataForMatchedEntries()
        bangumiService?.refreshSuspiciousMatches().orEmpty()
    }

    suspend fun searchBangumiManualMatchCandidates(
        animeId: Long,
        title: String,
    ): List<BangumiMatchCandidate> = withContext(Dispatchers.IO) {
        ensureAgeLookupMetadata(animeId)
        bangumiService?.searchManualMatchCandidates(animeId, title).orEmpty()
    }

    suspend fun assignManualBangumiMatch(
        animeId: Long,
        title: String,
        subjectId: Long,
    ): BangumiMetadata? = withContext(Dispatchers.IO) {
        ensureAgeLookupMetadata(animeId)
        bangumiService?.assignManualMatch(animeId, title, subjectId)
    }

    suspend fun rebuildBangumiIndexAndRematch(): BangumiRematchSummary = withContext(Dispatchers.IO) {
        backfillAgeLookupMetadataForMatchedEntries()
        bangumiService?.rebuildSubjectIndexAndRematchAll()
            ?: BangumiRematchSummary(
                ageEntries = 0,
                totalQueries = 0,
                searchedQueries = 0,
                skippedQueries = 0,
                indexedSubjects = 0,
                updatedMatches = 0,
                unchangedMatches = 0,
                missingEntries = 0,
            )
    }

    suspend fun rebuildBangumiIndexAndRematch(
        onProgress: suspend (BangumiRematchProgress) -> Unit,
    ): BangumiRematchSummary = withContext(Dispatchers.IO) {
        backfillAgeLookupMetadataForMatchedEntries()
        bangumiService?.rebuildSubjectIndexAndRematchAll(onProgress)
            ?: BangumiRematchSummary(
                ageEntries = 0,
                totalQueries = 0,
                searchedQueries = 0,
                skippedQueries = 0,
                indexedSubjects = 0,
                updatedMatches = 0,
                unchangedMatches = 0,
                missingEntries = 0,
            )
    }

    suspend fun bangumiIndexStats(): BangumiIndexStats = withContext(Dispatchers.IO) {
        backfillAgeLookupMetadataForMatchedEntries()
        bangumiService?.indexStats()
            ?: BangumiIndexStats(
                ageEntries = 0,
                indexedQueries = 0,
                indexedSubjects = 0,
            )
    }

    suspend fun alignBangumiTitlesToAge(
        titles: List<String>,
        excludeAnimeId: Long = 0L,
    ): AgeRelatedItem? = withContext(Dispatchers.IO) {
        alignBangumiSubjectToAge(titles, excludeAnimeId)
    }

    suspend fun peekHomeFeed(): HomeFeed? = withContext(Dispatchers.IO) {
        peekCached(homeCacheKey(), ::decodeHomeFeed)
    }

    suspend fun peekRecommend(page: Int = 1, size: Int = 100): PagedCards? = withContext(Dispatchers.IO) {
        peekCached(recommendCacheKey()) { body ->
            decodeRecommend(body, page, size)
        }
    }

    suspend fun peekUpdate(page: Int, size: Int = 30): PagedCards? = withContext(Dispatchers.IO) {
        peekCached(updateCacheKey(page)) { body ->
            decodeUpdate(body, page, size)
        }
    }

    suspend fun peekCatalog(query: CatalogQuery): PagedCards? = withContext(Dispatchers.IO) {
        peekCached(catalogCacheKey(query)) { body ->
            decodeCatalog(body, query)
        }
    }

    suspend fun peekSearch(query: String, page: Int, size: Int = 24): PagedCards? = withContext(Dispatchers.IO) {
        peekCached(searchCacheKey(query, page)) { body ->
            decodeSearch(body, page, size)
        }
    }

    suspend fun peekRankSections(year: String = "all"): List<BrowseSection>? = withContext(Dispatchers.IO) {
        peekCached(rankCacheKey(year)) { body ->
            decodeRankSections(body, year)
        }
    }

    suspend fun peekDetail(animeId: Long): AnimeDetail? = withContext(Dispatchers.IO) {
        peekCached("detail_$animeId") { body ->
            val response = json.decodeFromString<AgeDetailResponse>(body)
            cacheAgeLookupMetadata(response.video.toLookupMetadata())
            val detail = response.toAnimeDetail(
                desktopRecommendations = emptyList(),
                bangumi = bangumiService?.peek(animeId),
            )
            val supplementalSources = supplementalSourceService.loadCachedSources(animeId)
            if (supplementalSources.isEmpty()) {
                detail
            } else {
                detail.copy(
                    sources = detail.sources + supplementalSources.filterNot { s ->
                        detail.sources.any { it.key == s.key }
                    },
                )
            }
        }
    }

    suspend fun resolveStream(
        detail: AnimeDetail,
        source: EpisodeSource,
        episode: EpisodeItem,
    ): ResolvedStream = withContext(Dispatchers.IO) {
        val parserUrl = buildParserUrl(detail, source, episode)
        val html = get(parserUrl)
        val resolvedUrl = streamPattern.find(html)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("未能从解析页提取真实视频地址")
        val isM3u8 = resolvedUrl.contains(".m3u8", ignoreCase = true)

        ResolvedStream(
            streamUrl = resolvedUrl,
            parserUrl = parserUrl,
            sourceKey = source.key,
            sourceLabel = source.label,
            episode = episode,
            isM3u8 = isM3u8,
            mimeType = inferMimeType(resolvedUrl, isM3u8),
            headers = buildPlaybackHeaders(parserUrl),
        )
    }

    fun buildParserUrl(
        detail: AnimeDetail,
        source: EpisodeSource,
        episode: EpisodeItem,
    ): String {
        val parserBase = if (source.isVipLike) detail.playerJx.vip else detail.playerJx.direct
        if (parserBase.isBlank()) {
            throw IOException("AGE 解析前缀为空")
        }
        return parserBase + episode.token
    }

    fun buildPlaybackHeaders(parserUrl: String): Map<String, String> {
        val origin = parserUrl.toHttpUrlOrNull()?.let { url ->
            val defaultPort = when (url.scheme) {
                "https" -> 443
                else -> 80
            }
            buildString {
                append(url.scheme)
                append("://")
                append(url.host)
                if (url.port != defaultPort) {
                    append(':')
                    append(url.port)
                }
            }
        }.orEmpty()

        return buildMap {
            put("User-Agent", USER_AGENT)
            put("Referer", parserUrl)
            if (origin.isNotBlank()) {
                put("Origin", origin)
            }
        }
    }

    fun inferMimeType(url: String, isM3u8: Boolean): String? {
        if (isM3u8) return "application/x-mpegURL"
        val lower = url.lowercase()
        return when {
            lower.contains(".mp4") -> "video/mp4"
            lower.contains(".flv") -> "video/x-flv"
            lower.contains(".m4v") -> "video/mp4"
            lower.contains("bilivideo.com") -> "video/mp4"
            lower.contains("akamaized.net/obj/") -> "video/mp4"
            else -> null
        }
    }

    fun buildCoverUrl(animeId: Long): String {
        return "$DEFAULT_COVER_BASE/$animeId.jpg"
    }

    private fun touchDetailCaches(ids: List<Long>) {
        ids.forEach { id -> cache?.touch("detail_$id") }
    }

    private fun decodeHomeFeed(body: String): HomeFeed {
        val response = json.decodeFromString<AgeHomeResponse>(body)
        cacheAgeLookupMetadata(response.latest.map { it.toLookupMetadata() })
        cacheAgeLookupMetadata(response.recommend.map { it.toLookupMetadata() })
        cacheAgeLookupMetadata(
            response.weekList.values.flatten().map { item ->
                AgeBangumiLookupMetadata(
                    animeId = item.id,
                    title = item.name,
                    updatedAtMs = System.currentTimeMillis(),
                )
            },
        )
        val latestCards = response.latest.map { it.toPosterCard() }
        // Index by ID so schedule items can check whether they've actually aired today.
        val latestById = latestCards.associateBy { it.animeId }
        val feed = HomeFeed(
            latest = latestCards,
            recommend = response.recommend.map { it.toPosterCard() },
            dailySections = response.weekList
                .entries
                .sortedBy { weekDaySort(it.key) }
                .mapNotNull { (key, items) ->
                    val mapped = items.map { it.toScheduleCard(latestById) }
                    if (mapped.isEmpty()) null
                    else BrowseSection(title = weekDayTitle(key), subtitle = "${mapped.size} 部作品", items = mapped)
                },
        )
        touchDetailCaches(
            latestCards.map { it.animeId } +
                feed.recommend.map { it.animeId } +
                feed.dailySections.flatMap { section -> section.items.map { it.animeId } },
        )
        return feed
    }

    private fun decodeRecommend(body: String, page: Int, size: Int): PagedCards {
        val response = json.decodeFromString<AgePosterListResponse>(body)
        cacheAgeLookupMetadata(response.videos.map { it.toLookupMetadata() })
        val cards = response.videos.map { it.toPosterCard() }
        touchDetailCaches(cards.map { it.animeId })
        return PagedCards(items = cards, total = response.total, page = page, size = size)
    }

    private fun decodeUpdate(body: String, page: Int, size: Int): PagedCards {
        val response = json.decodeFromString<AgePosterListResponse>(body)
        cacheAgeLookupMetadata(response.videos.map { it.toLookupMetadata() })
        val cards = response.videos.map { it.toPosterCard() }
        touchDetailCaches(cards.map { it.animeId })
        return PagedCards(items = cards, total = response.total, page = page, size = size)
    }

    private fun decodeCatalog(body: String, query: CatalogQuery): PagedCards {
        val response = json.decodeFromString<AgeCatalogResponse>(body)
        cacheAgeLookupMetadata(response.videos.map { it.toLookupMetadata() })
        val cards = response.videos.map { it.toAnimeCard() }
        touchDetailCaches(cards.map { it.animeId })
        return PagedCards(items = cards, total = response.total, page = query.page, size = query.size)
    }

    private fun decodeSearch(body: String, page: Int, size: Int): PagedCards {
        val response = json.decodeFromString<AgeSearchResponse>(body)
        cacheAgeLookupMetadata(response.data.videos.map { it.toLookupMetadata() })
        val cards = response.data.videos.map { it.toAnimeCard() }
        touchDetailCaches(cards.map { it.animeId })
        return PagedCards(items = cards, total = response.data.total, page = page, size = size)
    }

    private fun decodeRankSections(body: String, year: String): List<BrowseSection> {
        val response = json.decodeFromString<AgeRankResponse>(body)
        cacheAgeLookupMetadata(
            response.rank.flatten().map { item ->
                AgeBangumiLookupMetadata(
                    animeId = item.animeId,
                    title = item.title,
                    updatedAtMs = System.currentTimeMillis(),
                )
            },
        )
        val titles = listOf("周榜", "月榜", "总榜")
        val sections = response.rank.mapIndexed { index, entries ->
            BrowseSection(
                title = titles.getOrElse(index) { "排行榜" },
                subtitle = if (year == "all") "Top ${entries.size}" else "$year 年 Top ${entries.size}",
                items = entries.map { it.toAnimeCard(titles.getOrElse(index) { "排行榜" }) },
            )
        }
        touchDetailCaches(sections.flatMap { section -> section.items.map { it.animeId } })
        return sections
    }

    private fun cacheAgeLookupMetadata(items: List<AgeBangumiLookupMetadata>) {
        items.forEach(::cacheAgeLookupMetadata)
    }

    private suspend fun backfillAgeLookupMetadataForMatchedEntries() {
        val store = persistentStore ?: return
        store.listKeys("bgm_match_")
            .mapNotNull { it.removePrefix("bgm_match_").toLongOrNull() }
            .forEach { animeId -> ensureAgeLookupMetadata(animeId) }
    }

    private suspend fun ensureAgeLookupMetadata(animeId: Long): AgeBangumiLookupMetadata? {
        readAgeLookupMetadata(animeId)?.let { return it }
        val body = runCatching {
            cachedGet(
                key = "detail_$animeId",
                ttlMs = TTL_DETAIL_MS,
                url = apiUrl("detail/$animeId"),
            )
        }.getOrNull() ?: return null
        val response = runCatching { json.decodeFromString<AgeDetailResponse>(body) }.getOrNull() ?: return null
        val metadata = response.video.toLookupMetadata()
        cacheAgeLookupMetadata(metadata)
        return metadata
    }

    private fun rememberBangumiLookupTitle(animeId: Long, title: String) {
        if (animeId <= 0L || title.isBlank()) return
        cacheAgeLookupMetadata(
            AgeBangumiLookupMetadata(
                animeId = animeId,
                title = title,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun readAgeLookupMetadata(animeId: Long): AgeBangumiLookupMetadata? {
        val store = persistentStore ?: return null
        val body = store.read(ageLookupCacheKey(animeId)) ?: return null
        return runCatching { json.decodeFromString<AgeBangumiLookupMetadata>(body) }.getOrNull()
    }

    private fun cacheAgeLookupMetadata(item: AgeBangumiLookupMetadata?) {
        val snapshot = item ?: return
        if (snapshot.animeId <= 0L) return
        val store = persistentStore ?: return
        val existing = readAgeLookupMetadata(snapshot.animeId)
        val merged = existing.mergeWith(snapshot)
        store.write(ageLookupCacheKey(snapshot.animeId), json.encodeToString(merged))
    }

    private fun AgeBangumiLookupMetadata?.mergeWith(
        incoming: AgeBangumiLookupMetadata,
    ): AgeBangumiLookupMetadata {
        val current = this
        return AgeBangumiLookupMetadata(
            animeId = incoming.animeId,
            title = incoming.title.ifBlank { current?.title.orEmpty() },
            originalTitle = incoming.originalTitle.ifBlank { current?.originalTitle.orEmpty() },
            otherTitles = (current?.otherTitles.orEmpty() + incoming.otherTitles)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct(),
            premiere = incoming.premiere.ifBlank { current?.premiere.orEmpty() },
            company = incoming.company.ifBlank { current?.company.orEmpty() },
            writer = incoming.writer.ifBlank { current?.writer.orEmpty() },
            website = incoming.website.ifBlank { current?.website.orEmpty() },
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun ageLookupCacheKey(animeId: Long): String = "age_lookup_$animeId"

    private fun <T> peekCached(key: String, decode: (String) -> T): T? {
        val contentCache = cache ?: return null
        val body = contentCache.peek(key) ?: return null
        return runCatching { decode(body) }
            .onSuccess { contentCache.touch(key) }
            .getOrNull()
    }

    private fun cachedGet(
        key: String,
        ttlMs: Long,
        url: String,
        referer: String = MOBILE_REFERER,
        forceRefresh: Boolean = false,
    ): String {
        if (!forceRefresh) {
            cache?.get(key, ttlMs)?.let { return it }
        }
        val fresh = get(url, referer)
        cache?.put(key, fresh)
        return fresh
    }

    private fun get(url: String, referer: String = MOBILE_REFERER): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("请求失败 ${response.code} $url")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun fetchDesktopRecommendations(animeId: Long, forceRefresh: Boolean = false): List<AgeRelatedItem> {
        val html = cachedGet(
            key = "detail_desktop_$animeId",
            ttlMs = TTL_DETAIL_MS,
            url = desktopDetailUrl(animeId),
            referer = DESKTOP_REFERER,
            forceRefresh = forceRefresh,
        )
        val document = Jsoup.parse(html)
        val section = document.selectFirst("div.video_list_box--hd:matchesOwn(^相关推荐$)")
            ?: document.getElementsContainingOwnText("相关推荐")
                .firstOrNull { it.text().trim() == "相关推荐" }
            ?: return emptyList()
        val cards = section.parent()
            ?.select("div.video_list_box--bd div.video_item")
            .orEmpty()

        return cards.mapNotNull { card ->
            val link = card.selectFirst("a[href*=/detail/]") ?: return@mapNotNull null
            val animeIdValue = detailPathPattern.find(link.attr("href"))?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: return@mapNotNull null
            AgeRelatedItem(
                animeId = animeIdValue,
                title = link.text().trim(),
                cover = card.selectFirst("img")?.attr("data-original")
                    ?.ifBlank { card.selectFirst("img")?.attr("src").orEmpty() }
                    .orEmpty(),
                updateLabel = card.selectFirst("span.video_item--info")?.text().orEmpty(),
            )
        }
            .distinctBy { it.animeId }
    }

    private fun desktopDetailUrl(animeId: Long): String {
        return "$DESKTOP_BASE_URL/detail/$animeId"
    }

    private fun homeCacheKey(): String = "home"

    private fun recommendCacheKey(): String = "recommend"

    private fun updateCacheKey(page: Int): String = "update_p$page"

    private fun catalogCacheKey(query: CatalogQuery): String {
        return "catalog_p${query.page}_r${query.region}_g${query.genre}" +
            "_l${query.label}_y${query.year}_o${query.order}"
    }

    private fun searchCacheKey(query: String, page: Int): String {
        val safeQuery = query.take(40).replace(Regex("[^\\w\\u4e00-\\u9fa5]"), "_")
        return "search_${safeQuery}_p$page"
    }

    private fun rankCacheKey(year: String): String = "rank_$year"

    private suspend fun alignBangumiSubjectToAge(
        titles: List<String>,
        excludeAnimeId: Long,
    ): AgeRelatedItem? {
        val cleanedTitles = titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleanedTitles.isEmpty()) return null
        val searchableTitles = preferredBangumiSearchTitles(cleanedTitles)

        readBangumiAgeAlignment(cleanedTitles)?.let { cached ->
            val cachedItem = cached.toItem()
            if (cachedItem == null) return null
            if (cachedItem.animeId != excludeAnimeId) return cachedItem
        }

        var bestCard: AnimeCard? = null
        var bestScore = 0
        for (query in searchableTitles) {
            val seenIds = mutableSetOf<Long>()
            for (searchQuery in buildBangumiAlignmentQueries(query)) {
                val results = runCatching { searchBangumiAlignmentCandidates(searchQuery) }
                    .getOrNull()
                    .orEmpty()
                for (candidate in results) {
                    if (candidate.id == excludeAnimeId || !seenIds.add(candidate.id)) continue
                    val score = scoreBangumiAlignment(
                        titles = cleanedTitles,
                        candidateTitles = listOf(
                            candidate.name.orEmpty(),
                            candidate.originalName.orEmpty(),
                            candidate.otherName.orEmpty(),
                        ),
                    )
                    if (score > bestScore) {
                        bestScore = score
                        bestCard = candidate.toAnimeCard()
                    }
                }
            }
        }

        val matched = bestCard?.takeIf { bestScore > 0 }?.let { candidate ->
            AgeRelatedItem(
                animeId = candidate.animeId,
                title = candidate.title,
                cover = candidate.cover,
                updateLabel = candidate.badge,
            )
        }
        writeBangumiAgeAlignment(cleanedTitles, matched)
        return matched?.takeIf { it.animeId != excludeAnimeId }
    }

    private fun searchBangumiAlignmentCandidates(query: String): List<AgeCatalogVideo> {
        val body = cachedGet(
            key = searchCacheKey(query, 1),
            ttlMs = TTL_SEARCH_MS,
            url = apiUrl("search", "query" to query, "page" to "1"),
        )
        val response = json.decodeFromString<AgeSearchResponse>(body)
        return if (response.code == 200) {
            cacheAgeLookupMetadata(response.data.videos.map { it.toLookupMetadata() })
            response.data.videos
        } else {
            emptyList()
        }
    }

    private fun readBangumiAgeAlignment(titles: List<String>): BangumiAgeAlignmentCacheEntry? {
        val store = persistentStore ?: return null
        val body = store.read(bangumiAgeAlignmentCacheKey(titles)) ?: return null
        return runCatching {
            json.decodeFromString<BangumiAgeAlignmentCacheEntry>(body)
        }.getOrNull()
    }

    private fun writeBangumiAgeAlignment(titles: List<String>, item: AgeRelatedItem?) {
        val store = persistentStore ?: return
        val payload = if (item == null) {
            BangumiAgeAlignmentCacheEntry(
                updatedAtMs = System.currentTimeMillis(),
                miss = true,
            )
        } else {
            BangumiAgeAlignmentCacheEntry(
                animeId = item.animeId,
                title = item.title,
                cover = item.cover,
                updateLabel = item.updateLabel,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        store.write(
            bangumiAgeAlignmentCacheKey(titles),
            json.encodeToString(payload),
        )
    }

    private fun bangumiAgeAlignmentCacheKey(titles: List<String>): String {
        val normalized = titles.asSequence()
            .map(::normalizeBangumiLookupTitle)
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString("|")
        return "bgm_age_align_${normalized.sha256Hex()}"
    }

    private fun scoreBangumiAlignment(
        titles: List<String>,
        candidateTitles: List<String>,
    ): Int {
        val normalizedCandidates = candidateTitles
            .map(::normalizeBangumiLookupTitle)
            .filter { it.isNotBlank() }
        if (normalizedCandidates.isEmpty()) return 0
        var best = 0
        titles.forEach { rawTitle ->
            val normalizedTitle = normalizeBangumiLookupTitle(rawTitle)
            if (normalizedTitle.isBlank()) return@forEach
            normalizedCandidates.forEach { normalizedCandidate ->
                var score = 0
                if (normalizedTitle == normalizedCandidate) score += 1000
                if (normalizedCandidate.contains(normalizedTitle) || normalizedTitle.contains(normalizedCandidate)) {
                    score += 360
                }
                val commonPrefix = normalizedTitle.zip(normalizedCandidate)
                    .takeWhile { it.first == it.second }
                    .count()
                score += commonPrefix * 8
                if (score > best) best = score
            }
        }
        return best
    }

    private fun preferredBangumiSearchTitles(titles: List<String>): List<String> {
        val chineseLike = titles.filter(::containsHanCharacters)
        if (chineseLike.isNotEmpty()) return chineseLike
        val nonJapanese = titles.filterNot(::containsJapaneseKana)
        return if (nonJapanese.isNotEmpty()) nonJapanese else titles
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

    private fun buildBangumiAlignmentQueries(title: String): List<String> {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return emptyList()

        val compact = trimmed.removeAgeSearchPunctuation()
        val seasonless = trimmed.stripSeasonHint().trim()
        val compactSeasonless = seasonless.removeAgeSearchPunctuation()
        val queries = linkedSetOf<String>()

        fun add(value: String) {
            val normalized = value.trim()
            if (normalized.length >= 2) {
                queries += normalized
            }
        }

        add(trimmed)
        add(compact)
        add(seasonless)
        add(compactSeasonless)

        val fragmentSource = compactSeasonless.ifBlank { compact }.ifBlank { trimmed }
        if (fragmentSource.length >= 6) {
            listOf(
                fragmentSource.drop(2),
                fragmentSource.drop(4),
                fragmentSource.dropLast(2),
                fragmentSource.dropLast(4),
            ).forEach(::add)
            listOf(4, 6, 8).forEach { length ->
                if (fragmentSource.length > length) {
                    add(fragmentSource.take(length))
                    add(fragmentSource.takeLast(length))
                }
            }
        }

        return queries.take(MAX_ALIGNMENT_SEARCH_QUERIES)
    }

    private fun String.stripSeasonHint(): String {
        return this
            .replace(Regex("""\s*第\s*[0-9一二三四五六七八九十两]+[季期部篇]\s*$"""), "")
            .replace(Regex("""\s*[0-9]+(?:st|nd|rd|th)?\s*season\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*season\s*[0-9]+\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+$"""), "")
            .replace(Regex("""\s+[0-9]+$"""), "")
    }

    private fun String.removeAgeSearchPunctuation(): String {
        return replace(Regex("[\\s\\u3000·・:：\\-_/\\\\|()（）\\[\\]【】《》「」『』\"'`!！?？,.，。~～〜]+"), "")
    }

    private fun normalizeBangumiLookupTitle(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("[\\s\\u3000·・:：\\-_/\\\\|()（）\\[\\]【】《》「」『』\"'`!！?？,.，。~～〜]+"), "")
    }

    private fun AgeDetailResponse.toAnimeDetail(
        desktopRecommendations: List<AgeRelatedItem>,
        bangumi: BangumiMetadata?,
    ): AnimeDetail {
        val vipSources = playerVipRaw.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        val sources = video.playlists.mapNotNull { (key, rows) ->
            val episodes = rows.mapIndexedNotNull { index, row ->
                val title = row.getOrNull(0)?.trim().orEmpty()
                val token = row.getOrNull(1)?.trim().orEmpty()
                if (token.isBlank()) {
                    null
                } else {
                    EpisodeItem(
                        index = index,
                        label = title.ifBlank { "第${index + 1}集" },
                        token = token,
                    )
                }
            }

            if (episodes.isEmpty()) {
                null
            } else {
                EpisodeSource(
                    key = key,
                    label = playerLabelMap[key].orEmpty().ifBlank { key.uppercase() },
                    isVipLike = vipSources.contains(key),
                    episodes = episodes,
                )
            }
        }

        return AnimeDetail(
            animeId = video.id,
            title = video.name,
            cover = video.cover,
            introHtml = video.introHtml,
            status = video.status,
            tags = video.tags,
            bangumi = bangumi,
            sources = sources,
            related = series,
            similar = desktopRecommendations.ifEmpty { similar },
            vipSourceKeys = vipSources,
            playerJx = playerJx,
        )
    }

    private fun AgeVideo.toLookupMetadata(): AgeBangumiLookupMetadata {
        return AgeBangumiLookupMetadata(
            animeId = id,
            title = name,
            originalTitle = originalName,
            otherTitles = splitLookupAliases(otherName),
            premiere = premiere,
            company = company,
            writer = writer,
            website = website,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun AgeCatalogVideo.toLookupMetadata(): AgeBangumiLookupMetadata {
        return AgeBangumiLookupMetadata(
            animeId = id,
            title = name.orEmpty(),
            originalTitle = originalName.orEmpty(),
            otherTitles = splitLookupAliases(otherName),
            premiere = premiere.orEmpty(),
            company = company.orEmpty(),
            writer = writer.orEmpty(),
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun AgeRelatedItem.toLookupMetadata(): AgeBangumiLookupMetadata {
        return AgeBangumiLookupMetadata(
            animeId = animeId,
            title = title,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun splitLookupAliases(raw: String?): List<String> {
        return raw.orEmpty()
            .split('/', '／', '|', '｜')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun AgeRelatedItem.toPosterCard(): AnimeCard {
        return AnimeCard(
            animeId = animeId,
            title = title,
            cover = cover,
            badge = updateLabel,
            subtitle = "",
            bgmScore = peekBangumiScore(animeId).orEmpty(),
        )
    }

    private fun AgeScheduleItem.toScheduleCard(latestById: Map<Long, AnimeCard>): AnimeCard {
        val latestCard = latestById[id]
        return if (latestCard != null) {
            // Confirmed aired: show the episode label from the recent-updates feed.
            AnimeCard(
                animeId = id,
                title = name,
                cover = buildCoverUrl(id),
                badge = latestCard.badge.ifBlank { "已更新" },
                subtitle = "",
                bgmScore = peekBangumiScore(id).orEmpty(),
            )
        } else {
            // Not yet aired: show scheduled local air time (converted from CST).
            AnimeCard(
                animeId = id,
                title = name,
                cover = buildCoverUrl(id),
                badge = "",
                subtitle = formatLocalAirTime(mtime, nameForNew),
                bgmScore = peekBangumiScore(id).orEmpty(),
            )
        }
    }

    private fun formatLocalAirTime(mtime: String, nameForNew: String): String {
        if (nameForNew.isNotBlank()) return nameForNew
        if (mtime.isBlank()) return ""
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            val date = sdf.parse(mtime) ?: return mtime.substringAfter(' ', "")
            val localSdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            localSdf.timeZone = java.util.TimeZone.getDefault()
            localSdf.format(date)
        } catch (_: Exception) {
            mtime.substringAfter(' ', "")
        }
    }

    private fun AgeCatalogVideo.toAnimeCard(): AnimeCard {
        val subtitleParts = buildList {
            premiere?.takeIf { it.isNotBlank() }?.let(::add)
            genreType?.takeIf { it.isNotBlank() }?.let(::add)
            status?.takeIf { it.isNotBlank() }?.let(::add)
        }
        return AnimeCard(
            animeId = id,
            title = name.orEmpty().ifBlank { "未命名动画" },
            cover = cover.orEmpty().ifBlank { buildCoverUrl(id) },
            badge = updateLabel.orEmpty(),
            subtitle = subtitleParts.joinToString(" · "),
            description = tags.orEmpty()
                .ifBlank { writer.orEmpty() }
                .ifBlank { company.orEmpty() },
            bgmScore = peekBangumiScore(id).orEmpty(),
        )
    }

    private fun AgeRankItem.toAnimeCard(rankTitle: String): AnimeCard {
        return AnimeCard(
            animeId = animeId,
            title = title,
            cover = buildCoverUrl(animeId),
            badge = "#$order",
            subtitle = "$rankTitle · $countLabel",
            bgmScore = peekBangumiScore(animeId).orEmpty(),
        )
    }

    private fun apiUrl(path: String, vararg queryPairs: Pair<String, String>): String {
        val builder = "${API_BASE_URL.trimEnd('/')}/$path".toHttpUrlOrNull()?.newBuilder()
            ?: throw IOException("无效接口地址 $path")
        queryPairs.forEach { (name, value) ->
            if (value.isNotBlank()) {
                builder.addQueryParameter(name, value)
            }
        }
        return builder.build().toString()
    }

    private fun weekDaySort(rawKey: String): Int {
        return when (rawKey) {
            "1" -> 1
            "2" -> 2
            "3" -> 3
            "4" -> 4
            "5" -> 5
            "6" -> 6
            "0" -> 7
            else -> 99
        }
    }

    private fun weekDayTitle(rawKey: String): String {
        return when (rawKey) {
            "1" -> "周一追番"
            "2" -> "周二追番"
            "3" -> "周三追番"
            "4" -> "周四追番"
            "5" -> "周五追番"
            "6" -> "周六追番"
            "0" -> "周日追番"
            else -> "每日更新"
        }
    }

    companion object {
        private const val TTL_HOME_MS = 10 * 60 * 1000L
        private const val TTL_LIST_MS = 10 * 60 * 1000L
        private const val TTL_SEARCH_MS = 5 * 60 * 1000L
        private const val TTL_DETAIL_MS = ContentCache.MAX_AGE_MS

        private const val API_BASE_URL = "https://api.agedm.io/v2"
        private const val DESKTOP_BASE_URL = "https://www.agedm.io"
        private const val DEFAULT_COVER_BASE = "https://cdn.aqdstatic.com:966/age"
        private const val MOBILE_REFERER = "https://m.agedm.io/"
        private const val DESKTOP_REFERER = "https://www.agedm.io/"
        private const val MAX_ALIGNMENT_SEARCH_QUERIES = 10
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

        private val streamPattern = Regex("""var\s+Vurl\s*=\s*'([^']+)'""")
        private val detailPathPattern = Regex("""/detail/(\d+)""")
    }

    @Serializable
    private data class BangumiAgeAlignmentCacheEntry(
        val animeId: Long = 0L,
        val title: String = "",
        val cover: String = "",
        val updateLabel: String = "",
        val updatedAtMs: Long = 0L,
        val miss: Boolean = false,
    ) {
        fun toItem(): AgeRelatedItem? {
            if (miss || animeId <= 0L) return null
            return AgeRelatedItem(
                animeId = animeId,
                title = title,
                cover = cover,
                updateLabel = updateLabel,
            )
        }
    }

    private fun String.sha256Hex(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return bytes.joinToString(separator = "") { byte ->
            byte.toInt().and(0xff).toString(16).padStart(2, '0')
        }
    }
}
