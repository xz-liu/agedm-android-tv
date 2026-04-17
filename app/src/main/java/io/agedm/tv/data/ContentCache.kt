package io.agedm.tv.data

import java.io.File

class ContentCache(private val dir: File) {

    fun get(key: String, ttlMs: Long): String? {
        val file = fileFor(key)
        if (!file.exists()) return null
        return try {
            val content = file.readText()
            val nl = content.indexOf('\n')
            if (nl < 0) { file.delete(); return null }
            val ts = content.substring(0, nl).toLongOrNull() ?: run { file.delete(); return null }
            if (System.currentTimeMillis() - ts > ttlMs) { file.delete(); return null }
            content.substring(nl + 1)
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    @Synchronized
    fun put(key: String, data: String) {
        try {
            dir.mkdirs()
            fileFor(key).writeText("${System.currentTimeMillis()}\n$data")
        } catch (_: Exception) {}
    }

    fun evictExpired(maxTtlMs: Long = MAX_AGE_MS) {
        try {
            dir.listFiles()?.forEach { file ->
                try {
                    val ts = file.bufferedReader().use { it.readLine() }?.toLongOrNull() ?: 0L
                    if (System.currentTimeMillis() - ts > maxTtlMs) file.delete()
                } catch (_: Exception) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }

    private fun fileFor(key: String): File {
        val safe = key.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(dir, "$safe.json")
    }

    companion object {
        val MAX_AGE_MS = 2 * 60 * 60 * 1000L
    }
}
