package io.agedm.tv.data

import android.app.Application
import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DownloadParallelTest {
    @Test fun defaultsToFourAndAppliesSavedLimitToExistingAndRecreatedManager() {
        val context = RuntimeEnvironment.getApplication()
        val settings = DownloadSettings(context)
        assertEquals(4, settings.parallelDownloads)
        val store = OfflineDownloads(context)
        try {
            assertEquals(4, store.manager.maxParallelDownloads)
            store.setParallelDownloads(8)
            assertEquals(8, store.manager.maxParallelDownloads)
            assertEquals(8, settings.parallelDownloads)
            store.setParallelDownloads(2)
            assertEquals(2, store.manager.maxParallelDownloads)
            assertEquals(2, DownloadSettings(context).parallelDownloads)
            assertThrows(IllegalArgumentException::class.java) { store.setParallelDownloads(0) }
            assertThrows(IllegalArgumentException::class.java) { store.setParallelDownloads(9) }
            assertEquals(2, settings.parallelDownloads)
        } finally { store.manager.release(); store.cache.release() }
        val recreated = OfflineDownloads(context)
        try { assertEquals(2, recreated.manager.maxParallelDownloads) }
        finally { recreated.manager.release(); recreated.cache.release() }
    }

    @Test fun fillsFourSlotsWithoutWaitingForFirstTransferToFinish() {
        assertEquals(4, availableDownloadSlots(emptyList(), 4))
        assertEquals(3, availableDownloadSlots(listOf(Download.STATE_DOWNLOADING), 4))
        assertEquals(1, availableDownloadSlots(listOf(Download.STATE_DOWNLOADING,
            Download.STATE_QUEUED, Download.STATE_RESTARTING), 4))
        assertEquals(0, availableDownloadSlots(List(4) { Download.STATE_DOWNLOADING }, 4))
    }

    @Test fun loweringLimitStopsPreparationUntilRoomOpensAndRaisingCreatesRoom() {
        val active = List(4) { Download.STATE_DOWNLOADING }
        assertEquals(0, availableDownloadSlots(active, 2))
        assertEquals(0, availableDownloadSlots(active.take(2), 2))
        assertEquals(1, availableDownloadSlots(active.take(1), 2))
        assertEquals(4, availableDownloadSlots(active, 8))
    }

    @Test fun stoppedFailedCompletedAndUnrelatedDeletionDoNotBlockTransfers() {
        val states = listOf(Download.STATE_STOPPED, Download.STATE_FAILED,
            Download.STATE_COMPLETED, Download.STATE_REMOVING, Download.STATE_DOWNLOADING)
        assertEquals(3, availableDownloadSlots(states, 4))
    }

    @Test fun changingLimitDoesNotResumePausedEpisodes() {
        val store = OfflineDownloads(RuntimeEnvironment.getApplication())
        try {
            val task = QueuedEpisode("paused", 1, "Series", "", "s", "S", "AGE",
                SourceResolver.AGE_PARSER, false, emptyMap(), PlayerJx(), 0, "1", "token", PreparationState.PAUSED)
            store.queue.add(listOf(task), emptySet())
            store.setParallelDownloads(8)
            assertEquals(PreparationState.PAUSED, store.queue.all().single().state)
        } finally { store.manager.release(); store.cache.release() }
    }
}
