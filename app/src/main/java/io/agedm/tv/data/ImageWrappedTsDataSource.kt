package io.agedm.tv.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Some HLS hosts prepend a complete PNG to each TS segment. Keep cached bytes unchanged;
 * remove the wrapper at the playback boundary, including on subsequent/retried segments. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ImageWrappedTsDataSource private constructor(
    private val upstream: DataSource,
    private val prefixes: MutableMap<String, Int>,
) : DataSource {
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        private val prefixes = ConcurrentHashMap<String, Int>()
        override fun createDataSource(): DataSource = ImageWrappedTsDataSource(upstream.createDataSource(), prefixes)
    }

    private var buffered = ByteArray(0)
    private var offset = 0

    override fun open(dataSpec: DataSpec): Long {
        buffered = ByteArray(0); offset = 0
        // Explicit HLS byte ranges describe the original file and must remain byte-for-byte.
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) return upstream.open(dataSpec)
        val key = dataSpec.key ?: dataSpec.uri.toString()
        if (dataSpec.position > 0) {
            val prefix = prefixes[key] ?: 0
            return upstream.open(dataSpec.buildUpon().setPosition(dataSpec.position + prefix).build())
        }
        try {
            val length = upstream.open(dataSpec)
            val probe = ByteArrayOutputStream()
            fun readTo(size: Int): Boolean {
                val temp = ByteArray((size - probe.size()).coerceAtLeast(0))
                var read = 0
                while (read < temp.size) {
                    val count = upstream.read(temp, read, temp.size - read)
                    if (count == C.RESULT_END_OF_INPUT) break
                    if (count == 0) break
                    read += count
                }
                probe.write(temp, 0, read)
                return probe.size() >= size
            }
            var skip = 0
            if (readTo(8) && probe.toByteArray().contentEquals(PNG_SIGNATURE)) {
                var cursor = 8
                while (cursor + 12 <= MAX_PREFIX_BYTES && readTo(cursor + 8)) {
                    val header = probe.toByteArray()
                    val chunkSize = (0..3).fold(0L) { value, i -> (value shl 8) or (header[cursor + i].toLong() and 255) }
                    val end = cursor.toLong() + 12 + chunkSize
                    if (end > MAX_PREFIX_BYTES || !readTo(end.toInt())) break
                    val isEnd = header.copyOfRange(cursor + 4, cursor + 8).contentEquals(byteArrayOf(73, 69, 78, 68))
                    cursor = end.toInt()
                    if (isEnd) {
                        if (chunkSize == 0L && readTo(cursor + 188 * 4 + 1)) {
                            val bytes = probe.toByteArray()
                            if ((0..4).all { bytes[cursor + it * 188] == 0x47.toByte() }) skip = cursor
                        }
                        break
                    }
                }
            }
            buffered = probe.toByteArray(); offset = skip
            if (skip > 0) prefixes[key] = skip else prefixes.remove(key)
            return if (length == C.LENGTH_UNSET.toLong()) length else (length - skip).coerceAtLeast(0)
        } catch (error: Exception) { upstream.close(); throw error }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (this.offset < buffered.size) {
            val count = minOf(length, buffered.size - this.offset)
            buffered.copyInto(buffer, offset, this.offset, this.offset + count)
            this.offset += count
            return count
        }
        return upstream.read(buffer, offset, length)
    }
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
    override fun close() { buffered = ByteArray(0); offset = 0; upstream.close() }

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)
        private const val MAX_PREFIX_BYTES = 64 * 1024
    }
}
