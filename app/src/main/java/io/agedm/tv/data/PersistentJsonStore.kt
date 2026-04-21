package io.agedm.tv.data

import java.io.File

class PersistentJsonStore(
    private val database: AppStorageDatabase,
    private val legacyDir: File? = null,
) {

    @Volatile
    private var legacyFullyMigrated = false

    @Synchronized
    fun read(key: String): String? {
        database.readMetadataEntry(key)?.let { return it }
        return migrateLegacyEntry(key)
    }

    @Synchronized
    fun write(key: String, value: String) {
        database.writeMetadataEntry(key, value)
        deleteLegacyFile(key)
    }

    @Synchronized
    fun remove(key: String) {
        database.removeMetadataEntry(key)
        deleteLegacyFile(key)
    }

    @Synchronized
    fun listKeys(prefix: String = ""): List<String> {
        migrateAllLegacyEntries()
        return database.listMetadataKeys(prefix)
    }

    private fun migrateLegacyEntry(key: String): String? {
        val file = fileFor(key)
        if (file == null || !file.exists()) return null
        val value = runCatching { file.readText() }.getOrNull() ?: return null
        database.writeMetadataEntry(key, value, file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis())
        file.delete()
        return value
    }

    private fun migrateAllLegacyEntries() {
        if (legacyFullyMigrated) return
        val files = legacyDir?.listFiles().orEmpty()
        files.forEach { file ->
            if (!file.isFile || !file.name.endsWith(".json")) return@forEach
            val key = file.name.removeSuffix(".json")
            val value = runCatching { file.readText() }.getOrNull() ?: return@forEach
            database.writeMetadataEntry(
                key = key,
                value = value,
                updatedAtMs = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
            file.delete()
        }
        legacyFullyMigrated = true
    }

    private fun deleteLegacyFile(key: String) {
        runCatching { fileFor(key)?.delete() }
    }

    private fun fileFor(key: String): File? {
        val dir = legacyDir ?: return null
        val safe = key.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(dir, "$safe.json")
    }
}
