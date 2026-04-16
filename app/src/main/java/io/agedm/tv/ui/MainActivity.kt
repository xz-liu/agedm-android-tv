package io.agedm.tv.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.agedm.tv.R
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.data.AgeLinks
import io.agedm.tv.data.AgeRoute
import io.agedm.tv.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app: AgeTvApplication
        get() = application as AgeTvApplication

    private var currentRoute: AgeRoute = AgeRoute.Home
    private var overlayJob: Job? = null
    private var lastPlayLaunchUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupChrome()
        setupWebView()
        collectIncomingRoutes()

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            if (!handleIntent(intent)) {
                loadRoute(AgeRoute.Home)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.webView.apply {
            stopLoading()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && binding.webView.hasFocus()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    dispatchWebMove("left")
                    return true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    dispatchWebMove("right")
                    return true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    dispatchWebMove("down")
                    return true
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    dispatchWebMove("up") { binding.backButton.requestFocus() }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    binding.webView.evaluateJavascript(WebFocusScripts.ACTIVATE_SCRIPT, null)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setupChrome() {
        binding.backButton.setOnClickListener {
            when {
                binding.webView.canGoBack() -> binding.webView.goBack()
                currentRoute != AgeRoute.Home -> loadRoute(AgeRoute.Home)
                else -> finish()
            }
        }

        binding.homeButton.setOnClickListener {
            loadRoute(AgeRoute.Home)
        }

        binding.castButton.setOnClickListener {
            startActivity(Intent(this, LinkCastActivity::class.java))
        }

        binding.continueButton.setOnClickListener {
            val detailRoute = currentRoute as? AgeRoute.Detail ?: return@setOnClickListener
            val record = app.playbackStore.getRecord(detailRoute.animeId) ?: return@setOnClickListener
            launchPlayer(
                route = AgeRoute.Play(
                    animeId = record.animeId,
                    sourceIndex = 1,
                    episodeIndex = record.episodeIndex + 1,
                ),
                preferredSourceKey = record.sourceKey,
                resumePositionMs = record.positionMs,
                preferResumePrompt = false,
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = AGE_USER_AGENT
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    binding.pageTitle.text = title
                }
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString().orEmpty()
                if (request?.isForMainFrame != true) return false
                if (!AgeLinks.isAllowedTopLevelUrl(url)) {
                    showOverlayMessage("仅支持 AGE DM 页面")
                    return true
                }
                val route = AgeLinks.parseCurrentUrl(url)
                if (route is AgeRoute.Play) {
                    launchPlayer(route)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.webView.evaluateJavascript(WebFocusScripts.installScript, null)
                onRouteChanged(url)
            }
        }

        binding.webView.addJavascriptInterface(WebBridge(), "AgeBridge")
    }

    private fun collectIncomingRoutes() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.linkCastManager.incomingRoutes.collectLatest { route ->
                    showOverlayMessage("已收到手机投送：${AgeLinks.describe(route)}")
                    openRoute(route)
                }
            }
        }
    }

    private fun handleIntent(intent: Intent?): Boolean {
        val routeString = intent?.getStringExtra(EXTRA_OPEN_ROUTE)
        if (routeString.isNullOrBlank()) return false
        val route = AgeLinks.parseInput(routeString) ?: return false
        openRoute(route)
        return true
    }

    private fun loadRoute(route: AgeRoute) {
        currentRoute = route
        lastPlayLaunchUrl = null
        updateChromeForRoute(route)
        binding.webView.loadUrl(AgeLinks.buildWebUrl(route))
        binding.webView.requestFocus()
    }

    private fun openRoute(route: AgeRoute) {
        when (route) {
            is AgeRoute.Play -> launchPlayer(route)
            else -> loadRoute(route)
        }
    }

    private fun launchPlayer(
        route: AgeRoute.Play,
        preferredSourceKey: String? = null,
        resumePositionMs: Long = 0L,
        preferResumePrompt: Boolean = true,
    ) {
        lastPlayLaunchUrl = AgeLinks.buildWebUrl(route)
        startActivity(
            PlayerActivity.createIntent(
                context = this,
                animeId = route.animeId,
                sourceIndex = route.sourceIndex,
                episodeIndex = route.episodeIndex,
                preferredSourceKey = preferredSourceKey,
                resumePositionMs = resumePositionMs,
                preferResumePrompt = preferResumePrompt,
            ),
        )
    }

    private fun onRouteChanged(url: String?) {
        val route = AgeLinks.parseCurrentUrl(url) ?: return
        currentRoute = route
        updateChromeForRoute(route)
        if (route is AgeRoute.Play) {
            val normalized = AgeLinks.buildWebUrl(route)
            if (normalized != lastPlayLaunchUrl) {
                launchPlayer(route)
            }
        } else {
            lastPlayLaunchUrl = null
        }
    }

    private fun updateChromeForRoute(route: AgeRoute) {
        binding.pageSubtitle.text = when (route) {
            AgeRoute.Home -> "AGE 首页"
            is AgeRoute.Detail -> "动画详情 #${route.animeId}"
            is AgeRoute.Play -> "准备切换到原生播放器"
            is AgeRoute.Search -> route.query?.let { "搜索：$it" } ?: "搜索页"
            is AgeRoute.Web -> route.url
        }

        val record = (route as? AgeRoute.Detail)?.let { app.playbackStore.getRecord(it.animeId) }
        binding.continueButton.isVisible = record != null
        binding.continueButton.text = record?.let { "继续 ${it.episodeLabel}" } ?: getString(R.string.btn_continue)
    }

    private fun dispatchWebMove(direction: String, fallback: (() -> Unit)? = null) {
        binding.webView.evaluateJavascript(WebFocusScripts.moveScript(direction)) { result ->
            if (result == "false" || result == "null" || result.isNullOrBlank()) {
                fallback?.invoke()
            }
        }
    }

    private fun showOverlayMessage(message: String) {
        overlayJob?.cancel()
        binding.overlayMessage.text = message
        binding.overlayMessage.visibility = View.VISIBLE
        overlayJob = lifecycleScope.launch {
            delay(2500)
            binding.overlayMessage.visibility = View.GONE
        }
    }

    inner class WebBridge {
        @JavascriptInterface
        fun onRouteChanged(url: String) {
            runOnUiThread { this@MainActivity.onRouteChanged(url) }
        }
    }

    companion object {
        const val EXTRA_OPEN_ROUTE = "extra_open_route"

        private const val AGE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}
