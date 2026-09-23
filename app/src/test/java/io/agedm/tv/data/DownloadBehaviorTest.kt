package io.agedm.tv.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DownloadBehaviorTest {
    private fun metadata(index: Int, source: String = "s") = OfflineEpisode(1, "Test", source, source, index, "第${index + 1}集", emptyMap())
    private fun task(index: Int) = QueuedEpisode(downloadId(1, "s", index), 1, "Test", "", "s", "Source", "AGE",
        SourceResolver.AGE_PARSER, false, emptyMap(), PlayerJx(), index, "第${index + 1}集", "token$index")

    @Test fun sparseDownloadsKeepOriginalIdentitiesAcrossSources() {
        val detail = mergeDownloadedDetail(null, listOf(metadata(99), metadata(101), metadata(99, "other")), emptyList())!!
        val source = detail.sources.first()
        assertEquals(listOf(99, 101), source.episodes.map { it.index })
        assertEquals(101, source.episodeIndexOrFirst(101))
        assertEquals(99, source.episodeIndexOrFirst(0))
        assertFalse(source.episodes.any { it.index == 100 }) // Auto-next cannot silently skip episode 101.
        assertEquals(2, detail.sources.size)
        assertNotEquals(downloadId(1, "s", 99), downloadId(1, "other", 99))
        assertNotEquals(downloadId(1, "s", 99), downloadId(2, "s", 99))
    }

    @Test fun downloadedDetailRetainsOnlinePlaylistAndParserData() {
        val queued = task(99)
        val base = queued.detail().copy(sources = listOf(queued.source().copy(episodes = listOf(
            EpisodeItem(98, "99", "online99"), queued.episode(), EpisodeItem(100, "101", "online101")))))
        val merged = mergeDownloadedDetail(base, listOf(metadata(99)), listOf(queued))!!
        assertEquals(base, merged)
        assertEquals(base, Json.decodeFromString<AnimeDetail>(Json.encodeToString(base)))
        assertEquals("online101", merged.sources.single().episodes.last().token)
    }

    @Test fun oldDownloadMetadataStillDecodesWithoutCover() {
        val old = """{"animeId":1,"title":"Test","sourceKey":"s","sourceLabel":"S","episodeIndex":99,"episodeLabel":"100","headers":{}}"""
        assertEquals("", Json.decodeFromString<OfflineEpisode>(old).cover)
    }

    @Test fun largeSelectionPreservesIdentityWhenOrderReversesAndClearsOnSourceChange() {
        val selection = EpisodeDownloadSelection()
        selection.begin("s", 999)
        selection.toggle(10)
        selection.reconcile("s", (0..1200).reversed().toSet())
        assertEquals(setOf(999, 10), selection.indices)
        selection.selectAll((0..1200).toList())
        assertEquals(1201, selection.indices.size)
        selection.toggle(999)
        assertFalse(999 in selection.indices)
        selection.reconcile("other", (0..1200).toSet())
        assertFalse(selection.active)
        assertTrue(selection.indices.isEmpty())
    }

    @Test fun batchDeduplicatesPersistsAndRecoversPreparationWithoutUnpausing() {
        var disk = emptyList<QueuedEpisode>()
        val queue = DownloadQueue(emptyList()) { disk = it }
        assertEquals(1199, queue.add((0..1200).map(::task) + task(1), setOf(task(0).id, task(1200).id)))
        assertEquals(0, queue.add(listOf(task(1)), emptySet()))
        queue.update(setOf(task(1).id), PreparationState.PREPARING)
        queue.update(setOf(task(2).id), PreparationState.PAUSED)
        val recovered = DownloadQueue(disk) { disk = it }
        assertEquals(PreparationState.WAITING, recovered.all().first { it.episodeIndex == 1 }.state)
        assertEquals(PreparationState.PAUSED, recovered.all().first { it.episodeIndex == 2 }.state)
        recovered.remove(setOf(task(1).id))
        assertFalse(disk.any { it.episodeIndex == 1 })
    }

    @Test fun failedDiskWriteDoesNotPublishUnsavedQueue() {
        val queue = DownloadQueue(listOf(task(1))) { throw java.io.IOException("disk full") }
        assertThrows(java.io.IOException::class.java) { queue.update(setOf(task(1).id), PreparationState.PAUSED) }
        assertEquals(PreparationState.WAITING, queue.all().single().state)
    }

    @Test fun speedUsesRecentByteDeltasAndResetsAfterPauseOrCounterReset() {
        val tracker = DownloadSpeedTracker()
        assertEquals(0, tracker.sample("a", 1000, true, 0))
        assertEquals(2000, tracker.sample("a", 3000, true, 1000))
        assertEquals(0, tracker.sample("a", 3000, false, 2000))
        assertEquals(0, tracker.sample("a", 3000, true, 10000))
        assertEquals(1000, tracker.sample("a", 4000, true, 11000))
        assertEquals(0, tracker.sample("a", 0, true, 12000))
        for (i in 13L..20L) assertEquals(0, tracker.sample("a", 0, true, i * 1000))
        tracker.retain(emptySet())
        assertEquals(0, tracker.sample("a", 9999, true, 30000))
    }

    @Test fun groupProgressCountsPendingEpisodesAndKeepsDifferentShowsSeparate() {
        val complete = DownloadEntry("1", 1, "Test", "poster", "s", "S", 0, "1", DownloadStatus.COMPLETED, 1000)
        val groups = groupDownloads(listOf(complete,
            complete.copy(id = "2", episodeIndex = 1, status = DownloadStatus.WAITING, bytes = 0),
            complete.copy(id = "3", animeId = 2)))
        assertEquals(2, groups.size)
        assertEquals(50, groups.first().progress)
        assertEquals(1000, groups.first().bytes)
        assertEquals(1, groups.first().complete)
    }
}
