package io.agedm.tv.data

import java.io.IOException
import java.net.URLEncoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

internal class SupplementalSourceService(
    private val cache: ContentCache?,
    private val client: OkHttpClient,
    private val json: Json,
) {

    fun loadCachedSources(animeId: Long): List<EpisodeSource> {
        val body = cache?.peek(cacheKey(animeId)) ?: return emptyList()
        return decodeSources(body)
    }

    fun fetchSources(
        animeId: Long,
        title: String,
    ): List<EpisodeSource> {
        val stale = loadCachedSources(animeId)
        if (title.isBlank()) return stale

        val sources = providers.flatMap { provider ->
            runCatching { fetchProviderSources(provider, title) }
                .getOrElse { emptyList() }
        }

        if (sources.isNotEmpty()) {
            val payload = SupplementalSourceCache(
                sources = sources.map { source ->
                    SupplementalSourceEntry(
                        key = source.key,
                        label = source.label,
                        providerId = source.providerName,
                        episodes = source.episodes.map { episode ->
                            SupplementalEpisodeEntry(
                                label = episode.label,
                                token = episode.token,
                            )
                        },
                    )
                },
            )
            cache?.put(cacheKey(animeId), json.encodeToString(payload))
            return sources
        }

        return stale
    }

    private fun decodeSources(body: String): List<EpisodeSource> {
        val payload = runCatching {
            json.decodeFromString<SupplementalSourceCache>(body)
        }.getOrNull() ?: return emptyList()

        return payload.sources.mapNotNull { entry ->
            val provider = providers.firstOrNull { it.id == entry.providerId } ?: return@mapNotNull null
            val episodes = entry.episodes.mapIndexed { index, episode ->
                EpisodeItem(
                    index = index,
                    label = episode.label,
                    token = episode.token,
                )
            }
            EpisodeSource(
                key = entry.key,
                label = entry.label,
                isVipLike = false,
                episodes = episodes,
                providerName = provider.id,
                resolver = SourceResolver.WEB_PAGE,
                pageHeaders = provider.pageHeaders(),
            )
        }
    }

    private fun fetchProviderSources(
        provider: ProviderConfig,
        title: String,
    ): List<EpisodeSource> {
        val match = searchBestMatch(provider, title) ?: return emptyList()
        val roads = fetchRoads(provider, match.url)
        return roads.mapIndexedNotNull { roadIndex, road ->
            if (road.episodes.isEmpty()) return@mapIndexedNotNull null

            EpisodeSource(
                key = "ext_${provider.id}_${roadIndex + 1}",
                label = "${provider.displayName} · 播放列表${roadIndex + 1}",
                isVipLike = false,
                episodes = road.episodes.mapIndexed { index, episode ->
                    EpisodeItem(
                        index = index,
                        label = normalizeEpisodeLabel(episode.label, index),
                        token = episode.url,
                    )
                },
                providerName = provider.id,
                resolver = SourceResolver.WEB_PAGE,
                pageHeaders = provider.pageHeaders(),
            )
        }
    }

    private fun searchBestMatch(provider: ProviderConfig, title: String): ProviderMatch? {
        val encoded = URLEncoder.encode(title, Charsets.UTF_8.name())
        val url = provider.searchUrl.replace("@keyword", encoded)
        val document = fetchDocument(url, provider.baseUrl)
        val items = document.selectXpath(provider.searchListXpath)
            .mapNotNull { element ->
                val name = element.selectXpath(relativeXpath(provider.searchNameXpath), Element::class.java)
                    .firstOrNull()
                    ?.text()
                    ?.trim()
                    .orEmpty()
                val href = element.selectXpath(relativeXpath(provider.searchResultXpath), Element::class.java)
                    .firstOrNull()
                    ?.attr("href")
                    .orEmpty()
                if (name.isBlank() || href.isBlank()) {
                    null
                } else {
                    ProviderMatch(
                        title = name,
                        url = buildAbsoluteUrl(provider.baseUrl, href),
                    )
                }
            }

        return items.maxByOrNull { scoreTitleMatch(title, it.title) }
            ?.takeIf { scoreTitleMatch(title, it.title) > 0 }
    }

    private fun fetchRoads(provider: ProviderConfig, detailUrl: String): List<ProviderRoad> {
        val document = fetchDocument(detailUrl, provider.baseUrl)
        return document.selectXpath(provider.chapterRoadsXpath)
            .mapNotNull { roadNode ->
                val episodes = roadNode.selectXpath(relativeXpath(provider.chapterResultXpath), Element::class.java)
                    .mapNotNull { episodeNode ->
                        val href = episodeNode.attr("href").trim()
                        if (href.isBlank()) return@mapNotNull null
                        ProviderEpisode(
                            label = episodeNode.text().trim(),
                            url = buildAbsoluteUrl(provider.baseUrl, href),
                        )
                    }
                if (episodes.isEmpty()) null else ProviderRoad(episodes)
            }
    }

    private fun normalizeEpisodeLabel(rawLabel: String, index: Int): String {
        return rawLabel.ifBlank { "第${index + 1}集" }
    }

    private fun fetchDocument(url: String, referer: String): org.jsoup.nodes.Document {
        val html = fetchHtml(url, referer)
        return Jsoup.parse(html, url)
    }

    private fun fetchHtml(url: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", WEB_USER_AGENT)
            .header("Referer", referer)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("请求失败 ${response.code} $url")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun buildAbsoluteUrl(baseUrl: String, rawUrl: String): String {
        if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            return rawUrl
        }
        val base = baseUrl.toHttpUrlOrNull() ?: return rawUrl
        return base.resolve(rawUrl)?.toString() ?: rawUrl
    }

    private fun relativeXpath(xpath: String): String {
        return if (xpath.startsWith("//")) ".${xpath}" else xpath
    }

    private fun scoreTitleMatch(query: String, candidate: String): Int {
        val normalizedQuery = normalizeTitle(query)
        val normalizedCandidate = normalizeTitle(candidate)
        if (normalizedQuery.isBlank() || normalizedCandidate.isBlank()) return 0

        var score = 0
        if (normalizedQuery == normalizedCandidate) score += 1000
        if (normalizedCandidate.contains(normalizedQuery) || normalizedQuery.contains(normalizedCandidate)) {
            score += 400
        }

        val commonLength = normalizedQuery.zip(normalizedCandidate)
            .takeWhile { it.first == it.second }
            .count()
        score += commonLength * 8

        val seasonQuery = extractSeasonHint(query)
        val seasonCandidate = extractSeasonHint(candidate)
        if (seasonQuery != null && seasonQuery == seasonCandidate) {
            score += 160
        }

        return score
    }

    private fun normalizeTitle(value: String): String {
        return value.lowercase()
            .replace(Regex("[\\s·:：\\-_/\\\\|()（）\\[\\]【】《》\"'`]+"), "")
    }

    private fun extractSeasonHint(value: String): Int? {
        val numberMatch = Regex("第\\s*([0-9]+)\\s*[季期部篇]").find(value)
            ?: Regex("([0-9]+)(?:st|nd|rd|th)?\\s*(?:season|季|期|部|篇)", RegexOption.IGNORE_CASE).find(value)
        if (numberMatch != null) {
            return numberMatch.groupValues.getOrNull(1)?.toIntOrNull()
        }

        val chineseMatch = Regex("第\\s*([一二三四五六七八九十两]+)\\s*[季期部篇]").find(value)
            ?: return null
        return chineseNumberToInt(chineseMatch.groupValues.getOrNull(1).orEmpty())
    }

    private fun chineseNumberToInt(raw: String): Int? {
        if (raw.isBlank()) return null
        val digits = mapOf(
            '零' to 0,
            '一' to 1,
            '二' to 2,
            '两' to 2,
            '三' to 3,
            '四' to 4,
            '五' to 5,
            '六' to 6,
            '七' to 7,
            '八' to 8,
            '九' to 9,
        )
        if (raw == "十") return 10
        if (!raw.contains('十')) return digits[raw.first()]
        val parts = raw.split('十')
        val tens = parts.firstOrNull().orEmpty().takeIf { it.isNotBlank() }?.let { digits[it.first()] } ?: 1
        val ones = parts.getOrNull(1).orEmpty().takeIf { it.isNotBlank() }?.let { digits[it.first()] } ?: 0
        return tens * 10 + ones
    }

    private fun ProviderConfig.pageHeaders(): Map<String, String> {
        return mapOf(
            "Referer" to baseUrl,
            "User-Agent" to WEB_USER_AGENT,
        )
    }

    private fun cacheKey(animeId: Long): String = "supplemental_$animeId"

    @Serializable
    private data class SupplementalSourceCache(
        val sources: List<SupplementalSourceEntry> = emptyList(),
    )

    @Serializable
    private data class SupplementalSourceEntry(
        val key: String,
        val label: String,
        val providerId: String,
        val episodes: List<SupplementalEpisodeEntry> = emptyList(),
    )

    @Serializable
    private data class SupplementalEpisodeEntry(
        val label: String,
        val token: String,
    )

    private data class ProviderConfig(
        val id: String,
        val displayName: String,
        val baseUrl: String,
        val searchUrl: String,
        val searchListXpath: String,
        val searchNameXpath: String,
        val searchResultXpath: String,
        val chapterRoadsXpath: String,
        val chapterResultXpath: String,
    )

    private data class ProviderMatch(
        val title: String,
        val url: String,
    )

    private data class ProviderRoad(
        val episodes: List<ProviderEpisode>,
    )

    private data class ProviderEpisode(
        val label: String,
        val url: String,
    )

    companion object {
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

        private val providers = listOf(
            ProviderConfig(
                id = "dm84",
                displayName = "DM84",
                baseUrl = "https://dmbus.cc/",
                searchUrl = "https://dmbus.cc/s----------.html?wd=@keyword",
                searchListXpath = "//div/div[3]/ul/li",
                searchNameXpath = "//div/a[2]",
                searchResultXpath = "//div/a[2]",
                chapterRoadsXpath = "//div/div[4]/div/ul",
                chapterResultXpath = "//li/a",
            ),
            ProviderConfig(
                id = "aafun",
                displayName = "aafun",
                baseUrl = "https://www.aafun.cc/",
                searchUrl = "https://www.aafun.cc/feng-s.html?wd=@keyword&submit=",
                searchListXpath = "//div/div[2]/div/div[2]/div/div/div[1]/div/div[2]/div/ul/li",
                searchNameXpath = "//div/div/div[2]/div[1]/div/a",
                searchResultXpath = "//div/div/div[2]/div[1]/div/a",
                chapterRoadsXpath = "//div[2]/div[2]/div/div/div[2]/div/div[1]/div[2]/div/div/div/ul",
                chapterResultXpath = "//li/a",
            ),
        )
    }
}
