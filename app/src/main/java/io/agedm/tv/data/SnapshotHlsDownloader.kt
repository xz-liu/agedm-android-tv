package io.agedm.tv.data

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.offline.SegmentDownloader
import java.io.IOException
import java.util.concurrent.Executor

/** Download exactly the manifest snapshot cached at the playback entry URI.
 * Media3 1.4.1 HlsDownloader reloads a direct media playlist at its redirected base URI;
 * hosts that rotate signed segment URLs on every response then cache two incompatible snapshots.
 * Transfer, retry, cancellation and byte-range caching remain managed by SegmentDownloader. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SnapshotHlsDownloader(
    item: MediaItem,
    cacheFactory: CacheDataSource.Factory,
    executor: Executor,
) : SegmentDownloader<HlsPlaylist>(item, HlsPlaylistParser(), cacheFactory, executor, DEFAULT_MAX_MERGED_SEGMENT_START_TIME_DIFF_MS) {
    private val entryUri = item.localConfiguration!!.uri
    private val downloadCache = requireNotNull(cacheFactory.cache)

    override fun getSegments(dataSource: DataSource, manifest: HlsPlaylist, removing: Boolean): List<Segment> {
        val result = mutableListOf<Segment>()
        val keys = mutableSetOf<Uri>()
        fun addMedia(playlist: HlsMediaPlaylist) {
            fun add(segment: HlsMediaPlaylist.Segment) {
                val time = playlist.startTimeUs + segment.relativeStartTimeUs
                segment.fullSegmentEncryptionKeyUri?.let {
                    val uri = UriUtil.resolveToUri(playlist.baseUri, it)
                    if (keys.add(uri)) result += Segment(time, getCompressibleDataSpec(uri))
                }
                result += Segment(time, DataSpec.Builder()
                    .setUri(UriUtil.resolveToUri(playlist.baseUri, segment.url))
                    .setPosition(segment.byteRangeOffset).setLength(segment.byteRangeLength).build())
            }
            var previousInit: HlsMediaPlaylist.Segment? = null
            playlist.segments.forEach { segment ->
                segment.initializationSegment?.let { init ->
                    if (init !== previousInit) { add(init); previousInit = init }
                }
                add(segment)
            }
        }
        if (manifest is HlsMediaPlaylist) {
            result += Segment(0, getCompressibleDataSpec(entryUri))
            addMedia(manifest)
            // Also clean the second snapshot left by pre-fix downloads. Removal uses Media3's
            // cache-only source, so an expired redirected URL cannot initiate network traffic.
            if (removing) {
                val redirected = ContentMetadata.getRedirectedUri(downloadCache.getContentMetadata(entryUri.toString()))
                if (redirected != null && redirected != entryUri) {
                    val spec = getCompressibleDataSpec(redirected)
                    result += Segment(0, spec)
                    try {
                        (getManifest(dataSource, spec, true) as? HlsMediaPlaylist)?.let(::addMedia)
                    } catch (_: IOException) { /* A missing legacy snapshot must not block removal. */ }
                }
            }
        } else if (manifest is HlsMultivariantPlaylist) {
            manifest.mediaPlaylistUrls.forEach { uri ->
                val spec = getCompressibleDataSpec(uri)
                result += Segment(0, spec)
                try {
                    addMedia(getManifest(dataSource, spec, removing) as HlsMediaPlaylist)
                } catch (error: IOException) {
                    if (!removing) throw error
                }
            }
        }
        return result
    }
}
