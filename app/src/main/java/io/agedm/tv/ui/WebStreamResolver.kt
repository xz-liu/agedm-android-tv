package io.agedm.tv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.agedm.tv.data.AgeRepository
import io.agedm.tv.data.AnimeDetail
import io.agedm.tv.data.EpisodeItem
import io.agedm.tv.data.EpisodeSource
import io.agedm.tv.data.ResolvedStream
import io.agedm.tv.data.SourceResolver
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Resolves a stream without starting playback. Owned and released by its activity or preparation service. */
internal class WebStreamResolver(
    private val context: Context,
    private val host: ViewGroup?,
    private val scope: CoroutineScope,
    private val repository: AgeRepository,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var parserWebView: WebView? = null
    private val parserJavascriptBridge = ParserJavascriptBridge()
    private var parserPollJob: Job? = null
    private var parserRequestId = 0
    @Volatile private var parserRequest: ParserRequest? = null

    init { setupParserWebView() }

    fun release() {
        parserRequest?.deferred?.cancel()
        parserRequest = null
        parserPollJob?.cancel()
        parserWebView?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeJavascriptInterface(PARSER_BRIDGE_NAME)
            host?.removeView(this)
            destroy()
        }
        parserWebView = null
    }

    private data class ParserRequest(
        val id: Int,
        val pageUrl: String,
        val source: EpisodeSource,
        val episode: EpisodeItem,
        val pageHeaders: Map<String, String>,
        val streamHeaders: Map<String, String>,
        val deferred: CompletableDeferred<ResolvedStream>,
    )

    private inner class ParserJavascriptBridge {
        @JavascriptInterface
        fun reportMedia(requestId: Int, url: String?, pageUrl: String?) {
            if (url.isNullOrBlank()) return
            mainHandler.post {
                completeParserRequest(
                    url = url,
                    requestId = requestId,
                    resolvedPageUrl = pageUrl,
                    verified = true,
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupParserWebView() {
        parserWebView = WebView(context).apply {
            visibility = View.INVISIBLE
            alpha = 0f
            isFocusable = false
            isFocusableInTouchMode = false
            layoutParams = ViewGroup.LayoutParams(1, 1)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.userAgentString = PLAYER_USER_AGENT
            addJavascriptInterface(parserJavascriptBridge, PARSER_BRIDGE_NAME)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ) = super.shouldInterceptRequest(view, request).also {
                    val candidate = request?.url?.toString().orEmpty()
                    val isVerified = looksLikePlayableMediaUrl(candidate) || looksLikeDirectStreamRequest(request)
                    if (isVerified) {
                        val pageUrl = request?.requestHeaders?.get("Referer")
                        val activeRequestId = parserRequest?.id ?: return@also
                        mainHandler.post {
                            completeParserRequest(
                                url = candidate,
                                requestId = activeRequestId,
                                resolvedPageUrl = pageUrl,
                                verified = true,
                            )
                        }
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val request = parserRequest ?: return
                    if (url.isNullOrBlank() || url == "about:blank") return
                    injectParserScripts(request.id)
                    startParserPolling(request.id)
                }
            }
        }
        host?.addView(parserWebView)
    }

    suspend fun resolve(
        detail: AnimeDetail,
        source: EpisodeSource,
        episode: EpisodeItem,
    ): ResolvedStream {
        return when (source.resolver) {
            SourceResolver.AGE_PARSER -> resolveStreamWithAgeParser(detail, source, episode)
            SourceResolver.WEB_PAGE -> resolveStreamFromWebPage(source, episode)
        }
    }

    private suspend fun resolveStreamWithAgeParser(
        detail: AnimeDetail,
        source: EpisodeSource,
        episode: EpisodeItem,
    ): ResolvedStream {
        val parserUrl = repository.buildParserUrl(detail, source, episode)
        return resolveStreamViaWebView(
            pageUrl = parserUrl,
            source = source,
            episode = episode,
            pageHeaders = emptyMap(),
            streamHeaders = repository.buildPlaybackHeaders(parserUrl),
            fallback = {
                repository.resolveStream(detail, source, episode)
            },
        )
    }

    private suspend fun resolveStreamFromWebPage(
        source: EpisodeSource,
        episode: EpisodeItem,
    ): ResolvedStream {
        val pageUrl = episode.token
        if (pageUrl.isBlank()) {
            throw IOException("补充源播放页为空")
        }
        return resolveStreamViaWebView(
            pageUrl = pageUrl,
            source = source,
            episode = episode,
            pageHeaders = source.pageHeaders,
            streamHeaders = buildStreamHeaders(pageUrl, source),
            fallback = null,
        )
    }

    private suspend fun resolveStreamViaWebView(
        pageUrl: String,
        source: EpisodeSource,
        episode: EpisodeItem,
        pageHeaders: Map<String, String>,
        streamHeaders: Map<String, String>,
        fallback: (suspend () -> ResolvedStream)?,
    ): ResolvedStream {
        val request = ParserRequest(
            id = ++parserRequestId,
            pageUrl = pageUrl,
            source = source,
            episode = episode,
            pageHeaders = pageHeaders,
            streamHeaders = streamHeaders,
            deferred = CompletableDeferred(),
        )

        parserPollJob?.cancel()
        parserRequest?.deferred?.cancel()
        parserRequest = request

        parserWebView?.stopLoading()
        parserWebView?.loadUrl("about:blank")
        parserWebView?.settings?.userAgentString = pageHeaders["User-Agent"] ?: PLAYER_USER_AGENT
        if (pageHeaders.isEmpty()) {
            parserWebView?.loadUrl(pageUrl)
        } else {
            parserWebView?.loadUrl(pageUrl, pageHeaders)
        }

        return try {
            resolveWithTimeoutFallback(PARSER_TIMEOUT_MS, { request.deferred.await() }) {
                if (parserRequest === request) {
                    parserPollJob?.cancel()
                    parserRequest = null
                    parserWebView?.stopLoading()
                    parserWebView?.loadUrl("about:blank")
                }
                fallback?.invoke() ?: throw IOException("未能从页面提取真实视频地址")
            }
        } finally {
            // A cancelled old resolve must not stop the WebView of the next episode.
            if (parserRequest === request) {
                parserPollJob?.cancel()
                parserRequest = null
                parserWebView?.stopLoading()
                parserWebView?.loadUrl("about:blank")
            }
        }
    }

    private fun startParserPolling(requestId: Int) {
        parserPollJob?.cancel()
        parserPollJob = scope.launch {
            repeat(PARSER_POLL_RETRY_COUNT) {
                delay(PARSER_POLL_INTERVAL_MS)
                val candidate = readParserMediaUrl() ?: return@repeat
                if (completeParserRequest(candidate, requestId)) {
                    return@launch
                }
            }
        }
    }

    private fun completeParserRequest(url: String, requestId: Int? = null): Boolean {
        return completeParserRequest(url, requestId, resolvedPageUrl = null, verified = false)
    }

    private fun completeParserRequest(
        url: String,
        requestId: Int? = null,
        resolvedPageUrl: String? = null,
        verified: Boolean = false,
    ): Boolean {
        val activeRequest = parserRequest ?: return false
        if (requestId != null && requestId != activeRequest.id) return false

        val normalizedUrl = normalizeParserCandidate(url)
            ?.takeIf { verified || looksLikePlayableMediaUrl(it) }
            ?: return false

        if (activeRequest.deferred.isCompleted || activeRequest.deferred.isCancelled) {
            return false
        }

        val isM3u8 = normalizedUrl.contains(".m3u8", ignoreCase = true)
        val headerBaseUrl = when (activeRequest.source.resolver) {
            SourceResolver.WEB_PAGE -> normalizeParserCandidate(resolvedPageUrl) ?: activeRequest.pageUrl
            SourceResolver.AGE_PARSER -> activeRequest.pageUrl
        }
        val headers = when (activeRequest.source.resolver) {
            SourceResolver.WEB_PAGE -> buildStreamHeaders(headerBaseUrl, activeRequest.source)
            SourceResolver.AGE_PARSER -> activeRequest.streamHeaders
        }
        activeRequest.deferred.complete(
            ResolvedStream(
                streamUrl = normalizedUrl,
                parserUrl = headerBaseUrl,
                sourceKey = activeRequest.source.key,
                sourceLabel = activeRequest.source.label,
                episode = activeRequest.episode,
                isM3u8 = isM3u8,
                mimeType = repository.inferMimeType(normalizedUrl, isM3u8),
                headers = headers,
            ),
        )
        parserPollJob?.cancel()
        return true
    }

    private fun buildStreamHeaders(pageUrl: String, source: EpisodeSource): Map<String, String> {
        return repository.buildPlaybackHeaders(pageUrl)
            .toMutableMap()
            .apply {
                source.pageHeaders["User-Agent"]?.let { put("User-Agent", it) }
            }
    }

    private suspend fun readParserMediaUrl(): String? {
        val webView = parserWebView ?: return null
        val result = CompletableDeferred<String?>()
        webView.evaluateJavascript(PARSER_PROBE_SCRIPT) { value ->
            result.complete(decodeJavascriptValue(value))
        }
        return normalizeParserCandidate(result.await())
    }

    private fun decodeJavascriptValue(rawValue: String?): String? {
        if (rawValue.isNullOrBlank() || rawValue == "null") return null
        return try {
            JSONObject("""{"value":$rawValue}""").getString("value")
        } catch (_: Throwable) {
            rawValue.removePrefix("\"").removeSuffix("\"")
                .replace("\\/", "/")
                .replace("\\u0026", "&")
        }.ifBlank { null }
    }

    private fun looksLikePlayableMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (
            lower.contains("artplayer") ||
            lower.contains("hls.min.js") ||
            lower.contains("flv.min.js") ||
            lower.contains("global.min.js") ||
            lower.contains("play.min.js") ||
            lower.contains("adposter") ||
            lower.contains("/player/?url=") ||
            lower.endsWith(".js") ||
            lower.endsWith(".css") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".gif") ||
            lower.endsWith(".svg")
        ) {
            return false
        }

        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".flv") ||
            lower.contains(".m4s") ||
            lower.contains("bilivideo.com/upgcxcode/") ||
            lower.contains("akamaized.net/obj/")
    }

    private fun looksLikeDirectStreamRequest(request: WebResourceRequest?): Boolean {
        val candidate = request?.url?.toString().orEmpty().lowercase()
        val headers = request?.requestHeaders ?: return false
        val range = headers["Range"] ?: headers["range"] ?: return false
        if (!range.startsWith("bytes=")) return false
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) return false
        if (
            candidate.endsWith(".js") ||
            candidate.endsWith(".css") ||
            candidate.endsWith(".html") ||
            candidate.endsWith(".json") ||
            candidate.endsWith(".jpg") ||
            candidate.endsWith(".jpeg") ||
            candidate.endsWith(".png") ||
            candidate.endsWith(".gif") ||
            candidate.endsWith(".svg") ||
            candidate.endsWith(".woff") ||
            candidate.endsWith(".woff2") ||
            candidate.endsWith(".wasm") ||
            candidate.endsWith(".ts") ||
            candidate.endsWith(".m4s") ||
            candidate.endsWith(".aac") ||
            candidate.endsWith(".vtt")
        ) {
            return false
        }
        return true
    }

    private fun normalizeParserCandidate(rawUrl: String?): String? {
        val value = rawUrl
            ?.trim()
            ?.replace("&amp;", "&")
            ?.ifBlank { null }
            ?: return null
        val normalized = when {
            value.startsWith("//") -> "https:$value"
            else -> value
        }
        return normalized.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun injectParserScripts(requestId: Int) {
        parserWebView?.evaluateJavascript(buildParserInjectionScript(requestId), null)
    }

    private fun buildParserInjectionScript(requestId: Int): String {
        return """
            (function() {
              const REQUEST_ID = $requestId;
              const BRIDGE_NAME = '${PARSER_BRIDGE_NAME}';
              if (!window[BRIDGE_NAME]) return;
              if (window.__agedmParserInjectionId === REQUEST_ID) return;
              window.__agedmParserInjectionId = REQUEST_ID;

              function report(url, pageUrl) {
                try {
                  if (!url) return;
                  window[BRIDGE_NAME].reportMedia(REQUEST_ID, String(url), String(pageUrl || window.location.href));
                } catch (e) {}
              }

              function cleanUrl(value) {
                if (!value) return '';
                return String(value).trim().replace(/&amp;/g, '&');
              }

              function shouldIgnore(url) {
                if (!url) return true;
                const lower = url.toLowerCase();
                return lower.startsWith('blob:') ||
                  lower.includes('googleads') ||
                  lower.includes('googlesyndication') ||
                  lower.includes('doubleclick') ||
                  lower.endsWith('.js') ||
                  lower.endsWith('.css') ||
                  lower.endsWith('.jpg') ||
                  lower.endsWith('.jpeg') ||
                  lower.endsWith('.png') ||
                  lower.endsWith('.gif') ||
                  lower.endsWith('.svg');
              }

              function maybeReport(url, targetWindow) {
                const cleaned = cleanUrl(url);
                if (!cleaned || shouldIgnore(cleaned)) return false;
                const absolute = cleaned.startsWith('//') ? 'https:' + cleaned : cleaned;
                if (!/^https?:\/\//i.test(absolute)) return false;
                report(absolute, targetWindow && targetWindow.location ? targetWindow.location.href : window.location.href);
                return true;
              }

              function processVideoElement(video, targetWindow) {
                if (!video) return false;
                if (maybeReport(video.currentSrc || video.src || video.getAttribute('src'), targetWindow)) return true;
                const sources = video.getElementsByTagName('source');
                for (let i = 0; i < sources.length; i += 1) {
                  if (maybeReport(sources[i].getAttribute('src'), targetWindow)) return true;
                }
                return false;
              }

              function scanDocument(doc, targetWindow) {
                if (!doc) return false;
                try {
                  const videos = doc.querySelectorAll('video');
                  for (let i = 0; i < videos.length; i += 1) {
                    if (processVideoElement(videos[i], targetWindow)) return true;
                  }
                } catch (e) {}

                try {
                  const tagged = doc.querySelector('[data-video="src"]');
                  if (tagged && maybeReport(tagged.textContent || '', targetWindow)) return true;
                } catch (e) {}

                try {
                  if (targetWindow.art && targetWindow.art.template && targetWindow.art.template.${'$'}video) {
                    if (maybeReport(targetWindow.art.template.${'$'}video.currentSrc || targetWindow.art.template.${'$'}video.src || '', targetWindow)) {
                      return true;
                    }
                  }
                } catch (e) {}

                try {
                  if (targetWindow.art && targetWindow.art.option && targetWindow.art.option.url) {
                    if (maybeReport(targetWindow.art.option.url, targetWindow)) return true;
                  }
                } catch (e) {}

                return false;
              }

              function installNetworkHooks(targetWindow) {
                if (!targetWindow || targetWindow.__agedmNetworkHooksId === REQUEST_ID) return;
                targetWindow.__agedmNetworkHooksId = REQUEST_ID;

                try {
                  if (targetWindow.Response && targetWindow.Response.prototype && targetWindow.Response.prototype.text) {
                    const originalText = targetWindow.Response.prototype.text;
                    targetWindow.Response.prototype.text = function() {
                      return originalText.apply(this, arguments).then((text) => {
                        try {
                          if (String(text || '').trim().startsWith('#EXTM3U')) {
                            maybeReport(this.url || '', targetWindow);
                          }
                        } catch (e) {}
                        return text;
                      });
                    };
                  }
                } catch (e) {}

                try {
                  if (targetWindow.XMLHttpRequest && targetWindow.XMLHttpRequest.prototype && targetWindow.XMLHttpRequest.prototype.open) {
                    const originalOpen = targetWindow.XMLHttpRequest.prototype.open;
                    targetWindow.XMLHttpRequest.prototype.open = function() {
                      const args = arguments;
                      this.addEventListener('load', function() {
                        try {
                          if (String(this.responseText || '').trim().startsWith('#EXTM3U')) {
                            maybeReport(args[1] || '', targetWindow);
                          }
                        } catch (e) {}
                      });
                      return originalOpen.apply(this, args);
                    };
                  }
                } catch (e) {}
              }

              function installVideoObserver(doc, targetWindow) {
                if (!doc || doc.__agedmVideoObserverId === REQUEST_ID) return;
                doc.__agedmVideoObserverId = REQUEST_ID;

                const observer = new MutationObserver((mutations) => {
                  for (let i = 0; i < mutations.length; i += 1) {
                    const mutation = mutations[i];
                    if (mutation.type === 'attributes' && mutation.target && mutation.target.nodeName === 'VIDEO') {
                      if (processVideoElement(mutation.target, targetWindow)) return;
                    }
                    for (let j = 0; j < mutation.addedNodes.length; j += 1) {
                      const node = mutation.addedNodes[j];
                      if (!node) continue;
                      if (node.nodeName === 'VIDEO' && processVideoElement(node, targetWindow)) return;
                      if (node.querySelectorAll) {
                        const nested = node.querySelectorAll('video');
                        for (let k = 0; k < nested.length; k += 1) {
                          if (processVideoElement(nested[k], targetWindow)) return;
                        }
                      }
                    }
                  }
                });

                const target = doc.body || doc.documentElement;
                if (target) {
                  observer.observe(target, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['src']
                  });
                }
              }

              function installFrameObservers(doc) {
                if (!doc || doc.__agedmFrameObserverId === REQUEST_ID) return;
                doc.__agedmFrameObserverId = REQUEST_ID;

                function attachIframe(iframe) {
                  if (!iframe) return;
                  const loadHandler = function() {
                    try {
                      installIntoWindow(iframe.contentWindow);
                    } catch (e) {}
                  };
                  iframe.addEventListener('load', loadHandler);
                  loadHandler();
                }

                try {
                  const existing = doc.querySelectorAll('iframe');
                  for (let i = 0; i < existing.length; i += 1) {
                    attachIframe(existing[i]);
                  }
                } catch (e) {}

                const observer = new MutationObserver((mutations) => {
                  for (let i = 0; i < mutations.length; i += 1) {
                    const mutation = mutations[i];
                    for (let j = 0; j < mutation.addedNodes.length; j += 1) {
                      const node = mutation.addedNodes[j];
                      if (!node) continue;
                      if (node.nodeName === 'IFRAME') {
                        attachIframe(node);
                      }
                      if (node.querySelectorAll) {
                        const nested = node.querySelectorAll('iframe');
                        for (let k = 0; k < nested.length; k += 1) {
                          attachIframe(nested[k]);
                        }
                      }
                    }
                  }
                });

                const target = doc.body || doc.documentElement;
                if (target) {
                  observer.observe(target, {
                    childList: true,
                    subtree: true
                  });
                }
              }

              function installIntoWindow(targetWindow) {
                if (!targetWindow || targetWindow.__agedmParserWindowId === REQUEST_ID) return;
                targetWindow.__agedmParserWindowId = REQUEST_ID;
                installNetworkHooks(targetWindow);
                try {
                  const doc = targetWindow.document;
                  if (!doc) return;
                  installVideoObserver(doc, targetWindow);
                  installFrameObservers(doc);
                  scanDocument(doc, targetWindow);
                } catch (e) {}
              }

              installIntoWindow(window);

              const timer = window.setInterval(function() {
                try {
                  scanDocument(document, window);
                } catch (e) {}
                try {
                  const frames = document.querySelectorAll('iframe');
                  for (let i = 0; i < frames.length; i += 1) {
                    try {
                      installIntoWindow(frames[i].contentWindow);
                    } catch (e) {}
                  }
                } catch (e) {}
              }, 1000);

              window.setTimeout(function() {
                window.clearInterval(timer);
              }, ${PARSER_TIMEOUT_MS});
            })();
        """.trimIndent()
    }

    companion object {
        const val PLAYER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
        private const val PARSER_TIMEOUT_MS = 20_000L
        private const val PARSER_POLL_INTERVAL_MS = 500L
        private const val PARSER_POLL_RETRY_COUNT = 24
        private const val PARSER_BRIDGE_NAME = "AgeTvParserBridge"
        private const val PARSER_PROBE_SCRIPT =
            """
            (function() {
              var values = [];
              var video = document.querySelector('video');
              if (video) {
                values.push(video.currentSrc || '');
                values.push(video.src || '');
              }
              var info = document.querySelector('[data-video="src"]');
              if (info) {
                values.push(info.textContent || '');
              }
              if (window.art && window.art.template && window.art.template.${'$'}video) {
                values.push(window.art.template.${'$'}video.currentSrc || '');
                values.push(window.art.template.${'$'}video.src || '');
              }
              if (window.art && window.art.option && window.art.option.url) {
                values.push(window.art.option.url || '');
              }
              for (var i = 0; i < values.length; i++) {
                var value = String(values[i] || '').trim();
                if (value && /^https?:\/\//i.test(value) && value.indexOf('blob:') !== 0) {
                  return value;
                }
              }
              return '';
            })();
            """

    }
}
