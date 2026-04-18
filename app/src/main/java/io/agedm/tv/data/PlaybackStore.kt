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

    @Synchronized
    fun getPlaybackSpeed(): Float {
        return prefs.getFloat(KEY_PLAYBACK_SPEED, 1f)
            .takeIf { it > 0f }
            ?: 1f
    }

    @Synchronized
    fun setPlaybackSpeed(speed: Float) {
        prefs.edit().putFloat(KEY_PLAYBACK_SPEED, speed).apply()
    }

    @Synchronized
    fun getSourcePriority(): List<String> {
        val raw = prefs.getString(KEY_SOURCE_PRIORITY, null).orEmpty()
        val requested = raw.split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        val ordered = buildList {
            add(SOURCE_AGE)
            requested.filterTo(this) { it in SUPPORTED_SOURCES && it != SOURCE_AGE }
            DEFAULT_SOURCE_PRIORITY.filterTo(this) { it !in this }
        }
        return ordered.distinct()
    }

    @Synchronized
    fun setSourcePriority(order: List<String>) {
        val normalized = buildList {
            add(SOURCE_AGE)
            order.map { it.trim().lowercase() }
                .filterTo(this) { it in SUPPORTED_SOURCES && it != SOURCE_AGE }
            DEFAULT_SOURCE_PRIORITY.filterTo(this) { it !in this }
        }.distinct()
        prefs.edit().putString(KEY_SOURCE_PRIORITY, normalized.joinToString(",")).apply()
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
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_SOURCE_PRIORITY = "source_priority"

        const val SOURCE_AGE = "age"
        const val SOURCE_AAFUN = "aafun"
        const val SOURCE_DM84 = "dm84"

        val DEFAULT_SOURCE_PRIORITY = listOf(SOURCE_AGE, SOURCE_AAFUN, SOURCE_DM84)
        val SUPPORTED_SOURCES = DEFAULT_SOURCE_PRIORITY.toSet()
    }
}
