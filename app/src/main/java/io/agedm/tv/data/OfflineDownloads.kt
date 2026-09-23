package io.agedm.tv.data

import android.content.Context
import android.os.StatFs
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.DownloaderFactory
import io.agedm.tv.service.MediaDownloadService
import io.agedm.tv.service.DownloadPreparationService
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
data class OfflineEpisode(
    val animeId: Long,
    val title: String,
    val sourceKey: String,
    val sourceLabel: String,
    val episodeIndex: Int,
    val episodeLabel: String,
    val headers: Map<String, String>,
    val cover: String = "",
) {
    fun encode(): ByteArray = Json.encodeToString(this).toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(request: DownloadRequest): OfflineEpisode =
            Json.decodeFromString(request.data.toString(Charsets.UTF_8))
    }
}

/** One cache and manager per process. Download bytes are never evicted as content metadata. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OfflineDownloads(private val context: Context) {
    val queue = DownloadQueue(File(context.filesDir, "download_queue.json"))
    private val database = StandaloneDatabaseProvider(context)
    val cache = SimpleCache(File(context.filesDir, "offline_media"), NoOpCacheEvictor(), database)
    private val index = DefaultDownloadIndex(database)
    private val executor = Executor { it.run() }
    val manager = DownloadManager(context, index, DownloaderFactory { request ->
        val metadata = OfflineEpisode.decode(request)
        DefaultDownloaderFactory(
            CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory(httpFactory(metadata.headers)),
            executor,
        ).createDownloader(request)
    }).apply { maxParallelDownloads = 1 }

    fun all(): List<Download> = index.getDownloads().use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.download) }
    }

    fun get(id: String): Download? = index.getDownload(id)

    /** Media lookup uses the same source/episode identity as PlaybackStore. */
    suspend fun completed(animeId: Long, sourceKey: String, episodeIndex: Int): Download? =
        withContext(Dispatchers.IO) {
            get(downloadId(animeId, sourceKey, episodeIndex))?.takeIf { it.state == Download.STATE_COMPLETED }
        }

    private fun detailFile(animeId: Long) = android.util.AtomicFile(File(context.filesDir, "download_details/$animeId.json"))

    suspend fun playbackDetail(animeId: Long, cached: AnimeDetail?): AnimeDetail? = withContext(Dispatchers.IO) {
        val saved = runCatching { detailFile(animeId).openRead().use {
            Json { ignoreUnknownKeys = true }.decodeFromString<AnimeDetail>(it.readBytes().toString(Charsets.UTF_8))
        } }.getOrNull()
        val downloaded = all().filter { it.state == Download.STATE_COMPLETED }.mapNotNull {
            runCatching { OfflineEpisode.decode(it.request) }.getOrNull()
        }.filter { it.animeId == animeId }
        mergeDownloadedDetail(cached ?: saved, downloaded, queue.all().filter { it.animeId == animeId })
    }

    private fun savePlaybackDetail(detail: AnimeDetail) {
        val file = detailFile(detail.animeId)
        val stream = file.startWrite()
        try {
            stream.write(Json.encodeToString(detail).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) { file.failWrite(stream); throw error }
    }

    suspend fun snapshot(): List<DownloadEntry> {
        val downloads = withContext(Dispatchers.IO) { all() }.associateByTo(linkedMapOf()) { it.request.id }
        manager.currentDownloads.forEach { downloads[it.request.id] = it }
        val queued = queue.all().associateBy { it.id }
        val entries = downloads.values.mapNotNull { download ->
            val metadata = runCatching { OfflineEpisode.decode(download.request) }.getOrNull() ?: return@mapNotNull null
            val status = when (download.state) {
                Download.STATE_COMPLETED -> DownloadStatus.COMPLETED
                Download.STATE_DOWNLOADING -> DownloadStatus.DOWNLOADING
                Download.STATE_STOPPED -> DownloadStatus.PAUSED
                Download.STATE_FAILED -> DownloadStatus.FAILED
                Download.STATE_REMOVING -> DownloadStatus.REMOVING
                else -> DownloadStatus.WAITING
            }
            DownloadEntry(download.request.id, metadata.animeId, metadata.title,
                metadata.cover.ifBlank { queued[download.request.id]?.cover.orEmpty() }, metadata.sourceKey,
                metadata.sourceLabel, metadata.episodeIndex, metadata.episodeLabel, status,
                download.bytesDownloaded, download.percentDownloaded,
                message = if (status == DownloadStatus.WAITING && manager.notMetRequirements != 0) "等待网络" else "")
        }
        return entries + queued.values.filter { it.id !in downloads && it.state != PreparationState.TRANSFERRED }.map { task ->
            val status = when (task.state) {
                PreparationState.PREPARING -> DownloadStatus.PREPARING
                PreparationState.PAUSED -> DownloadStatus.PAUSED
                PreparationState.FAILED -> DownloadStatus.FAILED
                else -> DownloadStatus.WAITING
            }
            DownloadEntry(task.id, task.animeId, task.title, task.cover, task.sourceKey, task.sourceLabel,
                task.episodeIndex, task.episodeLabel, status, message = task.error)
        }
    }

    fun offlineDataSource(): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(null)
        .setCacheWriteDataSinkFactory(null)

    suspend fun enqueue(detail: AnimeDetail, source: EpisodeSource, episode: EpisodeItem, stream: ResolvedStream) {
        require(StatFs(context.filesDir.path).availableBytes >= 256L * 1024 * 1024) {
            "剩余空间不足 256 MB，请先删除部分下载"
        }
        val metadata = OfflineEpisode(detail.animeId, detail.title, source.key, source.label,
            episode.index, episode.label, stream.headers, detail.cover)
        val id = downloadId(detail.animeId, source.key, episode.index)
        val existing = get(id)
        if (existing != null) {
            throw IOException("该集已在下载列表中。可继续下载，或删除失败记录后重新下载")
        }
        val mediaItem = MediaItem.Builder().setUri(stream.streamUrl)
            .setMimeType(stream.mimeType ?: if (stream.isM3u8) MimeTypes.APPLICATION_M3U8 else null).build()
        // Select a playable rendition instead of downloading every HLS quality.
        val helper = DownloadHelper.forMediaItem(context, mediaItem,
            DefaultRenderersFactory(context), httpFactory(stream.headers))
        val request = try {
            withTimeout(30_000L) {
                suspendCancellableCoroutine<DownloadRequest> { continuation ->
                    helper.prepare(object : DownloadHelper.Callback {
                        override fun onPrepared(helper: DownloadHelper) {
                            if (continuation.isActive) {
                                try {
                                    continuation.resume(helper.getDownloadRequest(id, metadata.encode()))
                                } catch (error: Exception) {
                                    continuation.resumeWithException(error)
                                }
                            }
                        }
                        override fun onPrepareError(helper: DownloadHelper, error: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    })
                }
            }
        } finally { helper.release() }
        DownloadService.sendResumeDownloads(context, MediaDownloadService::class.java, false)
        DownloadService.sendAddDownload(context, MediaDownloadService::class.java, request, true)
    }

    fun queueEpisodes(detail: AnimeDetail, source: EpisodeSource, episodes: List<EpisodeItem>): Int {
        require(StatFs(context.filesDir.path).availableBytes >= 256L * 1024 * 1024) { "剩余空间不足，请先清理下载" }
        savePlaybackDetail(detail)
        val added = queue.add(episodes.sortedBy { it.index }.map { QueuedEpisode.from(detail, source, it) },
            all().mapTo(mutableSetOf()) { it.request.id })
        if (added > 0) DownloadPreparationService.wake(context)
        return added
    }

    fun resumeQueue() { if (queue.all().any { it.state == PreparationState.WAITING }) DownloadPreparationService.wake(context) }

    fun pause(ids: Set<String>) {
        val pending = queue.all().filter { it.id in ids && it.state != PreparationState.TRANSFERRED }.mapTo(mutableSetOf()) { it.id }
        if (pending.isNotEmpty()) queue.update(pending, PreparationState.PAUSED)
        ids.forEach { DownloadService.sendSetStopReason(context, MediaDownloadService::class.java, it, 1, false) }
        DownloadPreparationService.syncIfRunning(context)
    }

    fun resume(ids: Set<String>) {
        val tasks = queue.all().associateBy { it.id }
        val pending = mutableSetOf<String>()
        ids.forEach { id ->
            val downloaded = get(id)
            val task = tasks[id]
            if (task != null && (downloaded == null || downloaded.state == Download.STATE_FAILED)) {
                if (downloaded != null) DownloadService.sendRemoveDownload(context, MediaDownloadService::class.java, id, false)
                pending.add(id)
            } else if (downloaded != null) {
                if (downloaded.state == Download.STATE_FAILED) {
                    DownloadService.sendAddDownload(context, MediaDownloadService::class.java, downloaded.request, true)
                } else {
                    DownloadService.sendSetStopReason(context, MediaDownloadService::class.java, id, 0, true)
                }
            }
        }
        if (pending.isNotEmpty()) queue.update(pending, PreparationState.WAITING)
        resumeQueue()
    }

    fun remove(ids: Set<String>) {
        queue.remove(ids)
        ids.forEach { DownloadService.sendRemoveDownload(context, MediaDownloadService::class.java, it, false) }
        DownloadPreparationService.syncIfRunning(context)
    }

    private fun httpFactory(headers: Map<String, String>) = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setDefaultRequestProperties(headers)
}
