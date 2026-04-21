package io.agedm.tv.data

import java.io.File

/**
 * Disk KV cache with two independent timestamps per entry:
 *   WRITE_TS  — when the data was fetched from the network; controls freshness (TTL).
 *   ACCESS_TS — when the entry was last read or explicitly touched; controls eviction.
 *
 * Entries are stored in SQLite; legacy file-based cache files are migrated lazily.
 */
class ContentCache(
    private val database: AppStorageDatabase,
    private val legacyDir: File? = null,
) {

    fun peek(key: String): String? {
        database.peekKvEntry(key)?.let { return it }
        return migrateLegacyEntry(key)?.data
    }

    @Synchronized
    fun get(key: String, ttlMs: Long): String? {
        database.getKvEntry(key, ttlMs)?.let { return it }
        val legacy = migrateLegacyEntry(key) ?: return null
        val now = System.currentTimeMillis()
        if (now - legacy.writeTs > ttlMs) {
            database.removeKvEntry(key)
            return null
        }
        database.putKvEntry(key, legacy.data, legacy.writeTs, now)
        return legacy.data
    }

    @Synchronized
    fun put(key: String, data: String) {
        database.putKvEntry(key, data)
        deleteLegacyFile(key)
    }

    @Synchronized
    fun touch(key: String) {
        if (database.peekKvEntry(key) != null) {
            database.touchKvEntry(key)
            return
        }
        val legacy = migrateLegacyEntry(key) ?: return
        database.putKvEntry(
            key = key,
            value = legacy.data,
            writeTs = legacy.writeTs,
            accessTs = System.currentTimeMillis(),
        )
    }

    @Synchronized
    fun clearAll() {
        database.clearKvEntries()
        try {
            legacyDir?.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    fun sizeBytes(): Long {
        val legacyBytes = try {
            legacyDir?.listFiles()?.sumOf { it.length() } ?: 0L
        } catch (_: Exception) {
            0L
        }
        return database.kvEntriesSizeBytes() + legacyBytes
    }

    fun evictExpired(maxAge: Long = MAX_AGE_MS) {
        database.evictKvEntriesByAccess(maxAge)
        try {
            legacyDir?.listFiles()?.forEach { file ->
                try {
                    val header = file.bufferedReader().use { it.readLine() } ?: ""
                    val (_, accessTs) = parseHeader(header) ?: Pair(0L, 0L)
                    if (System.currentTimeMillis() - accessTs > maxAge) file.delete()
                } catch (_: Exception) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun migrateLegacyEntry(key: String): LegacyEntry? {
        val file = fileFor(key)
        if (file == null || !file.exists()) return null
        val entry = try {
            val text = file.readText()
            val nl = text.indexOf('\n')
            if (nl < 0) {
                file.delete()
                return null
            }
            val header = parseHeader(text.substring(0, nl)) ?: run {
                file.delete()
                return null
            }
            LegacyEntry(
                writeTs = header.first,
                accessTs = header.second,
                data = text.substring(nl + 1),
            )
        } catch (_: Exception) {
            file.delete()
            return null
        }
        database.putKvEntry(key, entry.data, entry.writeTs, entry.accessTs)
        file.delete()
        return entry
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

    private fun deleteLegacyFile(key: String) {
        runCatching { fileFor(key)?.delete() }
    }

    private fun fileFor(key: String): File? {
        val dir = legacyDir ?: return null
        val safe = key.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(dir, "$safe.json")
    }

    private data class LegacyEntry(
        val writeTs: Long,
        val accessTs: Long,
        val data: String,
    )

    companion object {
        const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000L
    }
}
