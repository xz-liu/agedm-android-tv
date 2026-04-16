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
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {

    suspend fun fetchDetail(animeId: Long): AnimeDetail = withContext(Dispatchers.IO) {
        val body = get("https://api.agedm.io/v2/detail/$animeId")
        val response = json.decodeFromString<AgeDetailResponse>(body)
        response.toAnimeDetail()
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

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

        private val streamPattern = Regex("""var\s+Vurl\s*=\s*'([^']+)'""")
    }
}
