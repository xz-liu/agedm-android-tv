package io.agedm.tv.data

import android.net.Uri

sealed interface AgeRoute {
    data object Home : AgeRoute
    data class Detail(val animeId: Long) : AgeRoute
    data class Play(
        val animeId: Long,
        val sourceIndex: Int,
        val episodeIndex: Int,
    ) : AgeRoute

    data class Search(val query: String?) : AgeRoute
    data class Web(val url: String) : AgeRoute
}

object AgeLinks {
    private val detailPattern = Regex("""/detail/(\d+)""")
    private val playPattern = Regex("""/play/(\d+)/(\d+)/(\d+)""")

    const val BASE_WEB_URL = "https://m.agedm.io/#/"

    fun parseInput(rawInput: String?): AgeRoute? {
        val input = rawInput?.trim().orEmpty()
        if (input.isBlank()) return null
        if (input.matches(Regex("""\d+"""))) {
            return AgeRoute.Detail(input.toLong())
        }

        val normalized = when {
            input.startsWith("http://", true) || input.startsWith("https://", true) -> input
            input.startsWith("#/") -> "https://m.agedm.io/$input"
            input.startsWith("/") -> "https://m.agedm.io/#$input"
            else -> return null
        }

        val uri = Uri.parse(normalized)
        if (!isAllowedAgeHost(uri.host)) return null

        val routePath = when {
            !uri.fragment.isNullOrBlank() -> ensureLeadingSlash(uri.fragment!!)
            !uri.encodedPath.isNullOrBlank() -> ensureLeadingSlash(uri.encodedPath!!)
            else -> "/"
        }

        parseRoutePath(routePath)?.let { return it }
        return AgeRoute.Web(normalized)
    }

    fun parseCurrentUrl(url: String?): AgeRoute? {
        return parseInput(url)
    }

    fun buildWebUrl(route: AgeRoute): String {
        return when (route) {
            AgeRoute.Home -> BASE_WEB_URL
            is AgeRoute.Detail -> "https://m.agedm.io/#/detail/${route.animeId}"
            is AgeRoute.Play -> "https://m.agedm.io/#/play/${route.animeId}/${route.sourceIndex}/${route.episodeIndex}"
            is AgeRoute.Search -> {
                val query = route.query?.takeIf { it.isNotBlank() }?.let {
                    "?query=${Uri.encode(it)}"
                }.orEmpty()
                "https://m.agedm.io/#/search$query"
            }

            is AgeRoute.Web -> route.url
        }
    }

    fun isAllowedTopLevelUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = Uri.parse(url)
        if (url.startsWith("about:blank")) return true
        return isAllowedAgeHost(uri.host)
    }

    fun describe(route: AgeRoute): String {
        return when (route) {
            AgeRoute.Home -> "首页"
            is AgeRoute.Detail -> "详情页 ${route.animeId}"
            is AgeRoute.Play -> "播放页 ${route.animeId} / ${route.sourceIndex}-${route.episodeIndex}"
            is AgeRoute.Search -> "搜索 ${route.query.orEmpty()}"
            is AgeRoute.Web -> route.url
        }
    }

    private fun parseRoutePath(routePath: String): AgeRoute? {
        if (routePath == "/" || routePath.startsWith("/home")) return AgeRoute.Home

        detailPattern.find(routePath)?.let { match ->
            return AgeRoute.Detail(match.groupValues[1].toLong())
        }

        playPattern.find(routePath)?.let { match ->
            return AgeRoute.Play(
                animeId = match.groupValues[1].toLong(),
                sourceIndex = match.groupValues[2].toInt(),
                episodeIndex = match.groupValues[3].toInt(),
            )
        }

        if (routePath.startsWith("/search")) {
            val query = routePath.substringAfter("query=", "").substringBefore("&").ifBlank { null }
            return AgeRoute.Search(query?.let(Uri::decode))
        }

        return null
    }

    private fun ensureLeadingSlash(path: String): String {
        return if (path.startsWith("/")) path else "/$path"
    }

    private fun isAllowedAgeHost(host: String?): Boolean {
        val normalized = host?.lowercase().orEmpty()
        if (normalized.isBlank()) return false
        return normalized == "m.agedm.io" ||
            normalized == "agedm.io" ||
            normalized.endsWith(".agedm.io")
    }
}

