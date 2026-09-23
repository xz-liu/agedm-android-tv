package io.agedm.tv.data

import java.net.URI

/** Content signatures take precedence over misleading CDN extensions and Content-Type headers. */
internal fun detectMediaMimeType(url: String, contentType: String? = null, prefix: ByteArray = byteArrayOf()): String? {
    val text = prefix.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
    if (text.startsWith("#EXTM3U")) return "application/x-mpegURL"
    if (Regex("<(?:[A-Za-z]+:)?MPD(?:\\s|>)").containsMatchIn(text)) return "application/dash+xml"
    if (text.contains("<SmoothStreamingMedia")) return "application/vnd.ms-sstr+xml"
    val type = contentType?.substringBefore(';')?.trim()?.lowercase()
    if (type in setOf("application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl")) return "application/x-mpegURL"
    if (type == "application/dash+xml" || type == "application/vnd.ms-sstr+xml") return type
    val path = runCatching { URI(url).path.lowercase() }.getOrDefault("")
    return when {
        path.endsWith(".m3u8") -> "application/x-mpegURL"
        path.endsWith(".mpd") -> "application/dash+xml"
        path.endsWith(".ism/manifest") || path.endsWith(".isml/manifest") -> "application/vnd.ms-sstr+xml"
        path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
        path.endsWith(".mkv") -> "video/x-matroska"
        path.endsWith(".webm") -> "video/webm"
        path.endsWith(".flv") -> "video/x-flv"
        else -> null
    }
}

internal fun isMediaResourceUrl(url: String): Boolean {
    val path = runCatching { URI(url).path.lowercase() }.getOrDefault("")
    if (path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".aac")) return false
    return detectMediaMimeType(url) != null
}
