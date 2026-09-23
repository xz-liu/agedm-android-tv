package io.agedm.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import io.agedm.tv.data.ImageWrappedTsDataSource
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
import io.agedm.tv.data.episodeIndexOrFirst
import io.agedm.tv.databinding.ActivityPlayerBinding
import io.agedm.tv.ui.adapter.EpisodeAdapter
import io.agedm.tv.ui.adapter.SourceAdapter
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val app: AgeTvApplication
        get() = application as AgeTvApplication

    private lateinit var streamResolver: WebStreamResolver
    private lateinit var player: ExoPlayer
    private lateinit var sourceAdapter: SourceAdapter
    private lateinit var episodeAdapter: EpisodeAdapter

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
    private var skipOsdJob: Job? = null
    private var controlsHideJob: Job? = null
    private var controlsVisible = false
    private var episodePanelOpen = false
    private var autoHideAfterReady = true
    // Track last two key-down codes for the skip-intro gesture (RIGHT → UP).
    private var prevKeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var prev2KeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var hasPlaybackStarted = false
    private var playingDownloaded = false
    private var playbackRequestId = 0
    private var supplementalSourcesRequested = false
    private var supplementalSourceLoading = false
    private var bangumiWatchingQueued = false
    private var bangumiCollectedQueued = false
    private val attemptedSourceIndices = linkedSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPlayer()
        streamResolver = WebStreamResolver(this, binding.playerRoot, lifecycleScope, app.ageRepository)
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
        player.pause()
        super.onPause()
    }

    override fun onDestroy() {
        playbackErrorDialog?.dismiss()
        progressJob?.cancel()
        resolveJob?.cancel()
        skipOsdJob?.cancel()
        controlsHideJob?.cancel()
        persistCurrentProgress()
        streamResolver.release()
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
                    if (episodePanelOpen) {
                        when {
                            // UP from source row → jump to current episode item.
                            isInSourceList() -> { focusEpisodeList(); return true }
                            // UP from episode row → collapse section, back to controls.
                            isInEpisodeList() -> { closeEpisodeSection(); return true }
                        }
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
                        isInEpisodeList() -> {
                            // DOWN from episode row → jump to current source, not just first item.
                            focusSourceList()
                            return true
                        }
                        // In source list: natural focus handles further DOWN (nothing below).
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
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                app.linkCastManager.incomingRoutes.collectLatest {
                    val route = app.linkCastManager.consumePendingRoute() ?: return@collectLatest
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
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true)).build().apply {
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
                    if (playingDownloaded) {
                        showPlaybackFailure(error)
                        return
                    }
                    if (!hasPlaybackStarted || player.currentPosition <= 2_000L) {
                        if (tryAlternateSource(currentEpisodeIndex, deferredSeekMs.coerceAtLeast(player.currentPosition))) {
                            return
                        }
                    }
                    showPlaybackFailure(error)
                }
            })
        }
        binding.playerView.player = player
        binding.playerView.useController = false
    }

    private var playbackErrorDialog: androidx.appcompat.app.AlertDialog? = null

    private fun showPlaybackFailure(error: PlaybackException) {
        val wasLocal = playingDownloaded
        val requestId = playbackRequestId
        val seekMs = if (hasPlaybackStarted) maxOf(deferredSeekMs, player.currentPosition.coerceAtLeast(0)) else deferredSeekMs
        val message = playbackFailureMessage(error, wasLocal)
        val format = player.videoFormat
        android.util.Log.e("AgePlayback", "anime=${detail?.animeId} source=${currentSource?.key} episode=${currentEpisode?.index} local=$wasLocal " +
            "code=${error.errorCodeName} codec=${format?.codecs} mime=${format?.sampleMimeType}", error)
        binding.loadingText.isVisible = true
        binding.loadingText.text = message
        showControls()
        playbackErrorDialog?.dismiss()
        playbackErrorDialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (wasLocal) "本地播放失败" else "播放失败")
            .setMessage(message)
            .setPositiveButton(if (wasLocal) "在线播放本集" else "重新解析") { _, _ ->
                if (requestId == playbackRequestId) beginPlayback(currentSourceIndex, currentEpisodeIndex, seekMs, skipDownload = wasLocal)
            }
            .setNeutralButton("选择播放源") { _, _ -> showControls(expandEpisodes = true) }
            .setNegativeButton("关闭", null)
            .show()
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

        episodeAdapter = EpisodeAdapter(onSelected = { episode ->
            if (drawerPreviewSourceIndex != currentSourceIndex || episode.index != currentEpisodeIndex) {
                persistCurrentProgress()
                beginPlayback(drawerPreviewSourceIndex, episode.index, 0L)
            }
        })

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
                val cached = app.offlineDownloads.playbackDetail(animeId, app.ageRepository.peekDetail(animeId))
                val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY)
                    ?: cached?.sources?.getOrNull(intent.getIntExtra(EXTRA_SOURCE_INDEX, 1) - 1)?.key
                val episodeIndex = intent.getIntExtra(EXTRA_EPISODE_INDEX, 1) - 1
                val local = sourceKey?.let { app.offlineDownloads.completed(animeId, it, episodeIndex) }
                // A completed episode starts without a detail refresh or a stream resolution request.
                val loaded = if (local != null && cached != null) cached else try {
                    val fresh = app.ageRepository.fetchDetail(animeId)
                    app.offlineDownloads.playbackDetail(animeId, fresh) ?: fresh
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    cached ?: throw error
                }
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
                if (error is CancellationException) throw error
                binding.loadingText.text = "加载失败：${error.message.orEmpty()}"
                showControls()
            }
        }
    }

    private fun shouldOfferResume(record: PlaybackRecord?, detail: AnimeDetail): Boolean {
        if (record == null || record.completed || record.positionMs < 30_000L) return false
        return detail.sources.any { source ->
            source.key == record.sourceKey && source.episodes.any { it.index == record.episodeIndex }
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
                val episodeIndex = loadedDetail.sources[sourceIndex].episodeIndexOrFirst(record.episodeIndex)
                return sourceIndex to episodeIndex
            }
        }

        val preferredSourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY)
        if (!preferredSourceKey.isNullOrBlank()) {
            val sourceIndex = loadedDetail.sources.indexOfFirst { it.key == preferredSourceKey }
            if (sourceIndex >= 0) {
                val episodeIndex = loadedDetail.sources[sourceIndex].episodeIndexOrFirst(intent.getIntExtra(EXTRA_EPISODE_INDEX, 1) - 1)
                return sourceIndex to episodeIndex
            }
        }

        val sourceIndex = (intent.getIntExtra(EXTRA_SOURCE_INDEX, 1) - 1)
            .coerceIn(0, loadedDetail.sources.lastIndex)
        val episodeIndex = loadedDetail.sources[sourceIndex].episodeIndexOrFirst(intent.getIntExtra(EXTRA_EPISODE_INDEX, 1) - 1)
        return sourceIndex to episodeIndex
    }

    private fun beginPlayback(
        sourceIndex: Int,
        episodeIndex: Int,
        seekMs: Long,
        resetAttempts: Boolean = true,
        skipDownload: Boolean = false,
    ) {
        val loadedDetail = detail ?: return
        val source = loadedDetail.sources.getOrNull(sourceIndex) ?: return
        val episode = source.episodes.firstOrNull { it.index == episodeIndex } ?: return

        playbackErrorDialog?.dismiss()
        playbackRequestId++
        persistCurrentProgress()
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
        playingDownloaded = false
        maybeQueueBangumiWatching(loadedDetail)
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
                val download = if (skipDownload) null else app.offlineDownloads.completed(loadedDetail.animeId, source.key, episode.index)
                if (download != null) {
                    playingDownloaded = true
                    val item = app.offlineDownloads.playbackMediaItem(download)
                    player.setMediaSource(DefaultMediaSourceFactory(ImageWrappedTsDataSource.Factory(app.offlineDownloads.offlineDataSource()))
                        .createMediaSource(item))
                    prepareCurrentMedia()
                } else {
                    val stream = streamResolver.resolve(loadedDetail, source, episode)
                    playResolvedStream(stream)
                }
                updatePlayerInfo()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (playingDownloaded) {
                    showPlaybackFailure(PlaybackException("下载读取失败", error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
                    return@launch
                }
                if (tryAlternateSource(episodeIndex, seekMs)) {
                    return@launch
                }
                binding.loadingText.isVisible = true
                binding.loadingText.text = "解析失败：${error.message.orEmpty()}"
                showControls()
            }
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
            .setUserAgent(stream.headers["User-Agent"] ?: WebStreamResolver.PLAYER_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(stream.headers)

        val mediaSource = DefaultMediaSourceFactory(ImageWrappedTsDataSource.Factory(httpFactory))
            .createMediaSource(mediaItem)

        player.setMediaSource(mediaSource)
        prepareCurrentMedia()
    }

    private fun prepareCurrentMedia() {
        player.prepare()
        player.playWhenReady = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        player.playbackParameters = PlaybackParameters(playbackSpeed)
        updateProgressUi()
    }

    private fun tryAlternateSource(episodeIndex: Int, seekMs: Long): Boolean {
        val loadedDetail = detail ?: return false
        val attemptId = playbackRequestId
        val nextSourceIndex = loadedDetail.sources.indices.firstOrNull { index ->
            index !in attemptedSourceIndices &&
                loadedDetail.sources[index].episodes.any { it.index == episodeIndex }
        }
        if (nextSourceIndex != null) {
            val nextSource = loadedDetail.sources[nextSourceIndex]
            binding.loadingText.isVisible = true
            binding.loadingText.text = "当前源失败，切换到 ${nextSource.label}..."
            lifecycleScope.launch {
                if (attemptId != playbackRequestId) return@launch
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
                val extraSources = try {
                    app.ageRepository.fetchSupplementalSources(loadedDetail.animeId, loadedDetail.title)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (attemptId == playbackRequestId) {
                        binding.loadingText.text = "补充源加载失败，请重新选择播放源"
                        showControls()
                    }
                    return@launch
                }
                if (attemptId != playbackRequestId) return@launch
                val currentDetail = detail?.takeIf { it.animeId == loadedDetail.animeId } ?: return@launch
                if (extraSources.isNotEmpty()) {
                    val (mergedDetail, firstNewKey) = mergeSupplementalSources(currentDetail, extraSources)
                    detail = mergedDetail
                    refreshDrawerLists(focusSourceKey = firstNewKey)
                    val extraSourceIndex = mergedDetail.sources.indices.firstOrNull { index ->
                        index !in attemptedSourceIndices &&
                            mergedDetail.sources[index].episodes.any { it.index == episodeIndex }
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
        binding.playerSubtitle.text = "${source.label} · ${episode.label}${if (playingDownloaded) " · 已下载" else ""}"
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
        return source.episodeIndexOrFirst(currentEpisodeIndex)
    }

    private fun renderDrawerEpisodePreview(animate: Boolean, direction: Int) {
        val source = detail?.sources?.getOrNull(drawerPreviewSourceIndex)
        if (source == null) {
            episodeAdapter.submitList(emptyList(), 0)
            return
        }
        val applyContent = {
            episodeAdapter.submitList(source.episodes, drawerPreviewEpisodeIndex)
            binding.episodeRecycler.scrollToPosition(episodeAdapter.selectedPosition().coerceAtLeast(0))
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
                .findViewHolderForAdapterPosition(episodeAdapter.selectedPosition().coerceAtLeast(0))
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

    private fun isInSourceList(): Boolean {
        val focused = currentFocus ?: return false
        return focused.parent === binding.sourceRecycler
    }

    private fun focusSourceList() {
        binding.sourceRecycler.post {
            binding.sourceRecycler.scrollToPosition(drawerPreviewSourceIndex)
            binding.sourceRecycler.post {
                val target = binding.sourceRecycler
                    .findViewHolderForAdapterPosition(drawerPreviewSourceIndex)
                    ?.itemView
                    ?: binding.sourceRecycler.getChildAt(0)
                target?.requestFocus() ?: binding.sourceRecycler.requestFocus()
            }
        }
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
        if (!hasPlaybackStarted) return
        val isCompleted = completed || player.playbackState == Player.STATE_ENDED
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        val currentPosition = if (isCompleted) 0L else player.currentPosition.coerceAtLeast(0L)
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
            completed = isCompleted,
        )
        app.playbackStore.saveRecord(record)
        maybeQueueBangumiCollected(source, isCompleted)
    }

    private fun onPlaybackEnded() {
        persistCurrentProgress(completed = true)
        val source = currentSource ?: return
        if (autoNextEnabled && source.episodes.any { it.index == currentEpisodeIndex + 1 }) {
            beginPlayback(currentSourceIndex, currentEpisodeIndex + 1, 0L)
        } else {
            showControls()
            binding.loadingText.isVisible = true
            binding.loadingText.text = "当前分集已播放结束"
        }
    }

    private fun maybeQueueBangumiWatching(loadedDetail: AnimeDetail) {
        if (bangumiWatchingQueued) return
        bangumiWatchingQueued = true
        app.bangumiAccountService.enqueuePlaybackStarted(
            animeId = loadedDetail.animeId,
            title = loadedDetail.title,
        )
    }

    private fun maybeQueueBangumiCollected(source: EpisodeSource, completed: Boolean) {
        if (bangumiCollectedQueued) return
        val loadedDetail = detail ?: return
        val totalEpisodes = source.episodes.maxOfOrNull { it.index + 1 } ?: 0
        if (source.episodes.any { it.token.isBlank() }) return // Legacy download-only metadata has no series length.
        if (totalEpisodes <= 0) return
        val thresholdEpisode = ceil(totalEpisodes * 0.75).toInt().coerceAtLeast(1)
        val episodeGatePassed = currentEpisodeIndex + 1 >= thresholdEpisode
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        val progressGatePassed = completed || (duration > 0L && player.currentPosition >= duration * 0.9)
        if (!episodeGatePassed || !progressGatePassed) return
        bangumiCollectedQueued = true
        app.bangumiAccountService.enqueuePlaybackCompleted(
            animeId = loadedDetail.animeId,
            title = loadedDetail.title,
        )
    }

    private fun scrollEpisodeIntoView() {
        binding.episodeRecycler.post {
            binding.episodeRecycler.scrollToPosition(episodeAdapter.selectedPosition().coerceAtLeast(0))
        }
        binding.sourceRecycler.post {
            binding.sourceRecycler.scrollToPosition(currentSourceIndex)
        }
    }

    private fun navigateBackToDetail() {
        if (intent.getBooleanExtra(EXTRA_RETURN_TO_DOWNLOADS, false)) { finish(); return }
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
        private const val CONTROLS_AUTO_HIDE_MS = 4_000L
        private const val EXTRA_RETURN_TO_DOWNLOADS = "extra_return_to_downloads"
        private const val EXTRA_ANIME_ID = "extra_anime_id"
        private const val EXTRA_SOURCE_INDEX = "extra_source_index"
        private const val EXTRA_EPISODE_INDEX = "extra_episode_index"
        private const val EXTRA_SOURCE_KEY = "extra_source_key"
        private const val EXTRA_RESUME_POSITION_MS = "extra_resume_position_ms"
        private const val EXTRA_PREFER_RESUME_PROMPT = "extra_prefer_resume_prompt"
        fun createIntent(
            context: Context,
            animeId: Long,
            sourceIndex: Int,
            episodeIndex: Int,
            preferredSourceKey: String? = null,
            resumePositionMs: Long = 0L,
            preferResumePrompt: Boolean = true,
            returnToDownloads: Boolean = false,
        ): Intent {
            return Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_RETURN_TO_DOWNLOADS, returnToDownloads)
                .putExtra(EXTRA_ANIME_ID, animeId)
                .putExtra(EXTRA_SOURCE_INDEX, sourceIndex)
                .putExtra(EXTRA_EPISODE_INDEX, episodeIndex)
                .putExtra(EXTRA_SOURCE_KEY, preferredSourceKey)
                .putExtra(EXTRA_RESUME_POSITION_MS, resumePositionMs)
                .putExtra(EXTRA_PREFER_RESUME_PROMPT, preferResumePrompt)
        }
    }
}
