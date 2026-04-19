package io.agedm.tv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.data.AgeLinks
import io.agedm.tv.data.AgeRoute
import io.agedm.tv.data.AnimeDetail
import io.agedm.tv.data.EpisodeItem
import io.agedm.tv.data.EpisodeSource
import io.agedm.tv.data.PlaybackRecord
import io.agedm.tv.data.ResolvedStream
import io.agedm.tv.data.SUPPLEMENTAL_PROVIDER_IDS
import io.agedm.tv.data.SourceResolver
import io.agedm.tv.data.loadedSupplementalProviders
import io.agedm.tv.data.mergeDistinctSources
import io.agedm.tv.data.orderedByPriority
import io.agedm.tv.databinding.ActivityPlayerBinding
import io.agedm.tv.ui.adapter.EpisodeAdapter
import io.agedm.tv.ui.adapter.SourceAdapter
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class PlayerActivity : AppCompatActivity() {

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
            runOnUiThread {
                completeParserRequest(
                    url = url,
                    requestId = requestId,
                    resolvedPageUrl = pageUrl,
                    verified = true,
                )
            }
        }
    }

    private lateinit var binding: ActivityPlayerBinding
    private val app: AgeTvApplication
        get() = application as AgeTvApplication

    private lateinit var player: ExoPlayer
    private lateinit var sourceAdapter: SourceAdapter
    private lateinit var episodeAdapter: EpisodeAdapter
    private var parserWebView: WebView? = null
    private val parserJavascriptBridge = ParserJavascriptBridge()

    private var detail: AnimeDetail? = null
    private var currentSourceIndex: Int = 0
    private var currentEpisodeIndex: Int = 0
    private var drawerPreviewSourceIndex: Int = 0
    private var drawerPreviewEpisodeIndex: Int = 0
    private var currentSource: EpisodeSource? = null
    private var currentEpisode: EpisodeItem? = null
    private var deferredSeekMs: Long = 0L
    private var playbackSpeed: Float = 1f
    private var autoNextEnabled: Boolean = true
    private var progressJob: Job? = null
    private var resolveJob: Job? = null
    private var parserPollJob: Job? = null
    private var skipOsdJob: Job? = null
    private var controlsHideJob: Job? = null
    private var controlsVisible = false
    private var episodePanelOpen = false
    private var autoHideAfterReady = true
    // Track last two key-down codes for the skip-intro gesture (RIGHT → UP).
    private var prevKeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var prev2KeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var hasPlaybackStarted = false
    private var parserRequestId = 0
    private var parserRequest: ParserRequest? = null
    private var supplementalSourcesRequested = false
    private var supplementalSourceLoading = false
    private val attemptedSourceIndices = linkedSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPlayer()
        setupParserWebView()
        setupLists()
        setupButtons()
        setupBackBehavior()
        collectIncomingRoutes()

        autoNextEnabled = app.playbackStore.isAutoNextEnabled()
        playbackSpeed = app.playbackStore.getPlaybackSpeed()
        updateAutoNextButton()
        updateSpeedButton()
        updateProgressUi()
        showControls()
        loadDetail()
        startProgressLoop()
    }

    override fun onResume() {
        super.onResume()
        app.linkCastManager.consumePendingRoute()?.let(::handleIncomingRoute)
    }

    override fun onPause() {
        persistCurrentProgress()
        super.onPause()
    }

    override fun onDestroy() {
        progressJob?.cancel()
        resolveJob?.cancel()
        parserPollJob?.cancel()
        skipOsdJob?.cancel()
        controlsHideJob?.cancel()
        parserRequest?.deferred?.cancel()
        persistCurrentProgress()
        parserWebView?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }
        parserWebView = null
        player.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Reschedule auto-hide on any interaction while overlay is open (but no episodes).
            if (controlsVisible && !episodePanelOpen && event.keyCode != KeyEvent.KEYCODE_BACK) {
                scheduleControlsHide()
            }

            // Detect skip-intro gesture: exactly one RIGHT immediately before UP.
            // Multiple consecutive RIGHTs before UP should NOT trigger it.
            val skipIntroGesture = event.keyCode == KeyEvent.KEYCODE_DPAD_UP
                && prevKeyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                && prev2KeyCode != KeyEvent.KEYCODE_DPAD_RIGHT

            prev2KeyCode = prevKeyCode
            prevKeyCode = event.keyCode

            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    handleBackPress()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePlayPause()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    if (!controlsVisible) {
                        // Overlay hidden: pause + show controls (episodes stay hidden).
                        if (player.isPlaying) {
                            player.pause()
                            updatePlayPauseButton()
                        }
                        showControls(expandEpisodes = false)
                        return true
                    }
                    // Overlay visible: fall through so focused button handles it.
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (skipIntroGesture && !controlsVisible) {
                        val skipMs = app.playbackStore.getSkipIntroDurationMs()
                        seekBy(skipMs)
                        val sec = skipMs / 1000
                        val min = sec / 60
                        val rem = sec % 60
                        val label = if (min > 0) "$min:${rem.toString().padStart(2, '0')}" else "${sec}s"
                        showSkipOsd("跳过片头  +$label")
                        return true
                    }
                    // UP from episode list → collapse episode section back to controls-only.
                    if (episodePanelOpen && isInEpisodeList()) {
                        closeEpisodeSection()
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    when {
                        !controlsVisible -> {
                            // No overlay at all → show overlay and expand episodes immediately.
                            showControls(expandEpisodes = true)
                            return true
                        }
                        !episodePanelOpen -> {
                            // Overlay visible but episodes hidden → expand episodes.
                            openEpisodeSection()
                            return true
                        }
                        !isInEpisodeOrSourceList() -> {
                            // Episodes open but focus is still on controls → route to episode row.
                            focusEpisodeList()
                            return true
                        }
                        // Already in episode/source list: let natural focus navigate.
                    }
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!controlsVisible && !episodePanelOpen) {
                        seekBy(-10_000L)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!controlsVisible && !episodePanelOpen) {
                        seekBy(10_000L)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun collectIncomingRoutes() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.linkCastManager.incomingRoutes.collectLatest { route ->
                    app.linkCastManager.consumePendingRoute()
                    handleIncomingRoute(route)
                }
            }
        }
    }

    private fun handleIncomingRoute(route: AgeRoute) {
        when (route) {
            AgeRoute.Home,
            is AgeRoute.Search,
            is AgeRoute.Web,
            -> {
                startActivity(
                    MainActivity.createIntent(this, route)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
                finish()
            }

            is AgeRoute.Detail -> {
                startActivity(DetailActivity.createIntent(this, route.animeId))
                finish()
            }

            is AgeRoute.Play -> {
                startActivity(
                    createIntent(
                        context = this,
                        animeId = route.animeId,
                        sourceIndex = route.sourceIndex,
                        episodeIndex = route.episodeIndex,
                    ),
                )
                finish()
            }
        }
    }

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build().apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            binding.loadingText.isVisible = true
                            binding.loadingText.text = "正在缓冲..."
                        }

                        Player.STATE_READY -> {
                            hasPlaybackStarted = true
                            if (deferredSeekMs > 0L) {
                                player.seekTo(deferredSeekMs)
                                deferredSeekMs = 0L
                            }
                            binding.loadingText.isVisible = false
                            updatePlayPauseButton()
                            updateProgressUi()
                            if (autoHideAfterReady && !episodePanelOpen) {
                                autoHideAfterReady = false
                                hideControls()
                            }
                        }

                        Player.STATE_ENDED -> {
                            onPlaybackEnded()
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseButton()
                    updateProgressUi()
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!hasPlaybackStarted || player.currentPosition <= 2_000L) {
                        if (tryAlternateSource(currentEpisodeIndex, deferredSeekMs.coerceAtLeast(player.currentPosition))) {
                            return
                        }
                    }
                    binding.loadingText.isVisible = true
                    binding.loadingText.text = "播放失败：${error.errorCodeName}"
                    showControls()
                }
            })
        }
        binding.playerView.player = player
        binding.playerView.useController = false
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupParserWebView() {
        parserWebView = WebView(this).apply {
            visibility = View.INVISIBLE
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(1, 1)
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
                        runOnUiThread {
                            completeParserRequest(
                                url = candidate,
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
        binding.playerRoot.addView(parserWebView)
    }

    private fun setupLists() {
        sourceAdapter = SourceAdapter(
            onSelected = { source ->
                val targetIndex = detail?.sources?.indexOfFirst { it.key == source.key } ?: -1
                val targetEpisodeIndex = if (targetIndex == drawerPreviewSourceIndex) {
                    drawerPreviewEpisodeIndex
                } else {
                    resolveDrawerPreviewEpisodeIndex(targetIndex)
                }
                if (targetIndex >= 0 && (targetIndex != currentSourceIndex || targetEpisodeIndex != currentEpisodeIndex)) {
                    persistCurrentProgress()
                    beginPlayback(targetIndex, targetEpisodeIndex, 0L)
                }
            },
            onFocused = { source ->
                val targetIndex = detail?.sources?.indexOfFirst { it.key == source.key } ?: -1
                if (targetIndex >= 0) {
                    previewDrawerSource(targetIndex)
                }
            },
            onAction = ::loadSupplementalSourcesManually,
        )

        episodeAdapter = EpisodeAdapter { episode ->
            if (drawerPreviewSourceIndex != currentSourceIndex || episode.index != currentEpisodeIndex) {
                persistCurrentProgress()
                beginPlayback(drawerPreviewSourceIndex, episode.index, 0L)
            }
        }

        binding.sourceRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.sourceRecycler.adapter = sourceAdapter

        binding.episodeRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.episodeRecycler.adapter = episodeAdapter
    }

    private fun setupButtons() {
        binding.playPauseButton.setOnClickListener { togglePlayPause() }
        binding.speedButton.setOnClickListener { openSpeedSelector() }
        binding.autoNextButton.setOnClickListener {
            autoNextEnabled = !autoNextEnabled
            app.playbackStore.setAutoNextEnabled(autoNextEnabled)
            updateAutoNextButton()
        }
    }

    private fun setupBackBehavior() {
        onBackPressedDispatcher.addCallback(this) {
            handleBackPress()
        }
    }

    private fun handleBackPress() {
        when {
            episodePanelOpen -> closeEpisodeSection()
            controlsVisible -> hideControls()
            else -> {
                persistCurrentProgress()
                navigateBackToDetail()
            }
        }
    }

    private fun loadDetail() {
        val animeId = intent.getLongExtra(EXTRA_ANIME_ID, 0L)
        if (animeId <= 0L) {
            binding.loadingText.text = "缺少动画 ID"
            return
        }

        binding.loadingText.isVisible = true
        binding.loadingText.text = "正在读取动画详情..."
        lifecycleScope.launch {
            try {
                val loaded = app.ageRepository.fetchDetail(animeId)
                val ordered = loaded.copy(
                    sources = loaded.sources.orderedByPriority(app.playbackStore.getSourcePriority()),
                )
                detail = ordered
                supplementalSourcesRequested = ordered.sources.any { it.resolver == SourceResolver.WEB_PAGE }
                if (ordered.sources.isEmpty()) {
                    binding.loadingText.text = "当前动画没有可用分集"
                    showControls()
                    return@launch
                }
                val record = app.playbackStore.getRecord(animeId)
                val preferPrompt = intent.getBooleanExtra(EXTRA_PREFER_RESUME_PROMPT, true)
                if (preferPrompt && shouldOfferResume(record, ordered)) {
                    showResumePrompt(record!!)
                } else {
                    val selection = resolveSelection(record, useRecord = false)
                    beginPlayback(selection.first, selection.second, intent.getLongExtra(EXTRA_RESUME_POSITION_MS, 0L))
                }
            } catch (error: Throwable) {
                binding.loadingText.text = "加载失败：${error.message.orEmpty()}"
                showControls()
            }
        }
    }

    private fun shouldOfferResume(record: PlaybackRecord?, detail: AnimeDetail): Boolean {
        if (record == null || record.completed || record.positionMs < 30_000L) return false
        return detail.sources.any { source ->
            source.key == record.sourceKey && record.episodeIndex in source.episodes.indices
        }
    }

    private fun showResumePrompt(record: PlaybackRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle("继续播放")
            .setMessage("继续 ${record.episodeLabel} · ${formatPlaybackTime(record.positionMs)}？")
            .setPositiveButton("继续") { _, _ ->
                val selection = resolveSelection(record, useRecord = true)
                beginPlayback(selection.first, selection.second, record.positionMs)
            }
            .setNegativeButton("从当前打开的集数开始") { _, _ ->
                val selection = resolveSelection(record, useRecord = false)
                beginPlayback(selection.first, selection.second, 0L)
            }
            .setCancelable(false)
            .show()
    }

    private fun resolveSelection(record: PlaybackRecord?, useRecord: Boolean): Pair<Int, Int> {
        val loadedDetail = detail ?: return 0 to 0

        if (useRecord && record != null) {
            val sourceIndex = loadedDetail.sources.indexOfFirst { it.key == record.sourceKey }
            if (sourceIndex >= 0) {
                val episodeIndex = record.episodeIndex.coerceIn(0, loadedDetail.sources[sourceIndex].episodes.lastIndex)
                return sourceIndex to episodeIndex
            }
        }

        val preferredSourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY)
        if (!preferredSourceKey.isNullOrBlank()) {
            val sourceIndex = loadedDetail.sources.indexOfFirst { it.key == preferredSourceKey }
            if (sourceIndex >= 0) {
                val episodeIndex = (intent.getIntExtra(EXTRA_EPISODE_INDEX, 1) - 1)
                    .coerceIn(0, loadedDetail.sources[sourceIndex].episodes.lastIndex)
                return sourceIndex to episodeIndex
            }
        }

        val sourceIndex = (intent.getIntExtra(EXTRA_SOURCE_INDEX, 1) - 1)
            .coerceIn(0, loadedDetail.sources.lastIndex)
        val episodeIndex = (intent.getIntExtra(EXTRA_EPISODE_INDEX, 1) - 1)
            .coerceIn(0, loadedDetail.sources[sourceIndex].episodes.lastIndex)
        return sourceIndex to episodeIndex
    }

    private fun beginPlayback(
        sourceIndex: Int,
        episodeIndex: Int,
        seekMs: Long,
        resetAttempts: Boolean = true,
    ) {
        val loadedDetail = detail ?: return
        val source = loadedDetail.sources.getOrNull(sourceIndex) ?: return
        val episode = source.episodes.getOrNull(episodeIndex) ?: return

        if (resetAttempts) {
            attemptedSourceIndices.clear()
        }
        attemptedSourceIndices += sourceIndex
        currentSourceIndex = sourceIndex
        currentEpisodeIndex = episodeIndex
        currentSource = source
        currentEpisode = episode
        deferredSeekMs = seekMs
        autoHideAfterReady = true
        hasPlaybackStarted = false
        player.stop()

        refreshDrawerLists()
        updatePlayerInfo()
        scrollEpisodeIntoView()

        binding.loadingText.isVisible = true
        binding.loadingText.text = when (source.resolver) {
            SourceResolver.AGE_PARSER -> "正在使用 AGE 解析 ${episode.label}..."
            SourceResolver.WEB_PAGE -> "正在加载 ${source.label} · ${episode.label}..."
        }

        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch {
            try {
                val stream = resolveStreamForSource(loadedDetail, source, episode)
                playResolvedStream(stream)
                updatePlayerInfo()
            } catch (error: Throwable) {
                if (tryAlternateSource(episodeIndex, seekMs)) {
                    return@launch
                }
                binding.loadingText.isVisible = true
                binding.loadingText.text = "解析失败：${error.message.orEmpty()}"
                showControls()
            }
        }
    }

    private suspend fun resolveStreamForSource(
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
        val parserUrl = app.ageRepository.buildParserUrl(detail, source, episode)
        return resolveStreamViaWebView(
            pageUrl = parserUrl,
            source = source,
            episode = episode,
            pageHeaders = emptyMap(),
            streamHeaders = app.ageRepository.buildPlaybackHeaders(parserUrl),
            fallback = {
                app.ageRepository.resolveStream(detail, source, episode)
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
            withTimeout(PARSER_TIMEOUT_MS) {
                request.deferred.await()
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            parserPollJob?.cancel()
            parserRequest = null
            fallback?.invoke() ?: throw IOException("未能从页面提取真实视频地址")
        } finally {
            parserWebView?.stopLoading()
        }
    }

    private fun playResolvedStream(stream: ResolvedStream) {
        val mediaItem = MediaItem.Builder()
            .setUri(stream.streamUrl)
            .apply {
                val mimeType = stream.mimeType ?: if (stream.isM3u8) MimeTypes.APPLICATION_M3U8 else null
                if (!mimeType.isNullOrBlank()) {
                    setMimeType(mimeType)
                }
            }
            .build()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(stream.headers["User-Agent"] ?: PLAYER_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(stream.headers)

        val mediaSource = if (stream.isM3u8) {
            HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
        }

        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
        player.playbackParameters = PlaybackParameters(playbackSpeed)
        updateProgressUi()
    }

    private fun tryAlternateSource(episodeIndex: Int, seekMs: Long): Boolean {
        val loadedDetail = detail ?: return false
        val nextSourceIndex = loadedDetail.sources.indices.firstOrNull { index ->
            index !in attemptedSourceIndices &&
                episodeIndex in loadedDetail.sources[index].episodes.indices
        }
        if (nextSourceIndex != null) {
            val nextSource = loadedDetail.sources[nextSourceIndex]
            binding.loadingText.isVisible = true
            binding.loadingText.text = "当前源失败，切换到 ${nextSource.label}..."
            lifecycleScope.launch {
                beginPlayback(
                    sourceIndex = nextSourceIndex,
                    episodeIndex = episodeIndex,
                    seekMs = seekMs,
                    resetAttempts = false,
                )
            }
            return true
        }

        if (!supplementalSourcesRequested) {
            supplementalSourcesRequested = true
            binding.loadingText.isVisible = true
            binding.loadingText.text = "AGE 源已穷尽，正在匹配补充源..."
            lifecycleScope.launch {
                val extraSources = app.ageRepository.fetchSupplementalSources(
                    animeId = loadedDetail.animeId,
                    title = loadedDetail.title,
                )
                val currentDetail = detail?.takeIf { it.animeId == loadedDetail.animeId } ?: return@launch
                if (extraSources.isNotEmpty()) {
                    val (mergedDetail, firstNewKey) = mergeSupplementalSources(currentDetail, extraSources)
                    detail = mergedDetail
                    refreshDrawerLists(focusSourceKey = firstNewKey)
                    val extraSourceIndex = mergedDetail.sources.indices.firstOrNull { index ->
                        index !in attemptedSourceIndices &&
                            episodeIndex in mergedDetail.sources[index].episodes.indices
                    }
                    if (extraSourceIndex != null) {
                        beginPlayback(
                            sourceIndex = extraSourceIndex,
                            episodeIndex = episodeIndex,
                            seekMs = seekMs,
                            resetAttempts = false,
                        )
                        return@launch
                    }
                }

                binding.loadingText.isVisible = true
                binding.loadingText.text = "解析失败：未找到可用补充源"
                showControls()
            }
            return true
        }

        return false
    }

    private fun startParserPolling(requestId: Int) {
        parserPollJob?.cancel()
        parserPollJob = lifecycleScope.launch {
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
                mimeType = app.ageRepository.inferMimeType(normalizedUrl, isM3u8),
                headers = headers,
            ),
        )
        parserRequest = null
        parserPollJob?.cancel()
        return true
    }

    private fun buildStreamHeaders(pageUrl: String, source: EpisodeSource): Map<String, String> {
        return app.ageRepository.buildPlaybackHeaders(pageUrl)
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

    private fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
            controlsHideJob?.cancel()  // Stay visible while paused.
        } else {
            player.play()
            hideControls()             // Resume: dismiss the overlay.
        }
        updatePlayPauseButton()
    }

    private fun updatePlayPauseButton() {
        binding.playPauseButton.text = if (player.isPlaying) "暂停" else "播放"
    }

    private fun updateSpeedButton() {
        val label = if (playbackSpeed % 1f == 0f) {
            String.format("%.1f", playbackSpeed)
        } else {
            playbackSpeed.toString()
        }
        binding.speedButton.text = "倍速 ${label}x"
    }

    private fun updateAutoNextButton() {
        binding.autoNextButton.text = if (autoNextEnabled) "自动下一集：开" else "自动下一集：关"
    }

    private fun updatePlayerInfo() {
        val loadedDetail = detail ?: return
        val source = currentSource ?: return
        val episode = currentEpisode ?: return
        binding.playerTitle.text = loadedDetail.title
        binding.playerSubtitle.text = "${source.label} · ${episode.label}"
        binding.sourceSummary.text = "当前源：${source.label}（已尝试 ${attemptedSourceIndices.size} 个源）"
    }

    private fun refreshDrawerLists(focusSourceKey: String? = null) {
        val loadedDetail = detail ?: return
        val currentKey = currentSource?.key
        sourceAdapter.submitList(loadedDetail.sources, currentKey, sourceActionLabel(loadedDetail))

        if (drawerPreviewSourceIndex !in loadedDetail.sources.indices) {
            drawerPreviewSourceIndex = currentSourceIndex
        }
        drawerPreviewEpisodeIndex = resolveDrawerPreviewEpisodeIndex(drawerPreviewSourceIndex)
        renderDrawerEpisodePreview(animate = false, direction = 1)

        focusSourceKey?.let(::focusSourceKey)
    }

    private fun previewDrawerSource(targetIndex: Int) {
        val loadedDetail = detail ?: return
        if (targetIndex !in loadedDetail.sources.indices || targetIndex == drawerPreviewSourceIndex) return

        val direction = targetIndex.compareTo(drawerPreviewSourceIndex).takeIf { it != 0 } ?: 1
        drawerPreviewSourceIndex = targetIndex
        drawerPreviewEpisodeIndex = resolveDrawerPreviewEpisodeIndex(targetIndex)
        renderDrawerEpisodePreview(animate = true, direction = direction)
    }

    private fun resolveDrawerPreviewEpisodeIndex(sourceIndex: Int): Int {
        val source = detail?.sources?.getOrNull(sourceIndex) ?: return 0
        return currentEpisodeIndex.coerceIn(0, source.episodes.lastIndex.coerceAtLeast(0))
    }

    private fun renderDrawerEpisodePreview(animate: Boolean, direction: Int) {
        val source = detail?.sources?.getOrNull(drawerPreviewSourceIndex)
        if (source == null) {
            episodeAdapter.submitList(emptyList(), 0)
            return
        }
        val applyContent = {
            episodeAdapter.submitList(source.episodes, drawerPreviewEpisodeIndex)
            binding.episodeRecycler.scrollToPosition(drawerPreviewEpisodeIndex)
        }
        if (!animate || !binding.episodeRecycler.isLaidOut) {
            binding.episodeRecycler.translationX = 0f
            binding.episodeRecycler.alpha = 1f
            applyContent()
            return
        }

        val offset = if (direction >= 0) 40f else -40f
        binding.episodeRecycler.animate().cancel()
        binding.episodeRecycler.animate()
            .translationX(-offset * 0.35f)
            .alpha(0.25f)
            .setDuration(90L)
            .withEndAction {
                binding.episodeRecycler.translationX = offset
                applyContent()
                binding.episodeRecycler.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(180L)
                    .start()
            }
            .start()
    }

    private fun sourceActionLabel(loadedDetail: AnimeDetail): String? {
        val missingProviders = SUPPLEMENTAL_PROVIDER_IDS - loadedDetail.sources.loadedSupplementalProviders()
        if (missingProviders.isEmpty()) return null
        if (supplementalSourceLoading) return "正在加载其他源..."
        return if (loadedDetail.sources.loadedSupplementalProviders().isEmpty()) "加载其他源" else "继续加载其他源"
    }

    private fun loadSupplementalSourcesManually() {
        if (supplementalSourceLoading) return
        val loadedDetail = detail ?: return
        supplementalSourceLoading = true
        refreshDrawerLists()
        showSkipOsd("正在加载其他源...")

        lifecycleScope.launch {
            runCatching {
                app.ageRepository.fetchSupplementalSources(
                    animeId = loadedDetail.animeId,
                    title = loadedDetail.title,
                )
            }.onSuccess { extraSources ->
                supplementalSourceLoading = false
                val currentDetail = detail?.takeIf { it.animeId == loadedDetail.animeId } ?: return@launch
                val (mergedDetail, firstNewKey) = mergeSupplementalSources(currentDetail, extraSources)
                detail = mergedDetail
                if (firstNewKey != null) {
                    supplementalSourcesRequested = true
                    refreshDrawerLists(focusSourceKey = firstNewKey)
                    showSkipOsd("已加载其他源")
                } else {
                    refreshDrawerLists()
                    showSkipOsd("没有找到新的补充源")
                }
            }.onFailure {
                supplementalSourceLoading = false
                refreshDrawerLists()
                showSkipOsd("其他源加载失败")
            }
        }
    }

    private fun mergeSupplementalSources(
        currentDetail: AnimeDetail,
        extraSources: List<EpisodeSource>,
    ): Pair<AnimeDetail, String?> {
        val existingKeys = currentDetail.sources.mapTo(linkedSetOf()) { it.key }
        val mergedSources = currentDetail.sources
            .mergeDistinctSources(extraSources)
            .orderedByPriority(app.playbackStore.getSourcePriority())
        val firstNewKey = mergedSources.firstOrNull { it.key !in existingKeys }?.key
        return currentDetail.copy(sources = mergedSources) to firstNewKey
    }

    private fun focusSourceKey(sourceKey: String) {
        val position = detail?.sources?.indexOfFirst { it.key == sourceKey } ?: -1
        if (position < 0) return
        binding.sourceRecycler.post {
            binding.sourceRecycler.scrollToPosition(position)
            binding.sourceRecycler.post {
                binding.sourceRecycler.findViewHolderForAdapterPosition(position)
                    ?.itemView
                    ?.requestFocus()
            }
        }
    }

    private fun openSpeedSelector() {
        val labels = arrayOf("1.0x", "1.25x", "1.5x", "2.0x")
        val values = floatArrayOf(1f, 1.25f, 1.5f, 2f)
        val currentIndex = values.indexOfFirst { it == playbackSpeed }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("选择倍速")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                playbackSpeed = values[which]
                player.playbackParameters = PlaybackParameters(playbackSpeed)
                app.playbackStore.setPlaybackSpeed(playbackSpeed)
                updateSpeedButton()
                dialog.dismiss()
            }
            .show()
    }

    private fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        player.seekTo(target)
        updateProgressUi()
        val label = if (deltaMs > 0) "快进到 ${formatPlaybackTime(target)}" else "快退到 ${formatPlaybackTime(target)}"
        showSkipOsd(label)
    }

    private fun showSkipOsd(message: String) {
        skipOsdJob?.cancel()
        binding.skipOsdText.text = message
        binding.skipOsdText.isVisible = true
        skipOsdJob = lifecycleScope.launch {
            delay(1_500L)
            binding.skipOsdText.isVisible = false
        }
    }

    private fun showControls(expandEpisodes: Boolean = false) {
        controlsVisible = true
        fadeIn(binding.overlayScrim)
        fadeIn(binding.topInfoContainer)
        fadeIn(binding.bottomControlContainer)
        if (expandEpisodes) {
            openEpisodeSection()
        } else {
            binding.playPauseButton.requestFocus()
            scheduleControlsHide()
        }
    }

    private fun hideControls() {
        controlsHideJob?.cancel()
        controlsVisible = false
        episodePanelOpen = false
        binding.episodeSection.isVisible = false
        binding.sourceSection.isVisible = false
        fadeOut(binding.topInfoContainer)
        fadeOut(binding.bottomControlContainer)
        fadeOut(binding.overlayScrim)
        binding.playerRoot.requestFocus()
    }

    private fun openEpisodeSection() {
        episodePanelOpen = true
        controlsHideJob?.cancel()  // Auto-hide is suspended while episodes are open.
        binding.episodeSection.isVisible = true
        binding.sourceSection.isVisible = true
        drawerPreviewSourceIndex = currentSourceIndex
        drawerPreviewEpisodeIndex = currentEpisodeIndex
        renderDrawerEpisodePreview(animate = false, direction = 1)
        scrollEpisodeIntoView()
        focusEpisodeList()
    }

    private fun closeEpisodeSection() {
        episodePanelOpen = false
        binding.episodeSection.isVisible = false
        binding.sourceSection.isVisible = false
        binding.playPauseButton.requestFocus()
        scheduleControlsHide()
    }

    private fun focusEpisodeList() {
        binding.episodeRecycler.post {
            val target = binding.episodeRecycler
                .findViewHolderForAdapterPosition(drawerPreviewEpisodeIndex.coerceAtLeast(0))
                ?.itemView
                ?: binding.episodeRecycler.getChildAt(0)
            target?.requestFocus() ?: binding.episodeRecycler.requestFocus()
        }
    }

    private fun scheduleControlsHide() {
        controlsHideJob?.cancel()
        if (!player.isPlaying || episodePanelOpen) return  // Stay visible while paused or episodes open.
        controlsHideJob = lifecycleScope.launch {
            delay(CONTROLS_AUTO_HIDE_MS)
            if (controlsVisible && !episodePanelOpen) {
                hideControls()
            }
        }
    }

    private fun fadeIn(view: View, durationMs: Long = 150L) {
        view.animate().cancel()
        view.alpha = 0f
        view.isVisible = true
        view.animate()
            .alpha(1f)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun fadeOut(view: View, durationMs: Long = 120L) {
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(durationMs)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                view.isVisible = false
                view.alpha = 1f
            }
            .start()
    }

    private fun isInEpisodeOrSourceList(): Boolean {
        val focused = currentFocus ?: return false
        return focused.parent === binding.episodeRecycler ||
            focused.parent === binding.sourceRecycler
    }

    private fun isInEpisodeList(): Boolean {
        val focused = currentFocus ?: return false
        return focused.parent === binding.episodeRecycler
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            var persistTicker = 0
            while (true) {
                delay(1_000L)
                updateProgressUi()
                persistTicker += 1
                if (persistTicker >= 5) {
                    persistCurrentProgress()
                    persistTicker = 0
                }
            }
        }
    }

    private fun updateProgressUi() {
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        val position = player.currentPosition.coerceAtLeast(0L)
        val bufferedPosition = player.bufferedPosition.coerceAtLeast(position)
        binding.currentTimeText.text = formatPlaybackTime(position)
        binding.durationText.text = formatPlaybackTime(duration)
        binding.progressTimeBar.setDuration(duration)
        binding.progressTimeBar.setPosition(position)
        binding.progressTimeBar.setBufferedPosition(bufferedPosition)
    }

    private fun persistCurrentProgress(completed: Boolean = false) {
        val loadedDetail = detail ?: return
        val source = currentSource ?: return
        val episode = currentEpisode ?: return
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        val currentPosition = if (completed) 0L else player.currentPosition.coerceAtLeast(0L)
        val record = PlaybackRecord(
            animeId = loadedDetail.animeId,
            animeTitle = loadedDetail.title,
            detailUrl = AgeLinks.buildWebUrl(AgeRoute.Detail(loadedDetail.animeId)),
            sourceKey = source.key,
            sourceLabel = source.label,
            episodeIndex = episode.index,
            episodeLabel = episode.label,
            positionMs = currentPosition,
            durationMs = duration,
            lastUpdatedEpochMs = System.currentTimeMillis(),
            completed = completed,
        )
        app.playbackStore.saveRecord(record)
    }

    private fun onPlaybackEnded() {
        persistCurrentProgress(completed = true)
        val source = currentSource ?: return
        if (autoNextEnabled && currentEpisodeIndex < source.episodes.lastIndex) {
            beginPlayback(currentSourceIndex, currentEpisodeIndex + 1, 0L)
        } else {
            showControls()
            binding.loadingText.isVisible = true
            binding.loadingText.text = "当前分集已播放结束"
        }
    }

    private fun scrollEpisodeIntoView() {
        binding.episodeRecycler.post {
            binding.episodeRecycler.scrollToPosition(currentEpisodeIndex)
        }
        binding.sourceRecycler.post {
            binding.sourceRecycler.scrollToPosition(currentSourceIndex)
        }
    }

    private fun navigateBackToDetail() {
        val animeId = detail?.animeId ?: intent.getLongExtra(EXTRA_ANIME_ID, 0L)
        if (animeId > 0L) {
            startActivity(
                DetailActivity.createIntent(this, animeId)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }

    companion object {
        private const val EXTRA_ANIME_ID = "extra_anime_id"
        private const val EXTRA_SOURCE_INDEX = "extra_source_index"
        private const val EXTRA_EPISODE_INDEX = "extra_episode_index"
        private const val EXTRA_SOURCE_KEY = "extra_source_key"
        private const val EXTRA_RESUME_POSITION_MS = "extra_resume_position_ms"
        private const val EXTRA_PREFER_RESUME_PROMPT = "extra_prefer_resume_prompt"
        private const val PLAYER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
        private const val PARSER_TIMEOUT_MS = 20_000L
        private const val PARSER_POLL_INTERVAL_MS = 500L
        private const val CONTROLS_AUTO_HIDE_MS = 4_000L
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

        fun createIntent(
            context: Context,
            animeId: Long,
            sourceIndex: Int,
            episodeIndex: Int,
            preferredSourceKey: String? = null,
            resumePositionMs: Long = 0L,
            preferResumePrompt: Boolean = true,
        ): Intent {
            return Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ANIME_ID, animeId)
                .putExtra(EXTRA_SOURCE_INDEX, sourceIndex)
                .putExtra(EXTRA_EPISODE_INDEX, episodeIndex)
                .putExtra(EXTRA_SOURCE_KEY, preferredSourceKey)
                .putExtra(EXTRA_RESUME_POSITION_MS, resumePositionMs)
                .putExtra(EXTRA_PREFER_RESUME_PROMPT, preferResumePrompt)
        }
    }
}
