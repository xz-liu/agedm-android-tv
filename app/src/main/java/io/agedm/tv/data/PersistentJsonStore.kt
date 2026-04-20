package io.agedm.tv.data

import java.io.File

class PersistentJsonStore(private val dir: File) {

    @Synchronized
    fun read(key: String): String? {
        val file = fileFor(key)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    @Synchronized
    fun write(key: String, value: String) {
        runCatching {
            dir.mkdirs()
            fileFor(key).writeText(value)
        }
    }

    @Synchronized
    fun remove(key: String) {
        runCatching { fileFor(key).delete() }
    }

    @Synchronized
    fun listKeys(prefix: String = ""): List<String> {
        val files = dir.listFiles().orEmpty()
        return files.asSequence()
            .map { it.name }
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .filter { it.startsWith(prefix) }
            .sorted()
            .toList()
    }

    private fun fileFor(key: String): File {
        val safe = key.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(dir, "$safe.json")
    }
}
