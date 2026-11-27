package io.agedm.tv.data

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BrowseCacheTest {
    @Test fun staleHomeRefreshesAndChangedTitlesInvalidateTheIndex() = runBlocking {
        val db = AppStorageDatabase.get(RuntimeEnvironment.getApplication())
        val cache = ContentCache(db)
        val store = PersistentJsonStore(db)
        val oldFeed = """{"latest":[{"AID":1,"Title":"A"}]}"""
        val newFeed = """{"latest":[{"AID":1,"Title":"B"}]}"""
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(newFeed.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = AgeRepository(cache, store, client)
        db.putKvEntry("home", oldFeed, writeTs = 1L)
        val stale = requireNotNull(repo.peekHomeFeed())
        assertFalse(stale.isFresh)
        assertEquals("A", stale.value.latest.single().title)
        val index = repo.loadInitialsIndex()
        assertSame(index, repo.loadInitialsIndex())
        assertEquals(1L, index.search("A").single().animeId)
        assertEquals("B", repo.fetchHomeFeed().latest.single().title)
        val updated = repo.loadInitialsIndex()
        assertTrue(updated.search("A").isEmpty())
        assertEquals(1L, updated.search("B").single().animeId)

        val stored = store.read("age_lookup_1")
        assertTrue(requireNotNull(repo.peekHomeFeed()).isFresh)
        assertEquals(stored, store.read("age_lookup_1"))
        assertSame(updated, repo.loadInitialsIndex())
        assertEquals(1, requests)
    }

    @Test fun touchingLargePosterListsPreservesExpiryAndMissingDetails() {
        val db = AppStorageDatabase.get(RuntimeEnvironment.getApplication())
        val cache = ContentCache(db)
        db.putKvEntry("detail_502", "detail", writeTs = 1L)
        cache.touchAll((1..520).map { "detail_$it" })
        cache.evictExpired()
        assertEquals("detail", cache.peek("detail_502"))
        assertNull(cache.get("detail_502", 10))
        assertNull(cache.peek("detail_501"))
    }
}
