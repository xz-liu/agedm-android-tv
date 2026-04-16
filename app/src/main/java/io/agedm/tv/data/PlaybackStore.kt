package io.agedm.tv.data

import android.content.Context
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
class PlaybackStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Synchronized
    fun getRecord(animeId: Long): PlaybackRecord? {
        return readAll()[animeId]
    }

    @Synchronized
    fun saveRecord(record: PlaybackRecord) {
        val records = readAll()
        records[record.animeId] = record
        writeAll(records)
    }

    @Synchronized
    fun getRecentRecords(limit: Int = 20): List<PlaybackRecord> {
        return readAll()
            .values
            .sortedByDescending { it.lastUpdatedEpochMs }
            .take(limit)
    }

    @Synchronized
    fun isAutoNextEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_NEXT, true)
    }

    @Synchronized
    fun setAutoNextEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_NEXT, enabled).apply()
    }

    private fun readAll(): MutableMap<Long, PlaybackRecord> {
        val raw = prefs.getString(KEY_RECORDS, null).orEmpty()
        if (raw.isBlank()) return mutableMapOf()
        return try {
            json.decodeFromString<List<PlaybackRecord>>(raw)
                .associateBy { it.animeId }
                .toMutableMap()
        } catch (_: Throwable) {
            mutableMapOf()
        }
    }

    private fun writeAll(records: Map<Long, PlaybackRecord>) {
        val payload = records.values.sortedByDescending { it.lastUpdatedEpochMs }
        prefs.edit().putString(KEY_RECORDS, json.encodeToString(payload)).apply()
    }

    companion object {
        private const val PREFS_NAME = "age_dm_tv"
        private const val KEY_RECORDS = "playback_records"
        private const val KEY_AUTO_NEXT = "auto_next"
    }
}
