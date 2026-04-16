package io.agedm.tv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
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
import io.agedm.tv.databinding.ActivityPlayerBinding
import io.agedm.tv.ui.adapter.EpisodeAdapter
import io.agedm.tv.ui.adapter.SourceAdapter
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class PlayerActivity : AppCompatActivity() {

    private data class ParserRequest(
        val id: Int,
        val parserUrl: String,
        val source: EpisodeSource,
        val episode: EpisodeItem,
        val deferred: CompletableDeferred<ResolvedStream>,
    )

    private lateinit var binding: ActivityPlayerBinding
    private val app: AgeTvApplication
        get() = application as AgeTvApplication

    private lateinit var player: ExoPlayer
    private lateinit var sourceAdapter: SourceAdapter
    private lateinit var episodeAdapter: EpisodeAdapter
    private var parserWebView: WebView? = null

    private var detail: AnimeDetail? = null
    private var currentSourceIndex: Int = 0
    private var currentEpisodeIndex: Int = 0
    private var currentSource: EpisodeSource? = null
    private var currentEpisode: EpisodeItem? = null
    private var deferredSeekMs: Long = 0L
    private var playbackSpeed: Float = 1f
    private var autoNextEnabled: Boolean = true
    private var progressJob: Job? = null
    private var resolveJob: Job? = null
    private var parserPollJob: Job? = null
    private var controlsVisible = false
    private var drawerVisible = false
    private var autoHideAfterReady = true
    private var hasPlaybackStarted = false
    private var parserRequestId = 0
    private var parserRequest: ParserRequest? = null
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

        autoNextEnabled = app.playbackStore.isAutoNextEnabled()
        updateAutoNextButton()
        binding.speedButton.text = "倍速 1.0x"
        showControls()
        loadDetail()
        startProgressLoop()
    }

    override fun onPause() {
        persistCurrentProgress()
        super.onPause()
    }

    override fun onDestroy() {
        progressJob?.cancel()
        resolveJob?.cancel()
        parserPollJob?.cancel()
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
                    if (!controlsVisible && !drawerVisible) {
                        showControls()
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!controlsVisible && !drawerVisible) {
                        showControls()
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!drawerVisible && !isDrawerListFocused()) {
                        openDrawer()
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!controlsVisible && !drawerVisible) {
                        seekBy(-10_000L)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!controlsVisible && !drawerVisible) {
                        seekBy(10_000L)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
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
                            if (autoHideAfterReady && !drawerVisible) {
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
            settings.userAgentString = PLAYER_USER_AGENT
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ) = super.shouldInterceptRequest(view, request).also {
                    val candidate = request?.url?.toString().orEmpty()
                    if (looksLikePlayableMediaUrl(candidate)) {
                        runOnUiThread { completeParserRequest(candidate) }
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val request = parserRequest ?: return
                    startParserPolling(request.id)
                }
            }
        }
        binding.playerRoot.addView(parserWebView)
    }

    private fun setupLists() {
        sourceAdapter = SourceAdapter { source ->
            val targetIndex = detail?.sources?.indexOfFirst { it.key == source.key } ?: -1
            if (targetIndex >= 0 && targetIndex != currentSourceIndex) {
                persistCurrentProgress()
                beginPlayback(targetIndex, 0, 0L)
            }
        }

        episodeAdapter = EpisodeAdapter { episode ->
            if (episode.index != currentEpisodeIndex) {
                persistCurrentProgress()
                beginPlayback(currentSourceIndex, episode.index, 0L)
            }
        }

        binding.sourceRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.sourceRecycler.adapter = sourceAdapter

        binding.episodeRecycler.layoutManager = LinearLayoutManager(this)
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
            drawerVisible -> closeDrawer()
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
                detail = loaded
                if (loaded.sources.isEmpty()) {
                    binding.loadingText.text = "当前动画没有可用分集"
                    showControls()
                    return@launch
                }
                val record = app.playbackStore.getRecord(animeId)
                val preferPrompt = intent.getBooleanExtra(EXTRA_PREFER_RESUME_PROMPT, true)
                if (preferPrompt && shouldOfferResume(record, loaded)) {
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

        sourceAdapter.submitList(loadedDetail.sources, source.key)
        episodeAdapter.submitList(source.episodes, episode.index)
        updatePlayerInfo()
        scrollEpisodeIntoView()

        binding.loadingText.isVisible = true
        binding.loadingText.text = "正在使用 AGE 解析 ${episode.label}..."

        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch {
            try {
                val stream = resolveStreamWithAgeParser(loadedDetail, source, episode)
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

    private suspend fun resolveStreamWithAgeParser(
        detail: AnimeDetail,
        source: EpisodeSource,
        episode: EpisodeItem,
    ): ResolvedStream {
        val parserUrl = app.ageRepository.buildParserUrl(detail, source, episode)
        val request = ParserRequest(
            id = ++parserRequestId,
            parserUrl = parserUrl,
            source = source,
            episode = episode,
            deferred = CompletableDeferred(),
        )

        parserPollJob?.cancel()
        parserRequest?.deferred?.cancel()
        parserRequest = request

        parserWebView?.stopLoading()
        parserWebView?.loadUrl("about:blank")
        parserWebView?.loadUrl(parserUrl)

        return try {
            withTimeout(PARSER_TIMEOUT_MS) {
                request.deferred.await()
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            parserPollJob?.cancel()
            parserRequest = null
            app.ageRepository.resolveStream(detail, source, episode)
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
    }

    private fun tryAlternateSource(episodeIndex: Int, seekMs: Long): Boolean {
        val loadedDetail = detail ?: return false
        val nextSourceIndex = loadedDetail.sources.indices.firstOrNull { index ->
            index !in attemptedSourceIndices &&
                episodeIndex in loadedDetail.sources[index].episodes.indices
        } ?: return false

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
        val activeRequest = parserRequest ?: return false
        if (requestId != null && requestId != activeRequest.id) return false

        val normalizedUrl = url.trim()
            .replace("&amp;", "&")
            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return false

        if (activeRequest.deferred.isCompleted || activeRequest.deferred.isCancelled) {
            return false
        }

        val isM3u8 = normalizedUrl.contains(".m3u8", ignoreCase = true)
        activeRequest.deferred.complete(
            ResolvedStream(
                streamUrl = normalizedUrl,
                parserUrl = activeRequest.parserUrl,
                sourceKey = activeRequest.source.key,
                sourceLabel = activeRequest.source.label,
                episode = activeRequest.episode,
                isM3u8 = isM3u8,
                mimeType = app.ageRepository.inferMimeType(normalizedUrl, isM3u8),
                headers = app.ageRepository.buildPlaybackHeaders(activeRequest.parserUrl),
            ),
        )
        parserRequest = null
        parserPollJob?.cancel()
        return true
    }

    private suspend fun readParserMediaUrl(): String? {
        val webView = parserWebView ?: return null
        val result = CompletableDeferred<String?>()
        webView.evaluateJavascript(PARSER_PROBE_SCRIPT) { value ->
            result.complete(decodeJavascriptValue(value))
        }
        return result.await()
            ?.replace("&amp;", "&")
            ?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
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

    private fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
        updatePlayPauseButton()
    }

    private fun updatePlayPauseButton() {
        binding.playPauseButton.text = if (player.isPlaying) "暂停" else "播放"
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

    private fun openSpeedSelector() {
        val labels = arrayOf("1.0x", "1.25x", "1.5x", "2.0x")
        val values = floatArrayOf(1f, 1.25f, 1.5f, 2f)
        val currentIndex = values.indexOfFirst { it == playbackSpeed }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("选择倍速")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                playbackSpeed = values[which]
                player.playbackParameters = PlaybackParameters(playbackSpeed)
                binding.speedButton.text = "倍速 ${labels[which]}"
                dialog.dismiss()
            }
            .show()
    }

    private fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        player.seekTo(target)
        binding.loadingText.isVisible = true
        binding.loadingText.text = if (deltaMs > 0) "快进到 ${formatPlaybackTime(target)}" else "快退到 ${formatPlaybackTime(target)}"
        lifecycleScope.launch {
            delay(800)
            if (!player.isLoading) {
                binding.loadingText.isVisible = false
            }
        }
    }

    private fun openDrawer() {
        drawerVisible = true
        controlsVisible = true
        binding.overlayScrim.isVisible = true
        binding.topInfoContainer.isVisible = true
        binding.bottomControlContainer.isVisible = false
        binding.episodeDrawer.isVisible = true
        scrollEpisodeIntoView()
        binding.episodeRecycler.post {
            binding.episodeRecycler.findViewHolderForAdapterPosition(currentEpisodeIndex)
                ?.itemView
                ?.requestFocus()
                ?: binding.episodeRecycler.requestFocus()
        }
    }

    private fun closeDrawer() {
        drawerVisible = false
        binding.episodeDrawer.isVisible = false
        binding.overlayScrim.isVisible = controlsVisible
        binding.bottomControlContainer.isVisible = controlsVisible
        if (controlsVisible) {
            binding.playPauseButton.requestFocus()
        } else {
            binding.playerRoot.requestFocus()
        }
    }

    private fun showControls() {
        controlsVisible = true
        binding.overlayScrim.isVisible = true
        binding.topInfoContainer.isVisible = true
        binding.bottomControlContainer.isVisible = true
        binding.playPauseButton.requestFocus()
    }

    private fun hideControls() {
        controlsVisible = false
        binding.topInfoContainer.isVisible = false
        binding.bottomControlContainer.isVisible = false
        if (!drawerVisible) {
            binding.overlayScrim.isVisible = false
        }
        binding.playerRoot.requestFocus()
    }

    private fun isDrawerListFocused(): Boolean {
        val focused = currentFocus ?: return false
        return focused.parent == binding.episodeRecycler ||
            focused.parent == binding.sourceRecycler
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (true) {
                delay(5_000L)
                persistCurrentProgress()
            }
        }
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
        val route = if (animeId > 0L) {
            AgeLinks.buildWebUrl(AgeRoute.Detail(animeId))
        } else {
            AgeLinks.BASE_WEB_URL
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
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
        private const val PARSER_POLL_RETRY_COUNT = 24
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
