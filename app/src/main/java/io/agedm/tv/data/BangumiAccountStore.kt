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
    private val database = AppStorageDatabase.get(context)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
    private var sessionCache: BangumiAccountSession? = null
    private var sessionLoaded = false
    private var collectionCache: MutableMap<Long, BangumiCollectionCacheEntry> = mutableMapOf()
    private var collectionLoaded = false
    private var subjectMatchCache: MutableMap<Long, Long> = mutableMapOf()
    private var subjectMatchesLoaded = false

    @Synchronized
    fun readSession(): BangumiAccountSession? {
        if (!sessionLoaded) {
            sessionCache = database.readBangumiSession() ?: migrateLegacySession()
            sessionLoaded = true
        }
        return sessionCache
    }

    @Synchronized
    fun saveSession(session: BangumiAccountSession) {
        sessionCache = session
        sessionLoaded = true
        database.saveBangumiSession(session)
        prefs.edit().remove(KEY_SESSION).apply()
    }

    @Synchronized
    fun clearSession() {
        sessionCache = null
        sessionLoaded = true
        collectionCache = mutableMapOf()
        collectionLoaded = true
        subjectMatchCache = mutableMapOf()
        subjectMatchesLoaded = true
        database.clearBangumiAccountData()
        prefs.edit().remove(KEY_SESSION).remove(KEY_COLLECTIONS).remove(KEY_SUBJECT_MATCHES).apply()
    }

    @Synchronized
    fun getCollectionStatus(animeId: Long): BangumiCollectionCacheEntry? {
        return readCollectionMap()[animeId]
    }

    @Synchronized
    fun saveCollectionStatus(animeId: Long, entry: BangumiCollectionCacheEntry) {
        val map = readCollectionMap()
        map[animeId] = entry
        collectionCache = map
        collectionLoaded = true
        database.upsertBangumiCollection(animeId, entry)
        prefs.edit().remove(KEY_COLLECTIONS).apply()
    }

    @Synchronized
    fun findAnimeIdBySubjectId(subjectId: Long): Long? {
        return readSubjectMatches()[subjectId]
    }

    @Synchronized
    fun saveSubjectMatch(subjectId: Long, animeId: Long) {
        val map = readSubjectMatches()
        map[subjectId] = animeId
        subjectMatchCache = map
        subjectMatchesLoaded = true
        database.upsertBangumiSubjectMatch(subjectId, animeId)
        prefs.edit().remove(KEY_SUBJECT_MATCHES).apply()
    }

    private fun readCollectionMap(): MutableMap<Long, BangumiCollectionCacheEntry> {
        if (!collectionLoaded) {
            collectionCache = database.loadBangumiCollections()
            if (collectionCache.isEmpty()) {
                collectionCache = migrateLegacyCollections()
            }
            collectionLoaded = true
        }
        return collectionCache
    }

    private fun readSubjectMatches(): MutableMap<Long, Long> {
        if (!subjectMatchesLoaded) {
            subjectMatchCache = database.loadBangumiSubjectMatches()
            if (subjectMatchCache.isEmpty()) {
                subjectMatchCache = migrateLegacySubjectMatches()
            }
            subjectMatchesLoaded = true
        }
        return subjectMatchCache
    }

    private fun migrateLegacySession(): BangumiAccountSession? {
        val raw = prefs.getString(KEY_SESSION, null).orEmpty()
        if (raw.isBlank()) return null
        val session = runCatching { json.decodeFromString<BangumiAccountSession>(raw) }.getOrNull() ?: return null
        database.saveBangumiSession(session)
        prefs.edit().remove(KEY_SESSION).apply()
        return session
    }

    private fun migrateLegacyCollections(): MutableMap<Long, BangumiCollectionCacheEntry> {
        val raw = prefs.getString(KEY_COLLECTIONS, null).orEmpty()
        if (raw.isBlank()) return mutableMapOf()
        val serializer = MapSerializer(Long.serializer(), BangumiCollectionCacheEntry.serializer())
        val map = runCatching { json.decodeFromString(serializer, raw).toMutableMap() }.getOrElse { mutableMapOf() }
        map.forEach { (animeId, entry) -> database.upsertBangumiCollection(animeId, entry) }
        prefs.edit().remove(KEY_COLLECTIONS).apply()
        return map
    }

    private fun migrateLegacySubjectMatches(): MutableMap<Long, Long> {
        val raw = prefs.getString(KEY_SUBJECT_MATCHES, null).orEmpty()
        if (raw.isBlank()) return mutableMapOf()
        val serializer = MapSerializer(Long.serializer(), Long.serializer())
        val map = runCatching { json.decodeFromString(serializer, raw).toMutableMap() }.getOrElse { mutableMapOf() }
        map.forEach(database::upsertBangumiSubjectMatch)
        prefs.edit().remove(KEY_SUBJECT_MATCHES).apply()
        return map
    }

    companion object {
        private const val PREFS_NAME = "age_dm_tv_bangumi"
        private const val KEY_SESSION = "session"
        private const val KEY_COLLECTIONS = "collections"
        private const val KEY_SUBJECT_MATCHES = "subject_matches"
    }
}
