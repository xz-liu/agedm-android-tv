package io.agedm.tv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
class AppStorageDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    override fun onCreate(db: SQLiteDatabase) {
        createCoreTables(db)
        createCacheTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createCoreTables(db)
            createCacheTables(db)
        }
    }

    fun loadPlaybackRecords(): MutableMap<Long, PlaybackRecord> {
        val db = readableDatabase
        val cursor = db.query(
            "playback_records",
            null,
            null,
            null,
            null,
            null,
            "last_updated_epoch_ms DESC",
        )
        return cursor.use {
            buildMap {
                while (it.moveToNext()) {
                    val record = PlaybackRecord(
                        animeId = it.getLong(it.getColumnIndexOrThrow("anime_id")),
                        animeTitle = it.getString(it.getColumnIndexOrThrow("anime_title")),
                        detailUrl = it.getString(it.getColumnIndexOrThrow("detail_url")),
                        sourceKey = it.getString(it.getColumnIndexOrThrow("source_key")),
                        sourceLabel = it.getString(it.getColumnIndexOrThrow("source_label")),
                        episodeIndex = it.getInt(it.getColumnIndexOrThrow("episode_index")),
                        episodeLabel = it.getString(it.getColumnIndexOrThrow("episode_label")),
                        positionMs = it.getLong(it.getColumnIndexOrThrow("position_ms")),
                        durationMs = it.getLong(it.getColumnIndexOrThrow("duration_ms")),
                        lastUpdatedEpochMs = it.getLong(it.getColumnIndexOrThrow("last_updated_epoch_ms")),
                        completed = it.getInt(it.getColumnIndexOrThrow("completed")) != 0,
                    )
                    put(record.animeId, record)
                }
            }.toMutableMap()
        }
    }

    fun upsertPlaybackRecord(record: PlaybackRecord) {
        writableDatabase.insertWithOnConflict(
            "playback_records",
            null,
            ContentValues().apply {
                put("anime_id", record.animeId)
                put("anime_title", record.animeTitle)
                put("detail_url", record.detailUrl)
                put("source_key", record.sourceKey)
                put("source_label", record.sourceLabel)
                put("episode_index", record.episodeIndex)
                put("episode_label", record.episodeLabel)
                put("position_ms", record.positionMs)
                put("duration_ms", record.durationMs)
                put("last_updated_epoch_ms", record.lastUpdatedEpochMs)
                put("completed", if (record.completed) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun deletePlaybackRecord(animeId: Long) {
        writableDatabase.delete("playback_records", "anime_id = ?", arrayOf(animeId.toString()))
    }

    fun clearPlaybackRecords() {
        writableDatabase.delete("playback_records", null, null)
    }

    fun readBangumiSession(): BangumiAccountSession? {
        val cursor = readableDatabase.query(
            "bangumi_session",
            null,
            "singleton_id = 1",
            null,
            null,
            null,
            null,
            "1",
        )
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            BangumiAccountSession(
                username = it.getString(it.getColumnIndexOrThrow("username")),
                displayName = it.getString(it.getColumnIndexOrThrow("display_name")),
                avatarUrl = it.getString(it.getColumnIndexOrThrow("avatar_url")),
                cookies = runCatching {
                    json.decodeFromString<Map<String, String>>(it.getString(it.getColumnIndexOrThrow("cookies_json")))
                }.getOrDefault(emptyMap()),
                lastValidatedMs = it.getLong(it.getColumnIndexOrThrow("last_validated_ms")),
            )
        }
    }

    fun saveBangumiSession(session: BangumiAccountSession) {
        writableDatabase.insertWithOnConflict(
            "bangumi_session",
            null,
            ContentValues().apply {
                put("singleton_id", 1)
                put("username", session.username)
                put("display_name", session.displayName)
                put("avatar_url", session.avatarUrl)
                put("cookies_json", json.encodeToString(session.cookies))
                put("last_validated_ms", session.lastValidatedMs)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun clearBangumiAccountData() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("bangumi_session", null, null)
            writableDatabase.delete("bangumi_collection_cache", null, null)
            writableDatabase.delete("bangumi_subject_matches", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun loadBangumiCollections(): MutableMap<Long, BangumiCollectionCacheEntry> {
        val cursor = readableDatabase.query(
            "bangumi_collection_cache",
            null,
            null,
            null,
            null,
            null,
            null,
        )
        return cursor.use {
            buildMap {
                while (it.moveToNext()) {
                    put(
                        it.getLong(it.getColumnIndexOrThrow("anime_id")),
                        BangumiCollectionCacheEntry(
                            subjectId = it.getLong(it.getColumnIndexOrThrow("subject_id")),
                            status = it.getString(it.getColumnIndexOrThrow("status")),
                            updatedAtMs = it.getLong(it.getColumnIndexOrThrow("updated_at_ms")),
                        ),
                    )
                }
            }.toMutableMap()
        }
    }

    fun upsertBangumiCollection(animeId: Long, entry: BangumiCollectionCacheEntry) {
        writableDatabase.insertWithOnConflict(
            "bangumi_collection_cache",
            null,
            ContentValues().apply {
                put("anime_id", animeId)
                put("subject_id", entry.subjectId)
                put("status", entry.status)
                put("updated_at_ms", entry.updatedAtMs)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun loadBangumiSubjectMatches(): MutableMap<Long, Long> {
        val cursor = readableDatabase.query(
            "bangumi_subject_matches",
            null,
            null,
            null,
            null,
            null,
            null,
        )
        return cursor.use {
            buildMap {
                while (it.moveToNext()) {
                    put(
                        it.getLong(it.getColumnIndexOrThrow("subject_id")),
                        it.getLong(it.getColumnIndexOrThrow("anime_id")),
                    )
                }
            }.toMutableMap()
        }
    }

    fun upsertBangumiSubjectMatch(subjectId: Long, animeId: Long) {
        writableDatabase.insertWithOnConflict(
            "bangumi_subject_matches",
            null,
            ContentValues().apply {
                put("subject_id", subjectId)
                put("anime_id", animeId)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun peekKvEntry(key: String): String? {
        val cursor = readableDatabase.query(
            "kv_entries",
            arrayOf("value"),
            "entry_key = ?",
            arrayOf(key),
            null,
            null,
            null,
            "1",
        )
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            it.getString(it.getColumnIndexOrThrow("value"))
        }
    }

    fun getKvEntry(key: String, ttlMs: Long): String? {
        val cursor = readableDatabase.query(
            "kv_entries",
            arrayOf("value", "write_ts"),
            "entry_key = ?",
            arrayOf(key),
            null,
            null,
            null,
            "1",
        )
        val result = cursor.use {
            if (!it.moveToFirst()) return@use null
            val writeTs = it.getLong(it.getColumnIndexOrThrow("write_ts"))
            val value = it.getString(it.getColumnIndexOrThrow("value"))
            writeTs to value
        } ?: return null
        val now = System.currentTimeMillis()
        if (now - result.first > ttlMs) {
            removeKvEntry(key)
            return null
        }
        writableDatabase.update(
            "kv_entries",
            ContentValues().apply { put("access_ts", now) },
            "entry_key = ?",
            arrayOf(key),
        )
        return result.second
    }

    fun putKvEntry(
        key: String,
        value: String,
        writeTs: Long = System.currentTimeMillis(),
        accessTs: Long = writeTs,
    ) {
        writableDatabase.insertWithOnConflict(
            "kv_entries",
            null,
            ContentValues().apply {
                put("entry_key", key)
                put("value", value)
                put("write_ts", writeTs)
                put("access_ts", accessTs)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun touchKvEntry(key: String) {
        writableDatabase.update(
            "kv_entries",
            ContentValues().apply { put("access_ts", System.currentTimeMillis()) },
            "entry_key = ?",
            arrayOf(key),
        )
    }

    fun removeKvEntry(key: String) {
        writableDatabase.delete("kv_entries", "entry_key = ?", arrayOf(key))
    }

    fun clearKvEntries() {
        writableDatabase.delete("kv_entries", null, null)
    }

    fun kvEntriesSizeBytes(): Long {
        val cursor = readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(LENGTH(value)), 0) FROM kv_entries",
            null,
        )
        return cursor.use {
            if (!it.moveToFirst()) return@use 0L
            it.getLong(0)
        }
    }

    fun evictKvEntriesByAccess(maxAge: Long) {
        val cutoff = System.currentTimeMillis() - maxAge
        writableDatabase.delete(
            "kv_entries",
            "access_ts < ?",
            arrayOf(cutoff.toString()),
        )
    }

    fun readMetadataEntry(key: String): String? {
        val cursor = readableDatabase.query(
            "metadata_entries",
            arrayOf("value"),
            "entry_key = ?",
            arrayOf(key),
            null,
            null,
            null,
            "1",
        )
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            it.getString(it.getColumnIndexOrThrow("value"))
        }
    }

    fun writeMetadataEntry(
        key: String,
        value: String,
        updatedAtMs: Long = System.currentTimeMillis(),
    ) {
        writableDatabase.insertWithOnConflict(
            "metadata_entries",
            null,
            ContentValues().apply {
                put("entry_key", key)
                put("value", value)
                put("updated_at_ms", updatedAtMs)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun removeMetadataEntry(key: String) {
        writableDatabase.delete("metadata_entries", "entry_key = ?", arrayOf(key))
    }

    fun listMetadataKeys(prefix: String = ""): List<String> {
        val selection = if (prefix.isBlank()) null else "entry_key LIKE ?"
        val args = if (prefix.isBlank()) null else arrayOf("$prefix%")
        val cursor = readableDatabase.query(
            "metadata_entries",
            arrayOf("entry_key"),
            selection,
            args,
            null,
            null,
            "entry_key ASC",
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(it.getString(it.getColumnIndexOrThrow("entry_key")))
                }
            }
        }
    }

    private fun createCoreTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playback_records (
                anime_id INTEGER PRIMARY KEY,
                anime_title TEXT NOT NULL,
                detail_url TEXT NOT NULL,
                source_key TEXT NOT NULL,
                source_label TEXT NOT NULL,
                episode_index INTEGER NOT NULL,
                episode_label TEXT NOT NULL,
                position_ms INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                last_updated_epoch_ms INTEGER NOT NULL,
                completed INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bangumi_session (
                singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
                username TEXT NOT NULL,
                display_name TEXT NOT NULL,
                avatar_url TEXT NOT NULL,
                cookies_json TEXT NOT NULL,
                last_validated_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bangumi_collection_cache (
                anime_id INTEGER PRIMARY KEY,
                subject_id INTEGER NOT NULL,
                status TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bangumi_subject_matches (
                subject_id INTEGER PRIMARY KEY,
                anime_id INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_playback_records_updated ON playback_records(last_updated_epoch_ms DESC)",
        )
    }

    private fun createCacheTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS kv_entries (
                entry_key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                write_ts INTEGER NOT NULL,
                access_ts INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS metadata_entries (
                entry_key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_kv_entries_access ON kv_entries(access_ts ASC)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_metadata_entries_key ON metadata_entries(entry_key ASC)",
        )
    }

    companion object {
        private const val DB_NAME = "age_tv_storage.db"
        private const val DB_VERSION = 2

        @Volatile
        private var instance: AppStorageDatabase? = null

        fun get(context: Context): AppStorageDatabase {
            return instance ?: synchronized(this) {
                instance ?: AppStorageDatabase(context.applicationContext).also { instance = it }
            }
        }
    }
}
