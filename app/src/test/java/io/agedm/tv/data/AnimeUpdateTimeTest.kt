package io.agedm.tv.data

import io.agedm.tv.ui.animeUpdateText
import io.agedm.tv.ui.detailStatusText
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AnimeUpdateTimeTest {
    private val instant = Instant.parse("2026-11-01T16:31:26Z")

    @Test fun apiTimestampSupportsNumbersAndStringsAndFallsBackToSiteTime() {
        for (raw in listOf("1793550686", "\"1793550686\"", "null", "0", "\"bad\"")) {
            val video = Json.decodeFromString<AgeVideo>("""{"time":$raw,"time_format_3":"2026-11-02 00:31:26"}""")
            assertEquals(instant.toEpochMilli(), video.lastUpdatedEpochMillis())
        }
        assertNull(Json.decodeFromString<AgeVideo>("{}").lastUpdatedEpochMillis())
        assertNull(AgeVideo(updatedAtText = "2026-02-30 18:00:00").lastUpdatedEpochMillis())
        assertNull(AgeVideo(updatedAtText = "每天 23:00").lastUpdatedEpochMillis())
    }

    @Test fun repositoryCarriesSiteTimestampAndEpisodeIntoPersistedDetail() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (chain.request().url.host == "api.agedm.io")
                """{"video":{"id":1,"status":"连载","uptodate":"23:00 第05集","time":1793550686}}""" else "<html></html>"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val detail = AgeRepository(client = client).fetchDetail(1)
        assertEquals(instant.toEpochMilli(), detail.lastUpdatedEpochMs)
        assertEquals("连载 · 第05集", detailStatusText(detail.status, detail.updateLabel))
        assertEquals(detail, Json.decodeFromString<AnimeDetail>(Json.encodeToString(detail)))
        val old = Json.encodeToString(detail.copy(updateLabel = "", lastUpdatedEpochMs = null))
        assertFalse(old.contains("lastUpdatedEpochMs"))
        assertNull(Json.decodeFromString<AnimeDetail>(old).lastUpdatedEpochMs)
    }

    @Test fun siteTimeDisplaysInTvTimezoneEvenAcrossDifferentCalendarDates() {
        val now = Instant.parse("2026-11-01T17:00:00Z")
        assertEquals("AGE 最近更新：今天（周日）11:31（本地时间）",
            animeUpdateText(instant.toEpochMilli(), now, ZoneId.of("America/Indiana/Indianapolis")))
        assertEquals("AGE 最近更新：今天（周一）00:31（本地时间）",
            animeUpdateText(instant.toEpochMilli(), now, ZoneId.of("Asia/Shanghai")))
    }

    @Test fun yesterdayUsesCalendarDayAcrossDstAndOlderDatesStayExplicit() {
        val zone = ZoneId.of("America/New_York")
        val beforeFallback = Instant.parse("2026-11-01T05:30:00Z").toEpochMilli()
        assertEquals("AGE 最近更新：昨天（周日）01:30（本地时间）",
            animeUpdateText(beforeFallback, Instant.parse("2026-11-02T06:00:00Z"), zone))
        assertEquals("AGE 最近更新：2026-11-01（周日）01:30（本地时间）",
            animeUpdateText(beforeFallback, Instant.parse("2027-01-01T12:00:00Z"), zone))
    }

    @Test fun unknownTimeAndMissingEpisodeDoNotInventUpdates() {
        assertNull(animeUpdateText(null))
        assertNull(animeUpdateText(0))
        assertEquals("连载", detailStatusText("连载", ""))
        assertEquals("完结 · 全12集", detailStatusText("完结", "全12集"))
        assertEquals("连载 · 第05集", detailStatusText("连载", "第05集"))
    }
}
