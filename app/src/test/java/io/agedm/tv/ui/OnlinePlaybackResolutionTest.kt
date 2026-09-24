package io.agedm.tv.ui

import android.app.Application
import android.os.Looper
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.DownloadHelper
import io.agedm.tv.data.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
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
class OnlinePlaybackResolutionTest {
    @Test fun onlineHlsDoesNotConsumeSingleUseUrlBeforeNativePlayer() {
        MockWebServer().use { server ->
            val sample = javaClass.getResourceAsStream("/media/sample.ts")!!.use { it.readBytes() }
            val manifestReads = java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.path) {
                        "/episode.m3u8" -> if (manifestReads.incrementAndGet() == 1)
                            MockResponse().setBody("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXTINF:1.0,\npart.ts\n#EXT-X-ENDLIST\n")
                            else MockResponse().setResponseCode(410)
                        "/part.ts" -> MockResponse().setBody(Buffer().write(sample))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val resolved = resolveFromBridge(server.url("/episode.m3u8").toString(), "")
            assertEquals("Online resolution must not make an extra validation GET", 0, server.requestCount)
            assertEquals(MimeTypes.APPLICATION_M3U8, resolved.mimeType)
            prepareOnline(resolved)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun opaqueHlsPreservesBrowserMimeWithoutConsumingOneUseAddress() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(410)) // Any preflight would reject it.
            val resolved = resolveFromBridge(server.url("/signed?token=one-use").toString(), MimeTypes.APPLICATION_M3U8)
            assertEquals(MimeTypes.APPLICATION_M3U8, resolved.mimeType)
            assertEquals(0, server.requestCount)
        }
    }

    private fun resolveFromBridge(url: String, hint: String): ResolvedStream {
        val context = RuntimeEnvironment.getApplication()
        val host = FrameLayout(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val resolver = WebStreamResolver(context, host, scope, AgeRepository())
        try {
            val episode = EpisodeItem(0, "1", "https://example.invalid/player")
            val source = EpisodeSource("s", "S", false, listOf(episode), resolver = SourceResolver.WEB_PAGE)
            val detail = AnimeDetail(1, "Test", "", "", "", "", sources = listOf(source), related = emptyList(),
                similar = emptyList(), vipSourceKeys = emptySet(), playerJx = PlayerJx())
            val result = scope.async { resolver.resolve(detail, source, episode) }
            shadowOf(Looper.getMainLooper()).idle()
            val bridge = shadowOf(host.getChildAt(0) as WebView).getJavascriptInterface("AgeTvParserBridge")
            bridge.javaClass.getDeclaredMethod("reportMedia", Int::class.javaPrimitiveType, String::class.java, String::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(bridge, 1, url, episode.token, hint)
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!result.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            assertTrue("A resolved online URL must not wait for another HTTP request", result.isCompleted)
            return runBlocking { result.await() }
        } finally { resolver.release(); scope.cancel() }
    }

    private fun prepareOnline(stream: ResolvedStream) {
        val context = RuntimeEnvironment.getApplication()
        val helper = DownloadHelper.forMediaItem(context,
            MediaItem.Builder().setUri(stream.streamUrl).setMimeType(stream.mimeType).build(),
            DefaultRenderersFactory(context), ImageWrappedTsDataSource.Factory(DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(stream.headers)))
        try {
            val ready = AtomicBoolean()
            val failure = AtomicReference<IOException>()
            helper.prepare(object : DownloadHelper.Callback {
                override fun onPrepared(helper: DownloadHelper) { ready.set(true) }
                override fun onPrepareError(helper: DownloadHelper, error: IOException) { failure.set(error) }
            })
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!ready.get() && failure.get() == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            failure.get()?.let { throw it }
            assertTrue("Online HLS tracks did not prepare", ready.get())
        } finally { helper.release() }
    }
}
