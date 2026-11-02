package io.agedm.tv.data

import io.agedm.tv.ui.animeWeeklyScheduleText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AnimeWeeklyScheduleTest {
    @Test fun sundayScheduleDoesNotBecomeMondayBecauseUploadWasLate() {
        val feed = Json.decodeFromString<AgeHomeResponse>("""{"week_list":{"0":[
            {"id":42,"wd":0,"mtime":"2026-11-02 00:31","namefornew":"23:00 第05集"},
            {"id":42,"wd":0,"mtime":"2026-11-02 00:31","namefornew":"23:00 第05集"}
        ]}}""")
        assertEquals(listOf(7), feed.airingWeekdays(42))
        assertEquals("每周日（AGE 周播表）", animeWeeklyScheduleText("连载", feed.airingWeekdays(42)))
        assertTrue(feed.airingWeekdays(99).isEmpty())
    }

    @Test fun finishedAndInvalidCalendarEntriesAreIgnored() {
        val feed = AgeHomeResponse(weekList = mapOf(
            "1" to listOf(AgeScheduleItem(id = 1, wd = 1, nameForNew = "第26集(完结)")),
            "2" to listOf(AgeScheduleItem(id = 1, wd = 3)),
            "7" to listOf(AgeScheduleItem(id = 1, wd = 7)),
            "bad" to listOf(AgeScheduleItem(id = 1, wd = 0)),
        ))
        assertTrue(feed.airingWeekdays(1).isEmpty())
        assertNull(animeWeeklyScheduleText("完结", listOf(1)))
        assertNull(animeWeeklyScheduleText("连载", emptyList()))
        assertNull(animeWeeklyScheduleText("未播放", listOf(1)))
    }

    @Test fun multipleWeeklyDaysAreDistinctAndOrdered() {
        val feed = AgeHomeResponse(weekList = mapOf(
            "5" to listOf(AgeScheduleItem(id = 1, wd = 5)),
            "1" to listOf(AgeScheduleItem(id = 1, wd = 1)),
        ))
        assertEquals("每周一、周五（AGE 周播表）", animeWeeklyScheduleText("连载", feed.airingWeekdays(1)))
    }

    @Test fun scheduleFailureDoesNotPreventDetailLoadingOrIntroducePlaybackDependency() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            paths += path
            val body = if (path == "/v2/detail/42") """{"video":{"id":42,"status":"连载"}}""" else "<html></html>"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (path == "/v2/home-list") 503 else 200).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repository = AgeRepository(client = client)
        assertEquals(42L, repository.fetchDetail(42).animeId)
        assertFalse(paths.contains("/v2/home-list"))
        assertTrue(runCatching { repository.fetchAiringWeekdays(42) }.isFailure)
        assertEquals(42L, repository.fetchDetail(42).animeId)
    }
}
