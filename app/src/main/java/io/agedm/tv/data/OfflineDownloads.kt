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
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

    fun offlineDataSource(): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(null)
        .setCacheWriteDataSinkFactory(null)

    suspend fun enqueue(detail: AnimeDetail, source: EpisodeSource, episode: EpisodeItem, stream: ResolvedStream) {
        require(StatFs(context.filesDir.path).availableBytes >= 256L * 1024 * 1024) {
            "剩余空间不足 256 MB，请先删除部分下载"
        }
        val metadata = OfflineEpisode(detail.animeId, detail.title, source.key, source.label,
            episode.index, episode.label, stream.headers)
        val id = MessageDigest.getInstance("SHA-256")
            .digest("${detail.animeId}:${source.key}:${episode.index}".toByteArray())
            .joinToString("") { "%02x".format(it) }
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

    fun pause(id: String) = DownloadService.sendSetStopReason(context, MediaDownloadService::class.java, id, 1, true)
    fun resume(id: String) = DownloadService.sendSetStopReason(context, MediaDownloadService::class.java, id, 0, true)
    fun retry(request: DownloadRequest) = DownloadService.sendAddDownload(context, MediaDownloadService::class.java, request, true)
    fun remove(id: String) = DownloadService.sendRemoveDownload(context, MediaDownloadService::class.java, id, true)

    private fun httpFactory(headers: Map<String, String>) = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setDefaultRequestProperties(headers)
}
