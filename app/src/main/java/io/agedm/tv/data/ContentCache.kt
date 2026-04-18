package io.agedm.tv.data

import java.io.File

/**
 * Disk KV cache with two independent timestamps per entry:
 *   WRITE_TS  — when the data was fetched from the network; controls freshness (TTL).
 *   ACCESS_TS — when the entry was last read or explicitly touched; controls eviction.
 *
 * File format (first line, then data):
 *   "<WRITE_TS>\t<ACCESS_TS>\n<data>"
 *
 * Legacy files written with the old single-timestamp format ("<TS>\n<data>") are read
 * as WRITE_TS == ACCESS_TS == TS for backward compatibility.
 */
class ContentCache(private val dir: File) {

    @Synchronized
    fun get(key: String, ttlMs: Long): String? {
        val file = fileFor(key)
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            val nl = text.indexOf('\n')
            if (nl < 0) { file.delete(); return null }
            val (writeTs, _) = parseHeader(text.substring(0, nl))
                ?: run { file.delete(); return null }
            val now = System.currentTimeMillis()
            if (now - writeTs > ttlMs) { file.delete(); return null }
            val data = text.substring(nl + 1)
            writeFile(file, writeTs, now, data)
            data
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    @Synchronized
    fun put(key: String, data: String) {
        try {
            dir.mkdirs()
            val now = System.currentTimeMillis()
            writeFile(fileFor(key), now, now, data)
        } catch (_: Exception) {}
    }

    @Synchronized
    fun touch(key: String) {
        val file = fileFor(key)
        if (!file.exists()) return
        try {
            val text = file.readText()
            val nl = text.indexOf('\n')
            if (nl < 0) return
            val (writeTs, _) = parseHeader(text.substring(0, nl)) ?: return
            writeFile(file, writeTs, System.currentTimeMillis(), text.substring(nl + 1))
        } catch (_: Exception) {}
    }

    fun evictExpired(maxAge: Long = MAX_AGE_MS) {
        try {
            dir.listFiles()?.forEach { file ->
                try {
                    val header = file.bufferedReader().use { it.readLine() } ?: ""
                    val (_, accessTs) = parseHeader(header) ?: Pair(0L, 0L)
                    if (System.currentTimeMillis() - accessTs > maxAge) file.delete()
                } catch (_: Exception) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }

    private fun writeFile(file: File, writeTs: Long, accessTs: Long, data: String) {
        dir.mkdirs()
        file.writeText("$writeTs\t$accessTs\n$data")
    }

    private fun parseHeader(header: String): Pair<Long, Long>? {
        val tab = header.indexOf('\t')
        return if (tab < 0) {
            val ts = header.toLongOrNull() ?: return null
            Pair(ts, ts)
        } else {
            val writeTs = header.substring(0, tab).toLongOrNull() ?: return null
            val accessTs = header.substring(tab + 1).toLongOrNull() ?: return null
            Pair(writeTs, accessTs)
        }
    }

    private fun fileFor(key: String): File {
        val safe = key.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(dir, "$safe.json")
    }

    companion object {
        const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000L
    }
}
