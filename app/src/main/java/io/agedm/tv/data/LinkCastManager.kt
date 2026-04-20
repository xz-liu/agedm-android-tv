package io.agedm.tv.data

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

data class MirrorState(
    val query: String = "",
    val results: List<MirrorItem> = emptyList(),
)

data class MirrorItem(
    val animeId: Long,
    val title: String,
    val badge: String,
    val cover: String = "",
)

class LinkCastManager(
    private val repository: AgeRepository,
    private val bangumiAccountService: BangumiAccountService,
) {
    private var server: LinkCastHttpServer? = null
    private var serverUrl: String? = null

    private val _incomingRoutes = MutableSharedFlow<AgeRoute>(extraBufferCapacity = 8)
    val incomingRoutes = _incomingRoutes.asSharedFlow()

    private val _pendingRoute = MutableStateFlow<AgeRoute?>(null)
    val pendingRoute = _pendingRoute.asStateFlow()

    private val _status = MutableStateFlow("等待手机扫码后搜索或提交 AGE 链接")
    val status = _status.asStateFlow()

    private val _mirrorState = MutableStateFlow(MirrorState())
    val mirrorState = _mirrorState.asStateFlow()

    fun consumePendingRoute(): AgeRoute? {
        val route = _pendingRoute.value
        if (route != null) _pendingRoute.value = null
        return route
    }

    @Synchronized
    fun ensureStarted(): String? {
        serverUrl?.let { return it }
        val localIp = resolveLocalIpv4() ?: run {
            _status.value = "未找到局域网 IPv4 地址，请确认电视已连接同一 Wi-Fi"
            return null
        }

        for (port in 8383..8390) {
            try {
                val candidate = LinkCastHttpServer(
                    port = port,
                    onRoute = ::handleIncomingRoute,
                    onMirrorUpdate = ::handleMirrorUpdate,
                    repository = repository,
                    bangumiAccountService = bangumiAccountService,
                )
                candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = candidate
                serverUrl = "http://$localIp:$port/"
                _status.value = "手机与电视连接同一局域网后，扫码搜索或提交 AGE 链接"
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
        _pendingRoute.value = route
        _incomingRoutes.tryEmit(route)
    }

    private fun handleMirrorUpdate(state: MirrorState) {
        _mirrorState.value = state
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
        private val onMirrorUpdate: (MirrorState) -> Unit,
        private val repository: AgeRepository,
        private val bangumiAccountService: BangumiAccountService,
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            return when {
                session.method == Method.GET && session.uri == "/" -> html(rootPage())
                session.method == Method.GET && session.uri == "/bgm/login" -> handleBangumiLoginPage()
                session.method == Method.GET && session.uri == "/bgm/captcha" -> handleBangumiCaptcha(session)
                session.method == Method.GET && session.uri == "/api/search" -> handleSearch(session)
                session.method == Method.POST && session.uri == "/api/bgm/login" -> handleBangumiLoginSubmit(session)
                session.method == Method.POST && session.uri == "/api/mirror" -> handleMirror(session)
                session.method == Method.POST && session.uri == "/submit" -> handleSubmit(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }

        private fun handleBangumiLoginPage(): Response {
            return try {
                val loginPage = runBlocking { bangumiAccountService.prepareLoginPage() }
                html(bangumiLoginPage(loginPage.sessionId))
            } catch (error: Throwable) {
                html(
                    """
                    <!doctype html>
                    <html lang="zh-CN">
                    <meta charset="utf-8"/>
                    <meta name="viewport" content="width=device-width,initial-scale=1"/>
                    <body style="font-family:-apple-system,BlinkMacSystemFont,sans-serif;background:#08131f;color:#f5f7fa;padding:24px">
                      <h2>Bangumi 登录页暂时不可用</h2>
                      <p>${escapeHtml(error.message.orEmpty().ifBlank { "请稍后重试" })}</p>
                    </body>
                    </html>
                    """.trimIndent(),
                    Response.Status.INTERNAL_ERROR,
                )
            }
        }

        private fun handleBangumiCaptcha(session: IHTTPSession): Response {
            val sessionId = session.parameters["sid"]?.firstOrNull().orEmpty()
            if (sessionId.isBlank()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing sid")
            }
            return try {
                val requestToken = session.parameters["t"]?.firstOrNull()?.toLongOrNull() ?: System.currentTimeMillis()
                val response = runBlocking {
                    bangumiAccountService.loadCaptcha(
                        sessionId = sessionId,
                        requestToken = requestToken,
                    )
                }
                newFixedLengthResponse(
                    Response.Status.OK,
                    response.contentType.ifBlank { "image/png" },
                    ByteArrayInputStream(response.bytes),
                    response.bytes.size.toLong(),
                ).apply {
                    addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                    addHeader("Pragma", "no-cache")
                }
            } catch (_: Throwable) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Captcha Load Failed")
            }
        }

        private fun handleBangumiLoginSubmit(session: IHTTPSession): Response {
            val files = HashMap<String, String>()
            return try {
                session.parseBody(files)
                val sid = session.parameters["sid"]?.firstOrNull().orEmpty()
                val username = session.parameters["username"]?.firstOrNull().orEmpty()
                val password = session.parameters["password"]?.firstOrNull().orEmpty()
                val captcha = session.parameters["captcha"]?.firstOrNull().orEmpty()
                val result = runBlocking {
                    bangumiAccountService.submitLogin(
                        sessionId = sid,
                        username = username,
                        password = password,
                        captcha = captcha,
                    )
                }
                val body = buildString {
                    append("{\"ok\":")
                    append(if (result.success) "true" else "false")
                    append(",\"message\":\"")
                    append(escapeJson(result.message))
                    append("\"")
                    result.account?.let { account ->
                        append(",\"username\":\"")
                        append(escapeJson(account.username))
                        append("\",\"displayName\":\"")
                        append(escapeJson(account.displayName))
                        append("\"")
                    }
                    append("}")
                }
                json(body, if (result.success) Response.Status.OK else Response.Status.BAD_REQUEST)
            } catch (error: Throwable) {
                json(
                    "{\"ok\":false,\"message\":\"${escapeJson(error.message.orEmpty().ifBlank { "登录失败" })}\"}",
                    Response.Status.INTERNAL_ERROR,
                )
            }
        }

        private fun handleSearch(session: IHTTPSession): Response {
            val q = session.parameters["q"]?.firstOrNull()?.trim().orEmpty()
            if (q.isBlank()) return json("[]")
            return try {
                val results = runBlocking { repository.search(q, 1, 20) }
                val array = buildString {
                    append('[')
                    results.items.forEachIndexed { i, card ->
                        if (i > 0) append(',')
                        append("{\"id\":")
                        append(card.animeId)
                        append(",\"title\":\"")
                        append(escapeJson(card.title))
                        append("\",\"badge\":\"")
                        append(escapeJson(card.badge))
                        append("\",\"cover\":\"")
                        append(escapeJson(card.cover))
                        append("\"}")
                    }
                    append(']')
                }
                json(array)
            } catch (_: Throwable) {
                json("[]")
            }
        }

        private fun handleMirror(session: IHTTPSession): Response {
            return try {
                val len = session.headers["content-length"]?.toIntOrNull() ?: 0
                if (len <= 0) return json("{\"ok\":true}")
                val bytes = ByteArray(len)
                var offset = 0
                while (offset < len) {
                    val n = session.inputStream.read(bytes, offset, len - offset)
                    if (n == -1) break
                    offset += n
                }
                val body = String(bytes, 0, offset, Charsets.UTF_8)
                val obj = JSONObject(body)
                val query = obj.optString("query", "")
                val arr = obj.optJSONArray("results") ?: JSONArray()
                val items = buildList {
                    for (i in 0 until arr.length()) {
                        val r = arr.getJSONObject(i)
                        add(MirrorItem(
                            animeId = r.optLong("id"),
                            title = r.optString("title", ""),
                            badge = r.optString("badge", ""),
                            cover = r.optString("cover", ""),
                        ))
                    }
                }
                onMirrorUpdate(MirrorState(query, items))
                json("{\"ok\":true}")
            } catch (_: Throwable) {
                json("{\"ok\":true}")
            }
        }

        private fun handleSubmit(session: IHTTPSession): Response {
            val files = HashMap<String, String>()
            return try {
                session.parseBody(files)
                val rawInput = session.parameters["id"]?.firstOrNull()
                    ?: session.parameters["link"]?.firstOrNull()
                val route = AgeLinks.parseInput(rawInput)
                if (route == null) {
                    json("{\"ok\":false,\"error\":\"无效的动画 ID\"}", Response.Status.BAD_REQUEST)
                } else {
                    onRoute(route)
                    json("{\"ok\":true,\"title\":\"${escapeJson(AgeLinks.describe(route))}\"}")
                }
            } catch (_: Throwable) {
                json("{\"ok\":false,\"error\":\"请求解析失败\"}", Response.Status.INTERNAL_ERROR)
            }
        }

        private fun rootPage(): String {
            return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8"/>
                  <meta name="viewport" content="width=device-width,initial-scale=1"/>
                  <title>AGE DM TV 投送</title>
                  <style>
                    *{box-sizing:border-box;margin:0;padding:0}
                    body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;background:#08131f;color:#f5f7fa;min-height:100vh}
                    header{position:sticky;top:0;z-index:10;background:#08131f;padding:14px 16px 10px;border-bottom:1px solid #1b3549}
                    header h1{font-size:18px;font-weight:700;margin-bottom:10px;color:#6ed9b8}
                    #searchInput{width:100%;padding:10px 14px;border-radius:10px;border:1px solid #355a77;background:#0f2236;color:#fff;font-size:16px;outline:none}
                    #searchInput:focus{border-color:#6ed9b8}
                    #list{padding:8px 12px 32px}
                    .item{display:flex;align-items:center;gap:12px;padding:10px;border-radius:12px;margin-bottom:6px;background:#0f2236;cursor:pointer;-webkit-tap-highlight-color:transparent;transition:background .15s}
                    .item:active{background:#1a3d52}
                    .item img{width:60px;height:84px;border-radius:6px;object-fit:cover;flex-shrink:0;background:#1b3549}
                    .item-info{flex:1;min-width:0}
                    .item-title{font-size:15px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
                    .item-badge{margin-top:4px;font-size:12px;color:#6ed9b8}
                    #empty{text-align:center;padding:60px 20px;color:#4a6b84;font-size:15px;display:none}
                    #spinner{text-align:center;padding:40px;color:#4a6b84;display:none}
                    .toast{position:fixed;bottom:24px;left:50%;transform:translateX(-50%);background:#1a3d52;border:1px solid #6ed9b8;color:#6ed9b8;padding:10px 20px;border-radius:10px;font-size:14px;font-weight:600;opacity:0;transition:opacity .25s;pointer-events:none;white-space:nowrap}
                    .toast.show{opacity:1}
                  </style>
                </head>
                <body>
                  <header>
                    <h1>投送到电视</h1>
                    <input id="searchInput" type="search" placeholder="搜索动画名称…" autocomplete="off" autocorrect="off" spellcheck="false"/>
                  </header>
                  <div id="list"></div>
                  <div id="empty">没有找到相关动画</div>
                  <div id="spinner">搜索中…</div>
                  <div class="toast" id="toast"></div>
                  <script>
                    var timer=null,lastQ='';
                    var input=document.getElementById('searchInput');
                    var list=document.getElementById('list');
                    var empty=document.getElementById('empty');
                    var spinner=document.getElementById('spinner');
                    var toast=document.getElementById('toast');
                    function doSearch(q){
                      if(!q){
                        list.innerHTML='';empty.style.display='none';spinner.style.display='none';
                        mirror('', []);
                        return;
                      }
                      spinner.style.display='block';list.innerHTML='';empty.style.display='none';
                      fetch('/api/search?q='+encodeURIComponent(q))
                        .then(function(r){return r.json()})
                        .then(function(items){
                          spinner.style.display='none';
                          if(!items||items.length===0){empty.style.display='block';mirror(q,[]);return}
                          list.innerHTML=items.map(function(it){
                            return '<div class="item" onclick="send('+it.id+',\''+ej(it.title)+'\')">'+
                              '<img src="'+ea(it.cover)+'" loading="lazy" onerror="this.style.visibility=\'hidden\'"/>'+
                              '<div class="item-info">'+
                                '<div class="item-title">'+eh(it.title)+'</div>'+
                                (it.badge?'<div class="item-badge">'+eh(it.badge)+'</div>':'')+
                              '</div></div>'
                          }).join('');
                          mirror(q, items);
                        })
                        .catch(function(){spinner.style.display='none';empty.style.display='block'});
                    }
                    function send(id,title){
                      var fd=new FormData();fd.append('id',id);
                      fetch('/submit',{method:'POST',body:fd})
                        .then(function(r){return r.json()})
                        .then(function(res){showToast(res.ok?('已投送：'+title):'投送失败')})
                        .catch(function(){showToast('投送失败')});
                    }
                    function mirror(q,items){
                      fetch('/api/mirror',{
                        method:'POST',
                        headers:{'Content-Type':'application/json'},
                        body:JSON.stringify({query:q,results:items||[]})
                      }).catch(function(){});
                    }
                    function showToast(msg){
                      toast.textContent=msg;toast.classList.add('show');
                      setTimeout(function(){toast.classList.remove('show')},2500);
                    }
                    function eh(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}
                    function ea(s){return String(s).replace(/"/g,'&quot;')}
                    function ej(s){return String(s).replace(/\\/g,'\\\\').replace(/'/g,"\\'")}
                    input.addEventListener('input',function(){
                      var q=input.value.trim();
                      clearTimeout(timer);
                      timer=setTimeout(function(){if(q!==lastQ){lastQ=q;doSearch(q)}},400);
                    });
                    input.focus();
                  </script>
                </body>
                </html>
            """.trimIndent()
        }

        private fun bangumiLoginPage(sessionId: String): String {
            return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8"/>
                  <meta name="viewport" content="width=device-width,initial-scale=1"/>
                  <title>Bangumi 登录</title>
                  <style>
                    *{box-sizing:border-box}
                    body{margin:0;font-family:-apple-system,BlinkMacSystemFont,sans-serif;background:#08131f;color:#f5f7fa;min-height:100vh;padding:18px}
                    .card{max-width:480px;margin:0 auto;background:#0f2236;border:1px solid #1b3549;border-radius:18px;padding:18px}
                    h1{margin:0 0 10px;font-size:22px;color:#6ed9b8}
                    p{margin:0 0 14px;color:#9bb7ca;line-height:1.5}
                    label{display:block;margin:14px 0 6px;font-size:14px;color:#d6e4ee}
                    input{width:100%;padding:12px 14px;border-radius:12px;border:1px solid #355a77;background:#08131f;color:#fff;font-size:16px}
                    input:focus{outline:none;border-color:#6ed9b8}
                    .captcha-row{display:flex;gap:10px;align-items:center}
                    .captcha-row img{width:128px;height:48px;border-radius:10px;background:#fff;object-fit:cover}
                    .captcha-row button{flex-shrink:0}
                    button{width:100%;margin-top:18px;padding:13px 16px;border:0;border-radius:12px;background:#6ed9b8;color:#052016;font-size:16px;font-weight:700}
                    button.secondary{width:auto;margin-top:0;padding:10px 12px;background:#173149;color:#d6e4ee}
                    #result{margin-top:14px;line-height:1.5}
                    .ok{color:#6ed9b8}
                    .err{color:#ff8585}
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>登录 Bangumi</h1>
                    <p>输入用户名、密码和验证码。登录成功后，电视端会自动同步收藏状态。</p>
                    <input type="hidden" id="sid" value="${escapeHtml(sessionId)}"/>
                    <label for="username">用户名 / Email</label>
                    <input id="username" autocomplete="username" autocapitalize="off" autocorrect="off" spellcheck="false"/>
                    <label for="password">密码</label>
                    <input id="password" type="password" autocomplete="current-password"/>
                    <label for="captcha">验证码</label>
                    <div class="captcha-row">
                      <input id="captcha" autocomplete="one-time-code" autocapitalize="off" autocorrect="off" spellcheck="false" inputmode="latin" maxlength="5" pattern="[A-Za-z0-9]{5}" placeholder="输入 5 位数字/字母验证码"/>
                      <img id="captchaImg" alt="captcha" src="/bgm/captcha?sid=${escapeHtml(sessionId)}&t=${System.currentTimeMillis()}"/>
                      <button type="button" class="secondary" onclick="refreshCaptcha()">刷新</button>
                    </div>
                    <button type="button" onclick="submitLogin()">登录到电视</button>
                    <div id="result"></div>
                  </div>
                  <script>
                    function refreshCaptcha(){
                      var sid=document.getElementById('sid').value;
                      document.getElementById('captchaImg').src='/bgm/captcha?sid='+encodeURIComponent(sid)+'&t='+Date.now();
                    }
                    function setResult(msg, ok){
                      var el=document.getElementById('result');
                      el.className=ok?'ok':'err';
                      el.textContent=msg;
                    }
                    function submitLogin(){
                      var sid=document.getElementById('sid').value;
                      var username=document.getElementById('username').value.trim();
                      var password=document.getElementById('password').value;
                      var captcha=document.getElementById('captcha').value.trim();
                      if(!username||!password||!captcha){setResult('请完整填写用户名、密码和验证码',false);return;}
                      if(!/^[0-9A-Za-z]{5}$/.test(captcha)){setResult('验证码必须是 5 位数字或字母',false);return;}
                      var fd=new FormData();
                      fd.append('sid',sid);
                      fd.append('username',username);
                      fd.append('password',password);
                      fd.append('captcha',captcha);
                      setResult('正在登录...', true);
                      fetch('/api/bgm/login',{method:'POST',body:fd})
                        .then(function(r){return r.json().then(function(data){return {ok:r.ok,data:data};});})
                        .then(function(payload){
                          if(payload.ok&&payload.data&&payload.data.ok){
                            var name=payload.data.displayName||payload.data.username||'';
                            setResult('登录成功：'+name+'。现在可以回到电视继续操作。',true);
                          }else{
                            setResult((payload.data&&payload.data.message)||'登录失败，请重试',false);
                            refreshCaptcha();
                          }
                        })
                        .catch(function(){
                          setResult('登录失败，请检查网络后重试',false);
                          refreshCaptcha();
                        });
                    }
                  </script>
                </body>
                </html>
            """.trimIndent()
        }

        private fun html(body: String, status: Response.Status = Response.Status.OK): Response {
            return newFixedLengthResponse(status, "text/html; charset=utf-8", body)
        }

        private fun json(body: String, status: Response.Status = Response.Status.OK): Response {
            return newFixedLengthResponse(status, "application/json; charset=utf-8", body)
        }

        private fun escapeJson(text: String): String {
            return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r")
        }

        private fun escapeHtml(text: String): String {
            return text
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
        }
    }
}
