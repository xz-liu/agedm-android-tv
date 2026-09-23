package io.agedm.tv.data

import android.content.Context
import androidx.media3.exoplayer.offline.Download

class DownloadSettings(context: Context) {
    private val prefs = context.getSharedPreferences("download_settings", Context.MODE_PRIVATE)

    var parallelDownloads: Int
        get() = prefs.getInt("parallel_downloads", DEFAULT_PARALLEL_DOWNLOADS)
            .takeIf { it in MIN_PARALLEL_DOWNLOADS..MAX_PARALLEL_DOWNLOADS } ?: DEFAULT_PARALLEL_DOWNLOADS
        set(value) {
            require(value in MIN_PARALLEL_DOWNLOADS..MAX_PARALLEL_DOWNLOADS)
            prefs.edit().putInt("parallel_downloads", value).apply()
        }

    companion object {
        const val DEFAULT_PARALLEL_DOWNLOADS = 4
        const val MIN_PARALLEL_DOWNLOADS = 1
        const val MAX_PARALLEL_DOWNLOADS = 8
    }
}

/** Queued/restarting transfers also reserve a slot; paused, failed and removed ones do not. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun availableDownloadSlots(states: Iterable<Int>, limit: Int): Int = (limit - states.count {
    it == Download.STATE_DOWNLOADING || it == Download.STATE_QUEUED || it == Download.STATE_RESTARTING
}).coerceAtLeast(0)
