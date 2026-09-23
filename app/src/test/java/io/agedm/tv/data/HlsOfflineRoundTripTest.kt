package io.agedm.tv.data

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class HlsOfflineRoundTripTest {
    @Test fun selectedVariantRetainsEncryptionKeyInitializationAndByteRanges() {
        val store = OfflineDownloads(RuntimeEnvironment.getApplication())
        val server = MockWebServer()
        val key = ByteArray(16) { it.toByte() }
        val media = ByteArray(8) { (it + 20).toByte() }
        val unexpected = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/master.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nskip.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000\nselected.m3u8\n")
                "/selected.m3u8" -> MockResponse().setResponseCode(302).setHeader("Location", "/quality/list")
                "/quality/list" -> MockResponse().setBody("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:2\n" +
                    "#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x00000000000000000000000000000001\n" +
                    "#EXT-X-MAP:URI=\"media\",BYTERANGE=\"4@0\"\n#EXTINF:1.0,\n#EXT-X-BYTERANGE:4@4\nmedia\n#EXT-X-ENDLIST\n")
                "/quality/key" -> MockResponse().setBody(Buffer().write(key))
                "/quality/media" -> {
                    val range = Regex("bytes=(\\d+)-(\\d*)").matchEntire(request.getHeader("Range").orEmpty())!!
                    val start = range.groupValues[1].toInt()
                    val end = range.groupValues[2].toIntOrNull() ?: media.lastIndex
                    MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${media.size}")
                        .setBody(Buffer().write(media.copyOfRange(start, end + 1)))
                }
                else -> { unexpected += request.path.orEmpty(); MockResponse().setResponseCode(404) }
            }
        }
        server.start()
        try {
            val item = MediaItem.Builder().setUri(server.url("/master.m3u8").toString())
                .setMimeType(MimeTypes.APPLICATION_M3U8).setStreamKeys(listOf(androidx.media3.common.StreamKey(0, 1))).build()
            val downloader = SnapshotHlsDownloader(item, CacheDataSource.Factory().setCache(store.cache)
                .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory()), java.util.concurrent.Executor { it.run() })
            downloader.download(null)
            assertTrue(unexpected.toString(), unexpected.isEmpty())
            assertTrue(store.cache.isCached(server.url("/quality/media").toString(), 0, 8))
            assertTrue(store.cache.isCached(server.url("/quality/key").toString(), 0, 16))
            server.shutdown()
            downloader.remove()
            assertEquals(0, store.cache.cacheSpace)
        } finally { store.manager.release(); store.cache.release(); server.close() }
    }

    @Test fun redirectedHlsDownloadCanPrepareOffline() = roundTrip(false)

    @Test fun rotatingSegmentUrlsInRedirectedPlaylistStillPlayOffline() = roundTrip(true)

    @Test fun existingDownloadUsesItsDownloadedSnapshotWithoutFetchingAgain() = roundTrip(true, legacy = true)

    @Test fun resumedOldDownloadDoesNotSelectAnIncompleteLegacySnapshot() = roundTrip(true, legacy = true, repairInterrupted = true)

    private fun roundTrip(rotating: Boolean, legacy: Boolean = false, repairInterrupted: Boolean = false) {
        val context = RuntimeEnvironment.getApplication()
        var store = OfflineDownloads(context)
        val server = MockWebServer()
        val sample = javaClass.getResourceAsStream("/media/sample.ts")!!.use { it.readBytes() }
        var revision = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/entry.m3u8" -> MockResponse().setResponseCode(302).setHeader("Location", "/manifest")
                "/manifest" -> MockResponse().setHeader("Content-Type", "image/vnd.microsoft.icon").setBody(
                    "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXTINF:1.0,\nsegment?revision=${if (rotating) ++revision else 0}\n#EXT-X-ENDLIST\n")
                else -> if (request.path!!.startsWith("/segment?")) MockResponse().setBody(Buffer().write(sample))
                    else MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val item = MediaItem.Builder().setUri(Uri.parse(server.url("/entry.m3u8").toString())).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        var helper: DownloadHelper? = null
        try {
            val factory = CacheDataSource.Factory().setCache(store.cache)
                .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            val downloader = if (legacy) HlsDownloader(item, factory) else SnapshotHlsDownloader(item, factory, java.util.concurrent.Executor { it.run() })
            downloader.download(null)
            assertEquals(if (legacy) 2 else 1, if (rotating) revision else 1)
            if (repairInterrupted) {
                store.cache.removeResource(server.url("/segment?revision=2").toString())
                SnapshotHlsDownloader(item, factory, java.util.concurrent.Executor { it.run() }).download(null)
            }
            store.manager.release(); store.cache.release()
            store = OfflineDownloads(context) // Persisted bytes and redirect metadata, after restart.
            server.shutdown()
            if (legacy && !repairInterrupted) {
                // Reproduce the old completed download: entry manifest references missing revision 1,
                // while revision 2 is present. This used to fail with cache-only IO_UNSPECIFIED.
                assertFalse(store.cache.isCached(server.url("/segment?revision=1").toString(), 0, 1))
                assertTrue(store.cache.isCached(server.url("/segment?revision=2").toString(), 0, sample.size.toLong()))
            }
            val request = DownloadRequest.Builder("roundtrip", item.localConfiguration!!.uri).setMimeType(MimeTypes.APPLICATION_M3U8).build()
            val complete = Download(request, Download.STATE_COMPLETED, 0, 0, -1, 0, 0)
            val playbackItem = kotlinx.coroutines.runBlocking { store.playbackMediaItem(complete) }
            assertEquals(server.url(if (legacy && !repairInterrupted) "/manifest" else "/entry.m3u8").toString(), playbackItem.localConfiguration!!.uri.toString())
            helper = DownloadHelper.forMediaItem(context, playbackItem, DefaultRenderersFactory(context),
                ImageWrappedTsDataSource.Factory(store.offlineDataSource()))
            val prepared = AtomicBoolean()
            val failure = AtomicReference<IOException>()
            helper.prepare(object : DownloadHelper.Callback {
                override fun onPrepared(helper: DownloadHelper) { prepared.set(true) }
                override fun onPrepareError(helper: DownloadHelper, error: IOException) { failure.set(error) }
            })
            val deadline = System.nanoTime() + 15_000_000_000L
            while (!prepared.get() && failure.get() == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            failure.get()?.let { throw it }
            assertTrue("Downloaded HLS did not prepare offline", prepared.get())
            helper.release(); helper = null
            SnapshotHlsDownloader(item, CacheDataSource.Factory().setCache(store.cache), java.util.concurrent.Executor { it.run() }).remove()
            assertEquals("Removing a legacy download must also remove its second snapshot's segments", 0, store.cache.cacheSpace)
        } finally {
            helper?.release()
            store.manager.release(); store.cache.release(); server.close()
        }
    }
}
