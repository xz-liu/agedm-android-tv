package io.agedm.tv.data

import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class LinkCastManager {
    private var server: LinkCastHttpServer? = null
    private var serverUrl: String? = null

    private val _incomingRoutes = MutableSharedFlow<AgeRoute>(extraBufferCapacity = 8)
    val incomingRoutes = _incomingRoutes.asSharedFlow()

    private val _status = MutableStateFlow("等待手机扫码后提交 AGE 链接")
    val status = _status.asStateFlow()

    @Synchronized
    fun ensureStarted(): String? {
        serverUrl?.let { return it }
        val localIp = resolveLocalIpv4() ?: run {
            _status.value = "未找到局域网 IPv4 地址，请确认电视已连接同一 Wi-Fi"
            return null
        }

        for (port in 8383..8390) {
            try {
                val candidate = LinkCastHttpServer(port, ::handleIncomingRoute)
                candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = candidate
                serverUrl = "http://$localIp:$port/"
                _status.value = "手机与电视连接同一局域网后，扫码提交 AGE 链接"
                return serverUrl
            } catch (_: IOException) {
                continue
            }
        }

        _status.value = "端口启动失败，无法创建投送入口"
        return null
    }

    private fun handleIncomingRoute(route: AgeRoute) {
        _status.value = "已收到：${AgeLinks.describe(route)}"
        _incomingRoutes.tryEmit(route)
    }

    private fun resolveLocalIpv4(): String? {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        interfaces.forEach { networkInterface ->
            if (!networkInterface.isUp || networkInterface.isLoopback) return@forEach
            Collections.list(networkInterface.inetAddresses).forEach { address ->
                if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }

    private class LinkCastHttpServer(
        port: Int,
        private val onRoute: (AgeRoute) -> Unit,
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            return when {
                session.method == Method.GET && session.uri == "/" -> html(rootPage())
                session.method == Method.POST && session.uri == "/submit" -> handleSubmit(session)
                else -> newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }

        private fun handleSubmit(session: IHTTPSession): Response {
            val files = HashMap<String, String>()
            return try {
                session.parseBody(files)
                val rawInput = session.parameters["link"]?.firstOrNull()
                val route = AgeLinks.parseInput(rawInput)
                if (route == null) {
                    html(
                        formPage(
                            title = "链接无效",
                            message = "只接受 AGE DM 详情页、播放页链接或纯动画 ID。",
                            error = true,
                        ),
                        NanoHTTPD.Response.Status.BAD_REQUEST,
                    )
                } else {
                    onRoute(route)
                    html(
                        formPage(
                            title = "已提交到电视",
                            message = "电视端会自动打开：${escape(AgeLinks.describe(route))}",
                            error = false,
                        ),
                    )
                }
            } catch (_: Throwable) {
                html(
                    formPage(
                        title = "提交失败",
                        message = "请求解析失败，请刷新页面后重试。",
                        error = true,
                    ),
                    NanoHTTPD.Response.Status.INTERNAL_ERROR,
                )
            }
        }

        private fun rootPage(): String {
            return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>AGE DM TV 投送</title>
                  <style>
                    body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, sans-serif; background: #08131f; color: #f5f7fa; }
                    main { max-width: 680px; margin: 0 auto; padding: 32px 20px 40px; }
                    .card { background: #10253a; border: 1px solid #355a77; border-radius: 18px; padding: 22px; box-sizing: border-box; }
                    h1 { margin: 0 0 12px; font-size: 24px; }
                    p { line-height: 1.7; color: #c7d5e2; }
                    textarea, input { width: 100%; box-sizing: border-box; margin: 12px 0 18px; padding: 14px 16px; border-radius: 12px; border: 1px solid #355a77; background: #17324d; color: #fff; font-size: 16px; }
                    button { width: 100%; padding: 14px 18px; border: 0; border-radius: 12px; background: #6ed9b8; color: #052016; font-size: 16px; font-weight: 700; }
                    .tips { margin-top: 18px; font-size: 14px; color: #9ab1c6; }
                  </style>
                </head>
                <body>
                  <main>
                    <div class="card">
                      <h1>把 AGE DM 链接投到电视</h1>
                      <p>支持动画详情页链接、播放页链接，或者直接输入纯数字动画 ID。电视和手机需要在同一局域网。</p>
                      <form action="/submit" method="post">
                        <input name="link" placeholder="例如 https://m.agedm.io/#/detail/20150086 或 20150086" />
                        <button type="submit">提交到电视</button>
                      </form>
                      <div class="tips">只接受 AGE DM 内容，不会跳转到外站。</div>
                    </div>
                  </main>
                </body>
                </html>
            """.trimIndent()
        }

        private fun formPage(title: String, message: String, error: Boolean): String {
            val accent = if (error) "#ff7a7a" else "#6ed9b8"
            return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>$title</title>
                  <style>
                    body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, sans-serif; background: #08131f; color: #f5f7fa; display: grid; place-items: center; min-height: 100vh; }
                    .card { width: min(640px, calc(100vw - 32px)); background: #10253a; border: 1px solid #355a77; border-radius: 18px; padding: 24px; box-sizing: border-box; }
                    h1 { margin: 0 0 12px; color: $accent; }
                    p { line-height: 1.7; color: #c7d5e2; }
                    a { color: #6ed9b8; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>${escape(title)}</h1>
                    <p>$message</p>
                    <p><a href="/">继续提交其他链接</a></p>
                  </div>
                </body>
                </html>
            """.trimIndent()
        }

        private fun html(
            body: String,
            status: NanoHTTPD.Response.Status = NanoHTTPD.Response.Status.OK,
        ): Response {
            return newFixedLengthResponse(status, "text/html; charset=utf-8", body)
        }

        private fun escape(text: String): String {
            return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
        }
    }
}
