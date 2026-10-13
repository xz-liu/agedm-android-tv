package io.agedm.tv.data

import android.app.Application
import java.io.IOException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LinkCastSearchTest {
    @Test fun certificateFailureIsAnErrorAndSubsequentEmptySearchIsARealSuccess() {
        var fail = true
        val repo = AgeRepository(client = OkHttpClient.Builder().addInterceptor { chain ->
            if (fail) throw SSLHandshakeException("java trust anchor not found")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"videos":[],"total":0}}""".toResponseBody("application/json".toMediaType())).build()
        }.build())
        val context = RuntimeEnvironment.getApplication()
        val account = BangumiAccountService(repo, PlaybackStore(context), BangumiAccountStore(context))
        var mirrored = MirrorState()
        val server = LinkCastManager.LinkCastHttpServer(0, {}, { mirrored = it }, repo, account)
        server.start()
        try {
            val client = OkHttpClient()
            val base = "http://127.0.0.1:${server.listeningPort}"
            val message = client.newCall(Request.Builder().url("$base/api/search?q=GATE").build()).execute().use {
                assertEquals(503, it.code)
                assertTrue(it.header("Cache-Control")!!.contains("no-store"))
                JSONObject(it.body!!.string()).getString("error").also { text ->
                    assertTrue(text.contains("证书")); assertFalse(text.contains("java"))
                }
            }
            val body = JSONObject().put("query", "GATE").put("error", message).toString()
            client.newCall(Request.Builder().url("$base/api/mirror").post(body.toRequestBody("application/json".toMediaType())).build())
                .execute().use { assertEquals(200, it.code) }
            assertEquals(message, mirrored.error)
            fail = false
            client.newCall(Request.Builder().url("$base/api/search?q=GATE").build()).execute().use {
                assertEquals(200, it.code); assertEquals("[]", it.body!!.string())
            }
            client.newCall(Request.Builder().url("$base/api/mirror").post("""{"query":"GATE","results":[]}""".toRequestBody("application/json".toMediaType())).build())
                .execute().close()
            assertEquals("", mirrored.error)
            // The emitted page is also checked with a JS runtime during development.
            client.newCall(Request.Builder().url(base).build()).execute().use {
                java.io.File("build/reports/phone-search.html").apply { parentFile?.mkdirs(); writeText(it.body!!.string()) }
            }
        } finally { server.stop() }
    }

    @Test fun wrappedCertificateExceptionsHaveReadableMessagesWithoutHidingOtherErrors() {
        val error = IOException("request failed", CertPathValidatorException("Trust anchor not found"))
        assertTrue(networkFailureMessage(error, "fallback").contains("证书"))
        assertEquals("fallback", networkFailureMessage(IOException("offline"), "fallback"))
    }
}
