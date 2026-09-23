package io.agedm.tv.data

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** A bounded GET detects extensionless manifests and rejects HTML error pages before download. */
internal suspend fun probeStream(client: OkHttpClient, stream: ResolvedStream): ResolvedStream = suspendCancellableCoroutine { continuation ->
    val request = Request.Builder().url(stream.streamUrl).apply {
        stream.headers.forEach { (name, value) -> header(name, value) }
    }.build()
    val call = client.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    if (!response.isSuccessful) throw IOException("媒体地址返回 HTTP ${response.code}")
                    val source = response.body?.source() ?: throw IOException("媒体响应为空")
                    source.request(1024)
                    val prefix = source.readByteArray(minOf(1024L, source.buffer.size))
                    val text = prefix.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
                    if (text.startsWith("<!doctype html", true) || text.startsWith("<html", true)) {
                        throw IOException("片源返回了网页而非视频，请重新解析或切换播放源")
                    }
                    if (prefix.isEmpty()) throw IOException("媒体响应为空")
                    val mime = detectMediaMimeType(response.request.url.toString(), response.header("Content-Type"), prefix)
                        ?: stream.mimeType
                    val result = stream.copy(mimeType = mime, isM3u8 = mime == "application/x-mpegURL")
                    if (continuation.isActive) continuation.resume(result)
                }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}
