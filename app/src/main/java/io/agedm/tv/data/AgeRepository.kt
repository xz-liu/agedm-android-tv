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

@OptIn(ExperimentalSerializationApi::class)
class AgeRepository(
    private val cache: ContentCache? = null,
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {

    suspend fun fetchDetail(animeId: Long): AnimeDetail = withContext(Dispatchers.IO) {
        val body = cachedGet("detail_$animeId", TTL_DETAIL_MS, apiUrl("detail/$animeId"))
        val response = json.decodeFromString<AgeDetailResponse>(body)
        response.toAnimeDetail()
    }

    suspend fun fetchHomeFeed(): HomeFeed = withContext(Dispatchers.IO) {
        val body = cachedGet("home", TTL_HOME_MS, apiUrl("home-list"))
        val response = json.decodeFromString<AgeHomeResponse>(body)
        HomeFeed(
            latest = response.latest.map { it.toPosterCard() },
            recommend = response.recommend.map { it.toPosterCard() },
            dailySections = response.weekList
                .entries
                .sortedBy { weekDaySort(it.key) }
                .mapNotNull { (key, items) ->
                    val mapped = items.map { it.toScheduleCard() }
                    if (mapped.isEmpty()) {
                        null
                    } else {
                        BrowseSection(
                            title = weekDayTitle(key),
                            subtitle = "${mapped.size} 部作品",
                            items = mapped,
                        )
                    }
                },
        )
    }

    suspend fun fetchRecommend(page: Int = 1, size: Int = 100): PagedCards = withContext(Dispatchers.IO) {
        val body = cachedGet("recommend", TTL_LIST_MS, apiUrl("recommend"))
        val response = json.decodeFromString<AgePosterListResponse>(body)
        PagedCards(
            items = response.videos.map { it.toPosterCard() },
            total = response.total,
            page = page,
            size = size,
        )
    }

    suspend fun fetchUpdate(page: Int, size: Int = 30): PagedCards = withContext(Dispatchers.IO) {
        val url = apiUrl("update", "page" to page.toString(), "size" to size.toString())
        val body = cachedGet("update_p$page", TTL_LIST_MS, url)
        val response = json.decodeFromString<AgePosterListResponse>(body)
        PagedCards(
            items = response.videos.map { it.toPosterCard() },
            total = response.total,
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
        val cacheKey = "catalog_p${query.page}_r${query.region}_g${query.genre}" +
            "_l${query.label}_y${query.year}_o${query.order}"
        val body = cachedGet(cacheKey, TTL_LIST_MS, url)
        val response = json.decodeFromString<AgeCatalogResponse>(body)
        PagedCards(
            items = response.videos.map { it.toAnimeCard() },
            total = response.total,
            page = query.page,
            size = query.size,
        )
    }

    suspend fun search(query: String, page: Int, size: Int = 24): PagedCards = withContext(Dispatchers.IO) {
        val url = apiUrl("search", "query" to query, "page" to page.toString())
        val safeQuery = query.take(40).replace(Regex("[^\\w\\u4e00-\\u9fa5]"), "_")
        val body = cachedGet("search_${safeQuery}_p$page", TTL_SEARCH_MS, url)
        val response = json.decodeFromString<AgeSearchResponse>(body)
        PagedCards(
            items = response.data.videos.map { it.toAnimeCard() },
            total = response.data.total,
            page = page,
            size = size,
        )
    }

    suspend fun fetchRankSections(year: String = "all"): List<BrowseSection> = withContext(Dispatchers.IO) {
        val body = cachedGet("rank_$year", TTL_LIST_MS, apiUrl("rank", "year" to year))
        val response = json.decodeFromString<AgeRankResponse>(body)
        val titles = listOf("周榜", "月榜", "总榜")
        response.rank.mapIndexed { index, entries ->
            BrowseSection(
                title = titles.getOrElse(index) { "排行榜" },
                subtitle = if (year == "all") "Top ${entries.size}" else "$year 年 Top ${entries.size}",
                items = entries.map { it.toAnimeCard(titles.getOrElse(index) { "排行榜" }) },
            )
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

    private fun cachedGet(key: String, ttlMs: Long, url: String): String {
        cache?.get(key, ttlMs)?.let { return it }
        val fresh = get(url)
        cache?.put(key, fresh)
        return fresh
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://m.agedm.io/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("请求失败 ${response.code} $url")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun AgeDetailResponse.toAnimeDetail(): AnimeDetail {
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
            similar = similar,
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
        private const val TTL_DETAIL_MS = 2 * 60 * 60 * 1000L

        private const val API_BASE_URL = "https://api.agedm.io/v2"
        private const val DEFAULT_COVER_BASE = "https://cdn.aqdstatic.com:966/age"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

        private val streamPattern = Regex("""var\s+Vurl\s*=\s*'([^']+)'""")
    }
}
