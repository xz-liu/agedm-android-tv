package io.agedm.tv.data

enum class DownloadStatus { WAITING, PREPARING, DOWNLOADING, PAUSED, FAILED, COMPLETED, REMOVING }

data class DownloadEntry(
    val id: String,
    val animeId: Long,
    val title: String,
    val cover: String,
    val sourceKey: String,
    val sourceLabel: String,
    val episodeIndex: Int,
    val episodeLabel: String,
    val status: DownloadStatus,
    val bytes: Long = 0,
    val percent: Float = -1f,
    val bytesPerSecond: Long = 0,
    val message: String = "",
)

data class DownloadShow(val animeId: Long, val title: String, val cover: String, val entries: List<DownloadEntry>) {
    val complete get() = entries.count { it.status == DownloadStatus.COMPLETED }
    val bytes get() = entries.sumOf { it.bytes }
    val speed get() = entries.sumOf { it.bytesPerSecond }
    val progress get() = (entries.sumOf {
        if (it.status == DownloadStatus.COMPLETED) 100.0 else it.percent.coerceAtLeast(0f).toDouble()
    } / entries.size.coerceAtLeast(1)).toInt()
}

fun groupDownloads(entries: List<DownloadEntry>): List<DownloadShow> = entries.groupBy { it.animeId }.map { (id, items) ->
    DownloadShow(id, items.first().title, items.firstOrNull { it.cover.isNotBlank() }?.cover.orEmpty(),
        items.sortedWith(compareBy<DownloadEntry> { it.episodeIndex }.thenBy { it.sourceKey }))
}

/** A short rolling window uses monotonic time and discards stale speed on pause/resume. */
class DownloadSpeedTracker {
    private data class Sample(val timeMs: Long, val bytes: Long)
    private val samples = mutableMapOf<String, ArrayDeque<Sample>>()

    fun sample(id: String, bytes: Long, active: Boolean, nowMs: Long): Long {
        if (!active) { samples.remove(id); return 0 }
        val history = samples.getOrPut(id) { ArrayDeque() }
        if (history.lastOrNull()?.let { bytes < it.bytes || nowMs < it.timeMs } == true) history.clear()
        history.addLast(Sample(nowMs, bytes))
        while (history.size > 2 && nowMs - history.first().timeMs > 4000) history.removeFirst()
        val baseline = history.first()
        val elapsed = nowMs - baseline.timeMs
        return if (elapsed <= 0) 0 else ((bytes - baseline.bytes).coerceAtLeast(0) * 1000 / elapsed)
    }

    fun retain(ids: Set<String>) { samples.keys.retainAll(ids) }
}

/** Selection belongs to a source; display order never changes the underlying episode identities. */
class EpisodeDownloadSelection {
    var sourceKey: String? = null
        private set
    private val selected = linkedSetOf<Int>()
    val indices: Set<Int> get() = selected.toSet()
    val active: Boolean get() = sourceKey != null
    fun begin(sourceKey: String, initialIndex: Int? = null) {
        this.sourceKey = sourceKey
        selected.clear()
        initialIndex?.let(selected::add)
    }
    fun toggle(index: Int) { if (!selected.add(index)) selected.remove(index) }
    fun selectAll(indices: List<Int>) { selected.addAll(indices) }
    fun clear() { selected.clear(); sourceKey = null }
    fun reconcile(sourceKey: String, available: Set<Int>) {
        if (this.sourceKey != sourceKey) clear() else selected.retainAll(available)
    }
}
