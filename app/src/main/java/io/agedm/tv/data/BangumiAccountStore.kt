package io.agedm.tv.data

import android.content.Context
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
class BangumiAccountStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Synchronized
    fun readSession(): BangumiAccountSession? {
        val raw = prefs.getString(KEY_SESSION, null).orEmpty()
        if (raw.isBlank()) return null
        return runCatching { json.decodeFromString<BangumiAccountSession>(raw) }.getOrNull()
    }

    @Synchronized
    fun saveSession(session: BangumiAccountSession) {
        prefs.edit().putString(KEY_SESSION, json.encodeToString(session)).apply()
    }

    @Synchronized
    fun clearSession() {
        prefs.edit()
            .remove(KEY_SESSION)
            .remove(KEY_COLLECTIONS)
            .remove(KEY_SUBJECT_MATCHES)
            .apply()
    }

    @Synchronized
    fun getCollectionStatus(animeId: Long): BangumiCollectionCacheEntry? {
        return readCollectionMap()[animeId]
    }

    @Synchronized
    fun saveCollectionStatus(animeId: Long, entry: BangumiCollectionCacheEntry) {
        val map = readCollectionMap()
        map[animeId] = entry
        writeCollectionMap(map)
    }

    @Synchronized
    fun findAnimeIdBySubjectId(subjectId: Long): Long? {
        return readSubjectMatches()[subjectId]
    }

    @Synchronized
    fun saveSubjectMatch(subjectId: Long, animeId: Long) {
        val map = readSubjectMatches()
        map[subjectId] = animeId
        writeSubjectMatches(map)
    }

    private fun readCollectionMap(): MutableMap<Long, BangumiCollectionCacheEntry> {
        val raw = prefs.getString(KEY_COLLECTIONS, null).orEmpty()
        if (raw.isBlank()) return mutableMapOf()
        val serializer = MapSerializer(Long.serializer(), BangumiCollectionCacheEntry.serializer())
        return runCatching { json.decodeFromString(serializer, raw).toMutableMap() }.getOrElse { mutableMapOf() }
    }

    private fun writeCollectionMap(map: Map<Long, BangumiCollectionCacheEntry>) {
        val serializer = MapSerializer(Long.serializer(), BangumiCollectionCacheEntry.serializer())
        prefs.edit().putString(KEY_COLLECTIONS, json.encodeToString(serializer, map)).apply()
    }

    private fun readSubjectMatches(): MutableMap<Long, Long> {
        val raw = prefs.getString(KEY_SUBJECT_MATCHES, null).orEmpty()
        if (raw.isBlank()) return mutableMapOf()
        val serializer = MapSerializer(Long.serializer(), Long.serializer())
        return runCatching { json.decodeFromString(serializer, raw).toMutableMap() }.getOrElse { mutableMapOf() }
    }

    private fun writeSubjectMatches(map: Map<Long, Long>) {
        val serializer = MapSerializer(Long.serializer(), Long.serializer())
        prefs.edit().putString(KEY_SUBJECT_MATCHES, json.encodeToString(serializer, map)).apply()
    }

    companion object {
        private const val PREFS_NAME = "age_dm_tv_bangumi"
        private const val KEY_SESSION = "session"
        private const val KEY_COLLECTIONS = "collections"
        private const val KEY_SUBJECT_MATCHES = "subject_matches"
    }
}
