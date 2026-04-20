package io.agedm.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.data.AgeRoute
import io.agedm.tv.data.AgeRelatedItem
import io.agedm.tv.data.AnimeCard
import io.agedm.tv.data.AnimeDetail
import io.agedm.tv.data.BangumiCollectionStatus
import io.agedm.tv.data.BangumiComment
import io.agedm.tv.data.BangumiMetadata
import io.agedm.tv.data.EpisodeItem
import io.agedm.tv.data.EpisodeSource
import io.agedm.tv.data.SUPPLEMENTAL_PROVIDER_IDS
import io.agedm.tv.data.isAgeSource
import io.agedm.tv.data.loadedSupplementalProviders
import io.agedm.tv.data.mergeDistinctSources
import io.agedm.tv.data.orderedByPriority
import io.agedm.tv.data.replaceSourcesForProvider
import kotlinx.coroutines.flow.collectLatest
import io.agedm.tv.databinding.ActivityDetailBinding
import io.agedm.tv.ui.adapter.EpisodeAdapter
import io.agedm.tv.ui.adapter.PosterCardAdapter
import io.agedm.tv.ui.adapter.SourceAdapter
import kotlinx.coroutines.launch

class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private lateinit var sourceAdapter: SourceAdapter
    private lateinit var episodeAdapter: EpisodeAdapter
    private lateinit var relatedAdapter: PosterCardAdapter
    private lateinit var similarAdapter: PosterCardAdapter
    private lateinit var bangumiSimilarAdapter: PosterCardAdapter

    private val app: AgeTvApplication
        get() = application as AgeTvApplication

    private var detail: AnimeDetail? = null
    private var selectedSourceIndex: Int = 0
    private var selectedEpisodeIndex: Int = 0
    private var previewSourceIndex: Int = 0
    private var previewEpisodeIndex: Int = 0
    private var supplementalSourceLoading = false
    private var sourceRefreshLoading = false
    private var sourceMatchDialogLoading = false
    private var bangumiCollectionStatus: BangumiCollectionStatus? = null
    private var bangumiCollectionLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLists()
        setupButtons()
        setupBackBehavior()
        collectIncomingRoutes()
        loadDetail()
    }

    override fun onResume() {
        super.onResume()
        app.linkCastManager.consumePendingRoute()?.let(::handleIncomingRoute)
        detail?.let(::refreshBangumiCollectionState)
    }

    private fun setupLists() {
        sourceAdapter = SourceAdapter(
            onSelected = ::onSourceSelected,
            onFocused = ::onSourceFocused,
            onAction = ::onLoadSupplementalSources,
        )
        episodeAdapter = EpisodeAdapter(::onEpisodeSelected)
        relatedAdapter = PosterCardAdapter { openDetail(it.animeId) }
        similarAdapter = PosterCardAdapter { openDetail(it.animeId) }
        bangumiSimilarAdapter = PosterCardAdapter { openDetail(it.animeId) }

        binding.sourceRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.sourceRecycler.adapter = sourceAdapter
        binding.sourceRecycler.itemAnimator = null

        binding.episodeRecycler.layoutManager = GridLayoutManager(this, 4)
        binding.episodeRecycler.adapter = episodeAdapter
        binding.episodeRecycler.itemAnimator = null

        binding.relatedRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.relatedRecycler.adapter = relatedAdapter
        binding.relatedRecycler.itemAnimator = null

        binding.similarRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.similarRecycler.adapter = similarAdapter
        binding.similarRecycler.itemAnimator = null

        binding.bangumiSimilarRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.bangumiSimilarRecycler.adapter = bangumiSimilarAdapter
        binding.bangumiSimilarRecycler.itemAnimator = null
    }

    private fun setupButtons() {
        binding.backButton.setOnClickListener { finish() }
        binding.homeButton.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
        binding.playButton.setOnClickListener {
            val loadedDetail = detail ?: return@setOnClickListener
            val record = app.playbackStore.getRecord(loadedDetail.animeId)
            if (record != null) {
                launchPlayer(
                    sourceIndex = selectedSourceIndexFor(record.sourceKey),
                    episodeIndex = record.episodeIndex,
                    preferredSourceKey = record.sourceKey,
                    resumePositionMs = record.positionMs,
                    preferResumePrompt = false,
                )
            } else {
                val source = loadedDetail.sources.getOrNull(selectedSourceIndex) ?: return@setOnClickListener
                val episode = source.episodes.getOrNull(selectedEpisodeIndex) ?: return@setOnClickListener
                launchPlayer(
                    sourceIndex = selectedSourceIndex,
                    episodeIndex = episode.index,
                    preferredSourceKey = source.key,
                )
            }
        }
        binding.refreshSourcesButton.setOnClickListener { refreshSources() }
        binding.continueButton.isVisible = false
        binding.bangumiWishButton.setOnClickListener { selectBangumiCollectionStatus(BangumiCollectionStatus.WISH) }
        binding.bangumiDoingButton.setOnClickListener { selectBangumiCollectionStatus(BangumiCollectionStatus.DO) }
        binding.bangumiCollectButton.setOnClickListener { selectBangumiCollectionStatus(BangumiCollectionStatus.COLLECT) }
        binding.bangumiOnHoldButton.setOnClickListener { selectBangumiCollectionStatus(BangumiCollectionStatus.ON_HOLD) }
        binding.bangumiDroppedButton.setOnClickListener { selectBangumiCollectionStatus(BangumiCollectionStatus.DROPPED) }
    }

    private fun setupBackBehavior() {
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }
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

    private fun loadDetail() {
        val animeId = intent.getLongExtra(EXTRA_ANIME_ID, 0L)
        if (animeId <= 0L) {
            showError("无效的动画 ID")
            return
        }
        lifecycleScope.launch {
            var showingCached = false
            val cached = runCatching { app.ageRepository.peekDetail(animeId) }.getOrNull()
            if (cached != null) {
                val ordered = cached.copy(
                    sources = cached.sources.orderedByPriority(app.playbackStore.getSourcePriority()),
                )
                detail = ordered
                bindDetail(ordered, requestFocus = true)
                showingCached = true
            } else {
                binding.loadingLayout.isVisible = true
                binding.errorText.isVisible = false
                binding.detailScrollView.isVisible = false
            }
            runCatching { app.ageRepository.fetchDetail(animeId) }
                .onSuccess { loadedDetail ->
                    val orderedDetail = loadedDetail.copy(
                        sources = loadedDetail.sources.orderedByPriority(app.playbackStore.getSourcePriority()),
                    )
                    detail = orderedDetail
                    bindDetail(orderedDetail, requestFocus = !showingCached)
                }
                .onFailure { error ->
                    if (!showingCached) {
                        showError("详情加载失败：${error.message.orEmpty()}")
                    }
                }
        }
    }

    private fun bindDetail(
        loadedDetail: AnimeDetail,
        requestFocus: Boolean = false,
        preferredSourceKey: String? = null,
        preferredEpisodeIndex: Int? = null,
    ) {
        binding.loadingLayout.isVisible = false
        binding.errorText.isVisible = false
        binding.detailScrollView.isVisible = true

        binding.pageTitle.text = loadedDetail.title
        binding.titleText.text = loadedDetail.title
        binding.metaText.text = listOf(loadedDetail.status, loadedDetail.tags)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        binding.tagsText.text = buildTagsLine(loadedDetail)
        binding.introText.text = htmlToPlainText(loadedDetail.introHtml).ifBlank { "暂无简介" }
        binding.coverImage.loadPosterImage(loadedDetail.cover)
        bindBangumiPanel(loadedDetail.bangumi)
        bangumiCollectionStatus = app.bangumiAccountService.cachedCollectionStatus(loadedDetail.animeId)
        bangumiCollectionLoading = false
        renderBangumiCollectionState()
        refreshBangumiCollectionState(loadedDetail)

        val record = app.playbackStore.getRecord(loadedDetail.animeId)
        val resolvedSourceKey = preferredSourceKey ?: record?.sourceKey ?: loadedDetail.sources.firstOrNull()?.key
        selectedSourceIndex = resolveSourceIndex(loadedDetail, resolvedSourceKey)
        val selectedSource = loadedDetail.sources.getOrNull(selectedSourceIndex)
        selectedEpisodeIndex = resolveEpisodeIndex(selectedSource, preferredEpisodeIndex)
        previewSourceIndex = selectedSourceIndex
        previewEpisodeIndex = selectedEpisodeIndex
        bindSelectionPanels(
            preferredSourceKey = loadedDetail.sources.getOrNull(selectedSourceIndex)?.key,
            previewSourceKey = loadedDetail.sources.getOrNull(previewSourceIndex)?.key,
        )

        relatedAdapter.submitList(loadedDetail.related.map(::toCard))
        similarAdapter.submitList(loadedDetail.similar.map(::toCard))
        binding.relatedTitle.isVisible = loadedDetail.related.isNotEmpty()
        binding.relatedRecycler.isVisible = loadedDetail.related.isNotEmpty()
        binding.similarTitle.isVisible = loadedDetail.similar.isNotEmpty()
        binding.similarRecycler.isVisible = loadedDetail.similar.isNotEmpty()

        binding.playButton.text = record?.let { "继续 ${it.episodeLabel}" }
            ?: getString(io.agedm.tv.R.string.btn_play_now)
        updateRefreshSourcesButton()
        if (requestFocus) {
            binding.playButton.requestFocus()
        }
    }

    private fun refreshBangumiCollectionState(loadedDetail: AnimeDetail) {
        if (!app.bangumiAccountService.isLoggedIn()) {
            bangumiCollectionLoading = false
            renderBangumiCollectionState()
            return
        }
        bangumiCollectionLoading = true
        renderBangumiCollectionState()
        lifecycleScope.launch {
            val status = runCatching {
                app.bangumiAccountService.fetchCollectionStatus(loadedDetail.animeId, loadedDetail.title)
            }.getOrNull()
            if (detail?.animeId != loadedDetail.animeId) return@launch
            bangumiCollectionLoading = false
            if (status != null) {
                bangumiCollectionStatus = status
            }
            renderBangumiCollectionState()
        }
    }

    private fun selectBangumiCollectionStatus(status: BangumiCollectionStatus) {
        val loadedDetail = detail ?: return
        if (!app.bangumiAccountService.isLoggedIn()) {
            Toast.makeText(this, "请先在设置中登录 Bangumi 账号", Toast.LENGTH_SHORT).show()
            return
        }
        bangumiCollectionStatus = status
        renderBangumiCollectionState()
        app.bangumiAccountService.enqueueManualStatusUpdate(
            animeId = loadedDetail.animeId,
            title = loadedDetail.title,
            status = status,
        )
        Toast.makeText(this, "已加入 Bangumi 同步队列：${status.label}", Toast.LENGTH_SHORT).show()
    }

    private fun renderBangumiCollectionState() {
        val current = bangumiCollectionStatus
        val account = app.bangumiAccountService.currentAccount()
        renderBangumiStatusButton(binding.bangumiWishButton, BangumiCollectionStatus.WISH, current)
        renderBangumiStatusButton(binding.bangumiDoingButton, BangumiCollectionStatus.DO, current)
        renderBangumiStatusButton(binding.bangumiCollectButton, BangumiCollectionStatus.COLLECT, current)
        renderBangumiStatusButton(binding.bangumiOnHoldButton, BangumiCollectionStatus.ON_HOLD, current)
        renderBangumiStatusButton(binding.bangumiDroppedButton, BangumiCollectionStatus.DROPPED, current)
        binding.bangumiCollectionHintText.text = when {
            account == null -> "登录 Bangumi 后可同步 想看 / 在看 / 看过 / 搁置 / 抛弃"
            bangumiCollectionLoading -> "正在读取 Bangumi 收藏状态..."
            current != null -> "Bangumi 当前状态：${current.label}"
            else -> "Bangumi 当前还没有这部动画的收藏记录"
        }
    }

    private fun renderBangumiStatusButton(
        button: android.widget.Button,
        status: BangumiCollectionStatus,
        current: BangumiCollectionStatus?,
    ) {
        button.text = if (current == status) "✓ ${status.label}" else status.label
    }

    private fun bindSelectionPanels(
        preferredSourceKey: String? = null,
        focusSourceKey: String? = null,
        previewSourceKey: String? = null,
        animatePreview: Boolean = false,
    ) {
        val loadedDetail = detail ?: return
        binding.pageSubtitle.text = "共 ${loadedDetail.sources.sumOf { it.episodes.size }} 个可选分集"

        selectedSourceIndex = resolveSourceIndex(loadedDetail, preferredSourceKey)
        val selectedSource = loadedDetail.sources.getOrNull(selectedSourceIndex)
        if (selectedSource == null) {
            sourceAdapter.submitList(emptyList(), null, sourceActionLabel(loadedDetail))
            episodeAdapter.submitList(emptyList(), 0)
            return
        }

        selectedEpisodeIndex = resolveEpisodeIndex(selectedSource, selectedEpisodeIndex)
        sourceAdapter.submitList(loadedDetail.sources, selectedSource.key, sourceActionLabel(loadedDetail))
        binding.sourceRecycler.scrollToPosition(selectedSourceIndex)

        val previousPreviewIndex = previewSourceIndex
        previewSourceIndex = resolveSourceIndex(loadedDetail, previewSourceKey ?: selectedSource.key)
        val previewSource = loadedDetail.sources.getOrNull(previewSourceIndex) ?: selectedSource
        previewEpisodeIndex = if (previewSource.key == selectedSource.key) {
            selectedEpisodeIndex
        } else {
            resolveEpisodeIndex(previewSource, previewEpisodeIndex)
        }
        renderEpisodePreview(
            source = previewSource,
            animate = animatePreview,
            direction = previewSourceIndex.compareTo(previousPreviewIndex).takeIf { it != 0 } ?: 1,
        )

        focusSourceKey?.let(::focusSourceKey)
    }

    private fun resolveSourceIndex(loadedDetail: AnimeDetail, preferredSourceKey: String?): Int {
        return preferredSourceKey
            ?.let { sourceKey ->
                loadedDetail.sources.indexOfFirst { it.key == sourceKey }
                    .takeIf { it >= 0 }
            }
            ?: selectedSourceIndex.coerceIn(0, loadedDetail.sources.lastIndex.coerceAtLeast(0))
    }

    private fun resolveEpisodeIndex(source: EpisodeSource?, preferredEpisodeIndex: Int? = null): Int {
        if (source == null || source.episodes.isEmpty()) return 0
        preferredEpisodeIndex?.let { index ->
            return index.coerceIn(0, source.episodes.lastIndex)
        }
        val record = detail?.animeId?.let(app.playbackStore::getRecord)
        if (record != null && record.sourceKey == source.key) {
            return record.episodeIndex.coerceIn(0, source.episodes.lastIndex)
        }
        return 0
    }

    private fun renderEpisodePreview(
        source: EpisodeSource,
        animate: Boolean,
        direction: Int,
    ) {
        val applyContent = {
            episodeAdapter.submitList(source.episodes, previewEpisodeIndex)
            binding.episodeRecycler.scrollToPosition(previewEpisodeIndex)
        }
        if (!animate || !binding.episodeRecycler.isLaidOut) {
            binding.episodeRecycler.translationX = 0f
            binding.episodeRecycler.alpha = 1f
            applyContent()
            return
        }

        val offset = if (direction >= 0) 44f else -44f
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

    private fun onLoadSupplementalSources() {
        if (supplementalSourceLoading) return
        val loadedDetail = detail ?: return
        supplementalSourceLoading = true
        bindSelectionPanels(preferredSourceKey = loadedDetail.sources.getOrNull(selectedSourceIndex)?.key)

        lifecycleScope.launch {
            runCatching {
                app.ageRepository.fetchSupplementalSources(
                    animeId = loadedDetail.animeId,
                    title = loadedDetail.title,
                )
            }.onSuccess { extraSources ->
                supplementalSourceLoading = false
                val currentDetail = detail?.takeIf { it.animeId == loadedDetail.animeId } ?: return@launch
                val existingKeys = currentDetail.sources.mapTo(linkedSetOf()) { it.key }
                val mergedSources = currentDetail.sources
                    .mergeDistinctSources(extraSources)
                    .orderedByPriority(app.playbackStore.getSourcePriority())
                detail = currentDetail.copy(sources = mergedSources)

                val firstNewKey = mergedSources.firstOrNull { it.key !in existingKeys }?.key
                if (firstNewKey != null) {
                    selectedSourceIndex = mergedSources.indexOfFirst { it.key == firstNewKey }.coerceAtLeast(0)
                    selectedEpisodeIndex = 0
                    bindSelectionPanels(preferredSourceKey = firstNewKey, focusSourceKey = firstNewKey)
                    Toast.makeText(this@DetailActivity, "已加载其他源", Toast.LENGTH_SHORT).show()
                } else {
                    bindSelectionPanels(preferredSourceKey = currentDetail.sources.getOrNull(selectedSourceIndex)?.key)
                    Toast.makeText(this@DetailActivity, "没有找到新的补充源", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                supplementalSourceLoading = false
                bindSelectionPanels(preferredSourceKey = detail?.sources?.getOrNull(selectedSourceIndex)?.key)
                Toast.makeText(
                    this@DetailActivity,
                    "其他源加载失败：${error.message.orEmpty()}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun refreshSources() {
        if (sourceRefreshLoading) return
        val currentDetail = detail ?: return
        sourceRefreshLoading = true
        updateRefreshSourcesButton()

        val preferredSourceKey = currentDetail.sources.getOrNull(selectedSourceIndex)?.key
        val preferredEpisodeIndex = selectedEpisodeIndex
        val shouldRefreshSupplemental = currentDetail.sources.loadedSupplementalProviders().isNotEmpty()

        lifecycleScope.launch {
            runCatching {
                val refreshedDetail = app.ageRepository.fetchDetail(
                    animeId = currentDetail.animeId,
                    forceRefresh = true,
                )
                val mergedSources = if (shouldRefreshSupplemental) {
                    refreshedDetail.sources.mergeDistinctSources(
                        app.ageRepository.fetchSupplementalSources(
                            animeId = currentDetail.animeId,
                            title = refreshedDetail.title,
                        ),
                    )
                } else {
                    refreshedDetail.sources
                }
                refreshedDetail.copy(
                    sources = mergedSources.orderedByPriority(app.playbackStore.getSourcePriority()),
                )
            }.onSuccess { refreshedDetail ->
                detail = refreshedDetail
                bindDetail(
                    loadedDetail = refreshedDetail,
                    requestFocus = false,
                    preferredSourceKey = preferredSourceKey,
                    preferredEpisodeIndex = preferredEpisodeIndex,
                )
                binding.refreshSourcesButton.requestFocus()
                Toast.makeText(this@DetailActivity, "已刷新播放源", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                updateRefreshSourcesButton()
                Toast.makeText(
                    this@DetailActivity,
                    "刷新源失败：${error.message.orEmpty()}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            sourceRefreshLoading = false
            updateRefreshSourcesButton()
        }
    }

    private fun updateRefreshSourcesButton() {
        binding.refreshSourcesButton.text = getString(
            if (sourceRefreshLoading) io.agedm.tv.R.string.btn_refreshing_sources
            else io.agedm.tv.R.string.btn_refresh_sources,
        )
        binding.refreshSourcesButton.isEnabled = !sourceRefreshLoading
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

    private fun onSourceFocused(source: EpisodeSource) {
        val loadedDetail = detail ?: return
        val index = loadedDetail.sources.indexOfFirst { it.key == source.key }
        if (index < 0 || index == previewSourceIndex) return

        val direction = index.compareTo(previewSourceIndex).takeIf { it != 0 } ?: 1
        previewSourceIndex = index
        previewEpisodeIndex = if (index == selectedSourceIndex) {
            selectedEpisodeIndex
        } else {
            resolveEpisodeIndex(source)
        }
        renderEpisodePreview(source = source, animate = true, direction = direction)
    }

    private fun onSourceSelected(source: EpisodeSource) {
        val loadedDetail = detail ?: return
        val index = loadedDetail.sources.indexOfFirst { it.key == source.key }
        if (index < 0) return
        selectedSourceIndex = index
        selectedEpisodeIndex = if (previewSourceIndex == index) {
            previewEpisodeIndex.coerceIn(0, source.episodes.lastIndex.coerceAtLeast(0))
        } else {
            resolveEpisodeIndex(source)
        }
        bindSelectionPanels(preferredSourceKey = source.key, previewSourceKey = source.key)

        if (!source.isAgeSource()) {
            showExternalMatchChooser(source)
        }
    }

    private fun onEpisodeSelected(episode: EpisodeItem) {
        val loadedDetail = detail ?: return
        val source = loadedDetail.sources.getOrNull(previewSourceIndex) ?: return
        selectedSourceIndex = previewSourceIndex
        selectedEpisodeIndex = episode.index
        previewEpisodeIndex = episode.index
        sourceAdapter.submitList(loadedDetail.sources, source.key, sourceActionLabel(loadedDetail))
        renderEpisodePreview(source = source, animate = false, direction = 1)
        launchPlayer(
            sourceIndex = previewSourceIndex,
            episodeIndex = episode.index,
            preferredSourceKey = source.key,
        )
    }

    private fun showExternalMatchChooser(source: EpisodeSource) {
        if (sourceMatchDialogLoading) return
        val loadedDetail = detail ?: return
        sourceMatchDialogLoading = true
        Toast.makeText(this, "正在检索 ${source.providerName} 对应动画...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            runCatching {
                app.ageRepository.searchSupplementalCandidates(
                    providerId = source.providerName,
                    title = loadedDetail.title,
                )
            }.onSuccess { rawCandidates ->
                sourceMatchDialogLoading = false
                val candidates = rawCandidates
                    .sortedByDescending { candidate -> if (candidate.title == source.matchTitle) 1 else 0 }
                if (candidates.isEmpty()) {
                    Toast.makeText(this@DetailActivity, "没有找到可更正的候选动画", Toast.LENGTH_SHORT).show()
                    return@onSuccess
                }

                val labels = candidates.map { candidate ->
                    if (candidate.title == source.matchTitle) "${candidate.title}（当前自动匹配）" else candidate.title
                }.toTypedArray()
                val checkedIndex = candidates.indexOfFirst { it.title == source.matchTitle }.coerceAtLeast(0)

                MaterialAlertDialogBuilder(this@DetailActivity)
                    .setTitle("选择 ${source.providerName} 对应动画")
                    .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                        dialog.dismiss()
                        applyExternalMatchChoice(source, candidates[which])
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }.onFailure { error ->
                sourceMatchDialogLoading = false
                Toast.makeText(
                    this@DetailActivity,
                    "检索候选动画失败：${error.message.orEmpty()}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun applyExternalMatchChoice(source: EpisodeSource, candidate: io.agedm.tv.data.SupplementalCandidate) {
        val currentDetail = detail ?: return
        Toast.makeText(this, "正在切换到 ${candidate.title}", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            runCatching {
                app.ageRepository.fetchSupplementalSourcesForCandidate(
                    animeId = currentDetail.animeId,
                    candidate = candidate,
                )
            }.onSuccess { providerSources ->
                if (providerSources.isEmpty()) {
                    Toast.makeText(this@DetailActivity, "没有找到可用播放列表", Toast.LENGTH_SHORT).show()
                    return@onSuccess
                }
                val refreshedDetail = detail?.takeIf { it.animeId == currentDetail.animeId } ?: return@onSuccess
                val mergedSources = refreshedDetail.sources
                    .replaceSourcesForProvider(source.providerName, providerSources)
                    .orderedByPriority(app.playbackStore.getSourcePriority())
                detail = refreshedDetail.copy(sources = mergedSources)

                val targetKey = providerSources.first().key
                selectedSourceIndex = mergedSources.indexOfFirst { it.key == targetKey }.coerceAtLeast(0)
                selectedEpisodeIndex = 0
                previewSourceIndex = selectedSourceIndex
                previewEpisodeIndex = 0
                bindSelectionPanels(
                    preferredSourceKey = targetKey,
                    focusSourceKey = targetKey,
                    previewSourceKey = targetKey,
                )
                Toast.makeText(this@DetailActivity, "已切换到 ${candidate.title}", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(
                    this@DetailActivity,
                    "切换匹配动画失败：${error.message.orEmpty()}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun selectedSourceIndexFor(sourceKey: String): Int {
        return detail?.sources?.indexOfFirst { it.key == sourceKey }?.takeIf { it >= 0 } ?: 0
    }

    private fun launchPlayer(
        sourceIndex: Int,
        episodeIndex: Int,
        preferredSourceKey: String? = null,
        resumePositionMs: Long = 0L,
        preferResumePrompt: Boolean = true,
    ) {
        val loadedDetail = detail ?: return
        startActivity(
            PlayerActivity.createIntent(
                context = this,
                animeId = loadedDetail.animeId,
                sourceIndex = sourceIndex + 1,
                episodeIndex = episodeIndex + 1,
                preferredSourceKey = preferredSourceKey,
                resumePositionMs = resumePositionMs,
                preferResumePrompt = preferResumePrompt,
            ),
        )
    }

    private fun openDetail(animeId: Long) {
        startActivity(createIntent(this, animeId))
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
                startActivity(createIntent(this, route.animeId))
                finish()
            }

            is AgeRoute.Play -> {
                startActivity(
                    PlayerActivity.createIntent(
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

    private fun showError(message: String) {
        binding.loadingLayout.isVisible = false
        binding.detailScrollView.isVisible = false
        binding.errorText.isVisible = true
        binding.errorText.text = message
    }

    private fun bindBangumiPanel(metadata: BangumiMetadata?) {
        if (metadata == null) {
            binding.bangumiPanel.isVisible = false
            return
        }

        binding.bangumiPanel.isVisible = true
        val summaryParts = buildList {
            metadata.scoreLabel?.let { add("Bangumi $it") }
            metadata.voteCount.takeIf { it > 0 }?.let { add("${it}人评分") }
            metadata.rank?.let { add("#$it") }
        }
        binding.bangumiSummaryText.text = summaryParts.joinToString(" · ")
            .ifBlank { getString(io.agedm.tv.R.string.bangumi_empty) }

        binding.bangumiDistributionText.text = metadata.ratingCounts.entries
            .sortedByDescending { it.key }
            .joinToString("  ") { (score, count) -> "${score}分 $count" }
            .ifBlank { "评分分布暂缺" }

        binding.bangumiTagsText.text = metadata.tags
            .joinToString(" · ") { tag -> "${tag.name} ${tag.count}" }
            .ifBlank { "标签暂缺" }

        binding.bangumiStaffText.text = metadata.staff
            .joinToString("\n") { role ->
                "${role.role} · ${role.names.joinToString("、")}"
            }
            .ifBlank { "制作人员信息暂缺" }

        bindBangumiComments(metadata.comments)
        bangumiSimilarAdapter.submitList(metadata.similar.map(::toCard))
        binding.bangumiSimilarTitle.isVisible = metadata.similar.isNotEmpty()
        binding.bangumiSimilarRecycler.isVisible = metadata.similar.isNotEmpty()
    }

    private fun bindBangumiComments(comments: List<BangumiComment>) {
        binding.bangumiCommentsContainer.removeAllViews()
        binding.bangumiCommentsTitle.isVisible = comments.isNotEmpty()
        binding.bangumiCommentsContainer.isVisible = comments.isNotEmpty()
        comments.forEachIndexed { index, comment ->
            val view = TextView(this).apply {
                setBackgroundResource(io.agedm.tv.R.drawable.bg_panel_soft)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setTextColor(getColor(io.agedm.tv.R.color.age_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setLineSpacing(0f, 1.12f)
                text = buildCommentText(comment)
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (index > 0) topMargin = dp(8)
            }
            binding.bangumiCommentsContainer.addView(view, params)
        }
    }

    private fun buildCommentText(comment: BangumiComment): String {
        val headline = buildList {
            add(comment.user)
            comment.state.takeIf { it.isNotBlank() }?.let(::add)
            comment.time.takeIf { it.isNotBlank() }?.let(::add)
            comment.score?.let { add("${it}分") }
        }.joinToString(" · ")
        return "$headline\n${comment.content}"
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun buildTagsLine(detail: AnimeDetail): String {
        val tagText = detail.tags.replace(" ", " · ")
        return if (tagText.isBlank()) {
            "暂无标签"
        } else {
            tagText
        }
    }

    private fun toCard(item: AgeRelatedItem): AnimeCard {
        return AnimeCard(
            animeId = item.animeId,
            title = item.title,
            cover = item.cover,
            badge = item.updateLabel,
            subtitle = "相关作品",
        )
    }

    companion object {
        private const val EXTRA_ANIME_ID = "extra_anime_id"

        fun createIntent(context: Context, animeId: Long): Intent {
            return Intent(context, DetailActivity::class.java)
                .putExtra(EXTRA_ANIME_ID, animeId)
        }
    }
}
