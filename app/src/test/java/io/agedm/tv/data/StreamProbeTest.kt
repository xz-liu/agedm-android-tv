package io.agedm.tv.data

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class StreamProbeTest {
    private fun stream(url: String) = ResolvedStream(url, "https://example.invalid/player", "s", "S", EpisodeItem(0, "1", "token"),
        false, headers = mapOf("Referer" to "https://example.invalid/player"))

    @Test fun followsRedirectAndIdentifiesImageLabelledHlsWithoutChangingPlaybackUrl() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/opaque"))
            server.enqueue(MockResponse().addHeader("Content-Type", "image/vnd.microsoft.icon").setBody("#EXTM3U\n#EXT-X-ENDLIST"))
            val url = server.url("/video").toString()
            val resolved = probeStream(OkHttpClient(), stream(url))
            assertTrue(resolved.isM3u8)
            assertEquals("application/x-mpegURL", resolved.mimeType)
            assertEquals(url, resolved.streamUrl)
            assertEquals("https://example.invalid/player", server.takeRequest().getHeader("Referer"))
        }
    }

    @Test fun htmlSuccessResponseIsRejectedInsteadOfBecomingCompletedDownload() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<!DOCTYPE html><html>expired</html>"))
            try { probeStream(OkHttpClient(), stream(server.url("/video.mp4").toString())); fail("HTML must not be downloaded as video") }
            catch (error: IOException) { assertTrue(error.message!!.contains("网页")) }
        }
    }

    @Test fun errorStatusIsReportedAndDoesNotCreatePlayableResult() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            try { probeStream(OkHttpClient(), stream(server.url("/video.mp4").toString())); fail("HTTP failure") }
            catch (error: IOException) { assertTrue(error.message!!.contains("403")) }
        }
    }
}
