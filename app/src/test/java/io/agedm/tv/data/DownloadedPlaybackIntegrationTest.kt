package io.agedm.tv.data

import android.app.Application
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DownloadedPlaybackIntegrationTest {
    @Test fun onlyCompleteMatchingEpisodeUsesDownloadAndMetadataSurvivesWithoutContentCache() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val database = StandaloneDatabaseProvider(context)
        val index = DefaultDownloadIndex(database)
        val metadata = OfflineEpisode(42, "Series", "original", "Source", 99, "第100集", emptyMap())
        val request = DownloadRequest.Builder(downloadId(42, "original", 99), Uri.parse("https://example.invalid/100.mp4"))
            .setData(metadata.encode()).build()
        index.putDownload(Download(request, Download.STATE_COMPLETED, 0, 0, 4, 0, 0))
        val store = OfflineDownloads(context)
        try {
            assertNotNull(store.completed(42, "original", 99))
            assertNull(store.completed(42, "another-source", 99))
            assertNull(store.completed(42, "original", 0))
            val detail = store.playbackDetail(42, null)!!
            assertEquals(99, detail.sources.single().episodes.single().index)
            index.putDownload(Download(request, Download.STATE_STOPPED, 0, 0, 4, 1, 0))
            assertNull(store.completed(42, "original", 99))
            index.putDownload(Download(request, Download.STATE_FAILED, 0, 0, 4, 0, Download.FAILURE_REASON_UNKNOWN))
            assertNull(store.completed(42, "original", 99))
        } finally {
            store.manager.release()
            store.cache.release()
            database.close()
        }
    }

    @Test fun downloadedDataSourceReadsLocalBytesAndCannotFetchMissingBytes() {
        val store = OfflineDownloads(RuntimeEnvironment.getApplication())
        try {
            val uri = "https://example.invalid/local.mp4"
            val hole = store.cache.startReadWrite(uri, 0, 4)
            try {
                val file = store.cache.startFile(uri, 0, 4)
                file.writeBytes(byteArrayOf(1, 2, 3, 4))
                store.cache.commitFile(file, 4)
            } finally { store.cache.releaseHoleSpan(hole) }
            val source = store.offlineDataSource().createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(uri).setLength(4).build())
                val buffer = ByteArray(4)
                assertEquals(4, source.read(buffer, 0, 4))
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), buffer)
            } finally { source.close() }
            val missing = store.offlineDataSource().createDataSource()
            try {
                assertThrows(java.io.IOException::class.java) {
                    missing.open(DataSpec.Builder().setUri("https://example.invalid/missing.mp4").build())
                }
            } finally { missing.close() }
        } finally { store.manager.release(); store.cache.release() }
    }
}
