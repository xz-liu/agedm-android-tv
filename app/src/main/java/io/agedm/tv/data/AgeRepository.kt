package io.agedm.tv.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

@OptIn(ExperimentalSerializationApi::class)
class AgeRepository(
    private val cache: ContentCache? = null,
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    private val supplementalSourceService by lazy {
        SupplementalSourceService(
            cache = cache,
            client = client,
            json = json,
        )
    }

    suspend fun fetchDetail(animeId: Long, forceRefresh: Boolean = false): AnimeDetail = withContext(Dispatchers.IO) {
        val body = cachedGet(
            key = "detail_$animeId",
            ttlMs = TTL_DETAIL_MS,
            url = apiUrl("detail/$animeId"),
            forceRefresh = forceRefresh,
        )
        val response = json.decodeFromString<AgeDetailResponse>(body)
        val desktopRecommendations = runCatching {
            fetchDesktopRecommendations(animeId, forceRefresh)
        }.getOrElse { emptyList() }
        val detail = response.toAnimeDetail(desktopRecommendations)
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
        val feed = HomeFeed(
            latest = response.latest.map { it.toPosterCard() },
            recommend = response.recommend.map { it.toPosterCard() },
            dailySections = response.weekList
                .entries
                .sortedBy { weekDaySort(it.key) }
                .mapNotNull { (key, items) ->
                    val mapped = items.map { it.toScheduleCard() }
                    if (mapped.isEmpty()) null
                    else BrowseSection(title = weekDayTitle(key), subtitle = "${mapped.size} 部作品", items = mapped)
                },
        )
        touchDetailCaches(
            feed.latest.map { it.animeId } +
                feed.recommend.map { it.animeId } +
                feed.dailySections.flatMap { section -> section.items.map { it.animeId } },
        )
        return feed
    }

    private fun decodeRecommend(body: String, page: Int, size: Int): PagedCards {
        val response = json.decodeFromString<AgePosterListResponse>(body)
        val cards = response.videos.map { it.toPosterCard() }
        touchDetailCaches(cards.map { it.animeId })
        return PagedCards(items = cards, total = response.total, page = page, size = size)
    }

    private fun decodeUpdate(body: String, page: Int, size: Int): PagedCards {
        val response = json.decodeFromString<AgePosterListResponse>(body)
        val cards = response.videos.map { it.toPosterCard() }
        touchDetailCaches(cards.map { it.animeId })
        return PagedCards(items = cards, total = response.total, page = page, size = size)
    }

    private fun decodeCatalog(body: String, query: CatalogQuery): PagedCards {
        val response = json.decodeFromString<AgeCatalogResponse>(body)
        val cards = response.videos.map { it.toAnimeCard() }
        touchDetailCaches(cards.map { it.animeId })
        return PagedCards(items = cards, total = response.total, page = query.page, size = query.size)
    }

    private fun decodeSearch(body: String, page: Int, size: Int): PagedCards {
        val response = json.decodeFromString<AgeSearchResponse>(body)
        val cards = response.data.videos.map { it.toAnimeCard() }
        touchDetailCaches(cards.map { it.animeId })
        return PagedCards(items = cards, total = response.data.total, page = page, size = size)
    }

    private fun decodeRankSections(body: String, year: String): List<BrowseSection> {
        val response = json.decodeFromString<AgeRankResponse>(body)
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

    private fun AgeDetailResponse.toAnimeDetail(desktopRecommendations: List<AgeRelatedItem>): AnimeDetail {
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
            sources = sources,
            related = series,
            similar = desktopRecommendations.ifEmpty { similar },
            vipSourceKeys = vipSources,
            playerJx = playerJx,
        )
    }

    private fun AgeRelatedItem.toPosterCard(): AnimeCard {
        return AnimeCard(
            animeId = animeId,
            title = title,
            cover = cover,
            badge = updateLabel,
            subtitle = "",
        )
    }

    private fun AgeScheduleItem.toScheduleCard(): AnimeCard {
        return AnimeCard(
            animeId = id,
            title = name,
            cover = buildCoverUrl(id),
            badge = if (isNew > 0) "NEW" else "",
            subtitle = nameForNew.ifBlank { mtime.substringAfter(' ', "") },
        )
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
        )
    }

    private fun AgeRankItem.toAnimeCard(rankTitle: String): AnimeCard {
        return AnimeCard(
            animeId = animeId,
            title = title,
            cover = buildCoverUrl(animeId),
            badge = "#$order",
            subtitle = "$rankTitle · $countLabel",
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
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

        private val streamPattern = Regex("""var\s+Vurl\s*=\s*'([^']+)'""")
        private val detailPathPattern = Regex("""/detail/(\d+)""")
    }
}
