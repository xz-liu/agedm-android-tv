package io.agedm.tv.data

import android.app.Application
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MediaCompatibilityTest {
    private fun ts() = ByteArray(188 * 8) { (it % 251).toByte() }.apply { for (i in 0..7) this[i * 188] = 0x47 }
    private fun png(size: Int = 119): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10))
        // Synthetic PNG chunks with the same 139-byte prefix size observed on GATE.
        val dataLength = size - 12
        java.io.DataOutputStream(output).apply {
            writeInt(dataLength); writeBytes("IDAT"); write(ByteArray(dataLength)); writeInt(0)
            writeInt(0); writeBytes("IEND"); writeInt(0)
        }
        return output.toByteArray()
    }
    private fun readAll(source: DataSource): ByteArray {
        val result = ByteArrayOutputStream(); val buffer = ByteArray(37)
        while (true) { val n = source.read(buffer, 0, buffer.size); if (n == C.RESULT_END_OF_INPUT) break; result.write(buffer, 0, n) }
        return result.toByteArray()
    }
    private fun factory(bytes: ByteArray) = ImageWrappedTsDataSource.Factory(DataSource.Factory { ByteArrayDataSource(bytes) })
    private fun spec(position: Long = 0) = DataSpec.Builder().setUri("https://example.invalid/segment").setPosition(position).build()

    @Test fun stripsGateStylePrefixOnEverySegmentAndKeepsLogicalLength() {
        val media = ts(); val prefix = png(); assertEquals(139, prefix.size)
        val factory = factory(prefix + media)
        repeat(2) {
            val source = factory.createDataSource()
            try { assertEquals(media.size.toLong(), source.open(spec())); assertArrayEquals(media, readAll(source)) }
            finally { source.close() }
        }
    }

    @Test fun retryTranslatesLogicalPositionBackToOriginalCachedOffset() {
        val media = ts(); val factory = factory(png() + media)
        val first = factory.createDataSource()
        first.open(spec()); first.read(ByteArray(376), 0, 376); first.close()
        val retry = factory.createDataSource()
        try {
            assertEquals((media.size - 376).toLong(), retry.open(spec(376)))
            assertArrayEquals(media.copyOfRange(376, media.size), readAll(retry))
        } finally { retry.close() }
    }

    @Test fun largerImageWrappersWorkWithoutChangingOrdinaryTsOrImageFiles() {
        val media = ts()
        for (bytes in listOf(media, png() + byteArrayOf(1, 2, 3), "#EXTM3U\n".toByteArray(), byteArrayOf(1, 2))) {
            val source = factory(bytes).createDataSource()
            try { source.open(spec()); assertArrayEquals(bytes, readAll(source)) } finally { source.close() }
        }
        val source = factory(png(1024) + media).createDataSource()
        try { source.open(spec()); assertArrayEquals(media, readAll(source)) } finally { source.close() }
    }

    @Test fun explicitByteRangesRemainUnmodified() {
        val original = png() + ts(); val source = factory(original).createDataSource()
        try {
            source.open(spec().buildUpon().setLength(200).build())
            assertArrayEquals(original.copyOfRange(0, 200), readAll(source))
        } finally { source.close() }
    }

    @Test fun sameCompatibilityPathReadsRawOfflineCacheWithoutNetwork() {
        val store = OfflineDownloads(RuntimeEnvironment.getApplication())
        val original = png() + ts(); val uri = spec().uri.toString()
        try {
            val hole = store.cache.startReadWrite(uri, 0, original.size.toLong())
            try {
                val file = store.cache.startFile(uri, 0, original.size.toLong()); file.writeBytes(original)
                store.cache.commitFile(file, original.size.toLong())
                val changes = androidx.media3.datasource.cache.ContentMetadataMutations()
                androidx.media3.datasource.cache.ContentMetadataMutations.setContentLength(changes, original.size.toLong())
                store.cache.applyContentMetadataMutations(uri, changes)
            } finally { store.cache.releaseHoleSpan(hole) }
            val source = ImageWrappedTsDataSource.Factory(store.offlineDataSource()).createDataSource()
            try { source.open(spec()); assertArrayEquals(ts(), readAll(source)) } finally { source.close() }
            assertEquals(original.size.toLong(), store.cache.cacheSpace)
        } finally { store.manager.release(); store.cache.release() }
    }

    @Test fun previouslyMislabelledDownloadUsesCachedManifestSignature() = kotlinx.coroutines.runBlocking {
        val store = OfflineDownloads(RuntimeEnvironment.getApplication())
        val uri = spec().uri
        val manifest = "#EXTM3U\n#EXT-X-ENDLIST".toByteArray()
        try {
            val hole = store.cache.startReadWrite(uri.toString(), 0, manifest.size.toLong())
            try {
                val file = store.cache.startFile(uri.toString(), 0, manifest.size.toLong()); file.writeBytes(manifest)
                store.cache.commitFile(file, manifest.size.toLong())
                val changes = androidx.media3.datasource.cache.ContentMetadataMutations()
                androidx.media3.datasource.cache.ContentMetadataMutations.setContentLength(changes, manifest.size.toLong())
                store.cache.applyContentMetadataMutations(uri.toString(), changes)
            } finally { store.cache.releaseHoleSpan(hole) }
            val request = androidx.media3.exoplayer.offline.DownloadRequest.Builder("old", uri).setMimeType("video/mp4").build()
            val download = androidx.media3.exoplayer.offline.Download(request, androidx.media3.exoplayer.offline.Download.STATE_COMPLETED, 0, 0, manifest.size.toLong(), 0, 0)
            val item = store.playbackMediaItem(download)
            assertEquals("application/x-mpegURL", item.localConfiguration!!.mimeType)
            assertEquals(uri, item.localConfiguration!!.uri)
            assertEquals("video/mp4", download.request.mimeType) // Reading does not mutate/delete the old download.
        } finally { store.manager.release(); store.cache.release() }
    }

    @Test fun manifestsOverrideMisleadingMimeTypesAndScriptQueryStringsAreNotMedia() {
        assertEquals("application/x-mpegURL", detectMediaMimeType("https://host/video", "image/vnd.microsoft.icon", "#EXTM3U\n#EXT-X-ENDLIST".toByteArray()))
        assertEquals("application/x-mpegURL", detectMediaMimeType("https://host/file.mp4", null, "\uFEFF #EXTM3U\n".toByteArray()))
        assertEquals("application/dash+xml", detectMediaMimeType("https://host/play", null, "<?xml version='1.0'?><MPD type='static'>".toByteArray()))
        assertFalse(isMediaResourceUrl("https://host/player.js?url=https://host/ep.m3u8"))
        assertFalse(isMediaResourceUrl("https://host/part.m4s?sign=1"))
        assertTrue(isMediaResourceUrl("https://host/show.mkv?token=1"))
    }

    @Test fun sharedMediaSourceFactorySupportsAllIncludedStreamingProtocols() {
        val supported = DefaultMediaSourceFactory(RuntimeEnvironment.getApplication()).supportedTypes.toSet()
        assertTrue(supported.containsAll(listOf(C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_DASH, C.CONTENT_TYPE_SS, C.CONTENT_TYPE_OTHER)))
    }
}
