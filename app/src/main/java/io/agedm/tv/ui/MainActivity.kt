package io.agedm.tv.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable

import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.R
import io.agedm.tv.data.AgeLinks
import io.agedm.tv.data.AgeRoute
import io.agedm.tv.data.AnimeCard
import io.agedm.tv.data.BangumiCollectionStatus
import io.agedm.tv.data.BrowseSection
import io.agedm.tv.data.CatalogQuery
import io.agedm.tv.data.MirrorState
import io.agedm.tv.data.PagedCards
import io.agedm.tv.data.PlaybackRecord
import io.agedm.tv.databinding.ActivityMainBinding
import io.agedm.tv.ui.adapter.BrowseSectionAdapter
import io.agedm.tv.ui.adapter.PosterCardAdapter
import java.util.Calendar
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : AppCompatActivity() {

    private enum class Screen {
        CAST,
        HOME,
        CATALOG,
        RECOMMEND,
        UPDATE,
        RANK,
        HISTORY,
        SEARCH,
    }

    private data class FilterOption(
        val label: String,
        val value: String,
    )

    private data class FilterAction(
        val title: String,
        val options: List<FilterOption>,
        val labelProvider: () -> String,
        val onSelected: (String) -> Unit,
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var sectionAdapter: BrowseSectionAdapter
    private lateinit var gridAdapter: PosterCardAdapter
    private lateinit var mirrorAdapter: PosterCardAdapter

    private val app: AgeTvApplication
        get() = application as AgeTvApplication

    private var currentScreen: Screen = Screen.HOME
    private var currentSearchQuery: String? = null
    private var currentPage: Int = 1
    private var currentPageSize: Int = 30
    private var currentTotal: Int = 0
    private var catalogQuery = CatalogQuery()
    private var rankYear = "all"
    private var navIndicator: View? = null
    private var loadJob: Job? = null
    private var appendJob: Job? = null
    private var loadRequestId: Long = 0L
    private var overlayJob: Job? = null
    private var focusNavJob: Job? = null
    private var navFocusSwitchArmDeadlineMs: Long = 0L
    private var lastNavUpPressUptimeMs: Long = 0L
    private var slideFromRight = true
    private var pendingFocusRestoreViewId: Int? = null
    private var pendingFocusRestoreAnimeId: Long? = null
    private var selectionMode = false
    private var currentVisibleCards: List<AnimeCard> = emptyList()
    private var isAppendingGridPage = false
    private val selectedAnimeIds = linkedSetOf<Long>()
    private val navButtons: List<Button>
        get() = listOf(
            binding.navCastButton,
            binding.navHomeButton,
            binding.navCatalogButton,
            binding.navRecommendButton,
            binding.navUpdateButton,
            binding.navRankButton,
            binding.navHistoryButton,
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecycler()
        setupChrome()
        setupBottomNav()
        setupBackBehavior()
        collectIncomingRoutes()

        if (!handleIntent(intent)) {
            openScreen(Screen.HOME)
            binding.navHomeButton.post { binding.navHomeButton.requestFocus() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateHistoryNavLabel()
        if (currentScreen == Screen.HISTORY) {
            loadHistory()
        }
        app.linkCastManager.consumePendingRoute()?.let { route ->
            showOverlayMessage("已收到手机投送：${AgeLinks.describe(route)}")
            openRoute(route)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // When a dialog or another activity closes, Android restores focus to the activity
        // window. If the previously-focused view no longer exists (e.g. because the content
        // was reloaded while the dialog was open), focus can land on the wrong nav button
        // and trigger focusNavJob to switch screens. Cancel the job and correct focus here.
        focusNavJob?.cancel()
        clearNavFocusSwitchArm()
        binding.root.post {
            if (restorePendingAnimeFocusIfPossible()) return@post
            if (restorePendingFocusIfPossible()) return@post
            val focused = currentFocus
            if (focused == null) {
                requestBestContentFocus()
                return@post
            }
            if (isInNavArea(focused) && focused.id != currentNavButton().id) {
                requestBestContentFocus()
            }
        }
    }

    private fun setupRecycler() {
        sectionAdapter = BrowseSectionAdapter(::openDetail)
        gridAdapter = PosterCardAdapter(::openDetail)
        mirrorAdapter = PosterCardAdapter(::openDetail)
        sectionAdapter.onSelectionToggle = ::toggleCardSelection
        gridAdapter.onSelectionToggle = ::toggleCardSelection
        binding.contentRecycler.itemAnimator = null
        binding.contentRecycler.layoutManager = LinearLayoutManager(this)
        binding.contentRecycler.adapter = sectionAdapter
        binding.mirrorRecycler.itemAnimator = null
        binding.mirrorRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.mirrorRecycler.adapter = mirrorAdapter
    }

    private fun setupChrome() {
        binding.paginationBar.isVisible = false
    }

    private fun setupBottomNav() {
        binding.navCastButton.setOnClickListener { openScreen(Screen.CAST) }
        binding.navHomeButton.setOnClickListener { openScreen(Screen.HOME) }
        binding.navCatalogButton.setOnClickListener { openScreen(Screen.CATALOG) }
        binding.navRecommendButton.setOnClickListener { openScreen(Screen.RECOMMEND) }
        binding.navUpdateButton.setOnClickListener { openScreen(Screen.UPDATE) }
        binding.navRankButton.setOnClickListener { openScreen(Screen.RANK) }
        binding.navHistoryButton.setOnClickListener { openScreen(Screen.HISTORY) }

        val focusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) return@OnFocusChangeListener
            focusNavJob?.cancel()
            if (!consumeNavFocusSwitchArm()) return@OnFocusChangeListener
            val screen = when (view.id) {
                R.id.navCastButton -> Screen.CAST
                R.id.navHomeButton -> Screen.HOME
                R.id.navCatalogButton -> Screen.CATALOG
                R.id.navRecommendButton -> Screen.RECOMMEND
                R.id.navUpdateButton -> Screen.UPDATE
                R.id.navRankButton -> Screen.RANK
                R.id.navHistoryButton -> Screen.HISTORY
                else -> return@OnFocusChangeListener
            }
            if (screen == currentScreen) return@OnFocusChangeListener
            focusNavJob = lifecycleScope.launch {
                delay(NAV_FOCUS_DELAY_MS)
                if (view.isFocused) openScreen(screen)
            }
        }
        listOf(
            binding.navCastButton, binding.navHomeButton, binding.navCatalogButton, binding.navRecommendButton,
            binding.navUpdateButton, binding.navRankButton, binding.navHistoryButton,
        ).forEach { it.onFocusChangeListener = focusListener }

        configureNavWrapAround()
        setupNavIndicator()
        updateBottomNav()
    }

    private fun setupBackBehavior() {
        onBackPressedDispatcher.addCallback(this) {
            navigateBack()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && focused != null && isInNavArea(focused)) {
                clearNavFocusSwitchArm()
                val now = SystemClock.elapsedRealtime()
                if (now - lastNavUpPressUptimeMs <= SETTINGS_SHORTCUT_WINDOW_MS) {
                    lastNavUpPressUptimeMs = 0L
                    startActivity(SettingsActivity.createIntent(this))
                } else {
                    lastNavUpPressUptimeMs = now
                    showOverlayMessage("连按 2 次向上打开设置")
                }
                return true
            }
            if (focused != null && isInNavArea(focused) &&
                (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            ) {
                armNavFocusSwitch()
            } else {
                clearNavFocusSwitchArm()
            }
            if (event.keyCode != KeyEvent.KEYCODE_DPAD_UP || focused == null || !isInNavArea(focused)) {
                lastNavUpPressUptimeMs = 0L
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && focused != null && routeUpToCurrentNavIfNeeded(focused)) {
                return true
            }
            if (focused != null && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && handleInfiniteGridDownPress(focused)) {
                return true
            }
            if (focused != null && shouldBlockEdgeNavigation(focused, event.keyCode)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun configureNavWrapAround() {
        navButtons.forEachIndexed { index, button ->
            val left = navButtons[(index - 1 + navButtons.size) % navButtons.size]
            val right = navButtons[(index + 1) % navButtons.size]
            button.nextFocusLeftId = left.id
            button.nextFocusRightId = right.id
        }
    }

    private fun collectIncomingRoutes() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.linkCastManager.incomingRoutes.collectLatest { route ->
                    app.linkCastManager.consumePendingRoute()
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

    private fun openRoute(route: AgeRoute) {
        when (route) {
            AgeRoute.Home -> openScreen(Screen.HOME)
            is AgeRoute.Detail -> openDetail(route.animeId)
            is AgeRoute.Play -> launchPlayer(route)
            is AgeRoute.Search -> {
                if (route.query.isNullOrBlank()) {
                    showSearchDialog()
                } else {
                    currentSearchQuery = route.query
                    openScreen(Screen.SEARCH, page = 1)
                }
            }

            is AgeRoute.Web -> when {
                route.url.contains("/catalog") -> openScreen(Screen.CATALOG)
                route.url.contains("/recommend") -> openScreen(Screen.RECOMMEND)
                route.url.contains("/update") -> openScreen(Screen.UPDATE)
                route.url.contains("/rank") -> openScreen(Screen.RANK)
                route.url.contains("/history") -> openScreen(Screen.HISTORY)
                else -> openScreen(Screen.HOME)
            }
        }
    }

    private fun openScreen(screen: Screen, page: Int = 1) {
        clearNavFocusSwitchArm()
        exitSelectionMode(silent = true)
        slideFromRight = screen.navIndex() >= currentScreen.navIndex()
        currentScreen = screen
        currentPage = page.coerceAtLeast(1)
        currentPageSize = when (screen) {
            Screen.CAST -> 0
            Screen.SEARCH -> 24
            Screen.RECOMMEND -> 100
            else -> 30
        }
        updateBottomNav()
        when (screen) {
            Screen.CAST -> loadCast()
            Screen.HOME -> loadHome()
            Screen.CATALOG -> loadCatalog()
            Screen.RECOMMEND -> loadRecommend()
            Screen.UPDATE -> loadUpdate()
            Screen.RANK -> loadRank()
            Screen.HISTORY -> loadHistory()
            Screen.SEARCH -> loadSearch()
        }
    }

    private fun loadCast() {
        val requestId = replaceLoadRequest()
        applyFilterActions(emptyList(), showReset = false)
        updatePagination(visible = false)
        currentTotal = 0

        val bitmap = app.castQr.value
        if (bitmap != null) {
            prepareBrowseContent()
            applyCastBitmap(requestId, bitmap)
            return
        }

        renderLoading("正在启动投送服务...")
        loadJob = lifecycleScope.launch {
            val bm = withTimeoutOrNull(15_000) { app.castQr.filterNotNull().first() }
            if (!shouldHandleLoadResult(requestId)) return@launch
            if (bm != null) {
                applyCastBitmap(requestId, bm)
            } else {
                showError("投送服务启动失败：${app.linkCastManager.status.value}")
            }
        }
    }

    private fun applyCastBitmap(requestId: Long, bitmap: Bitmap) {
        if (!shouldHandleLoadResult(requestId)) return
        binding.loadingLayout.isVisible = false
        binding.contentRecycler.isVisible = false
        binding.castContent.isVisible = true
        binding.castQrImage.setImageBitmap(bitmap)
        binding.castErrorText.isVisible = false
        updateFocusTargets()
        animateContentIn(binding.castContent)
        loadJob = lifecycleScope.launch {
            app.linkCastManager.mirrorState.collect { state -> updateMirrorPanel(state) }
        }
    }

    private fun loadHome() {
        applyFilterActions(emptyList(), showReset = false)
        updatePagination(visible = false)
        currentTotal = 0
        launchStaleFirstLoad(
            loadingMessage = "正在整理首页...",
            errorPrefix = "首页加载失败",
            peek = { app.ageRepository.peekHomeFeed() },
            fetch = { app.ageRepository.fetchHomeFeed() },
        ) { feed, animate ->
            showSections(buildHomeSections(feed), emptyMessage = "首页暂时没有内容", animate = animate)
        }
    }

    private fun loadCatalog() {
        catalogQuery = catalogQuery.copy(page = 1, size = 30)
        applyFilterActions(catalogFilterActions(), showReset = true) {
            catalogQuery = CatalogQuery()
            openScreen(Screen.CATALOG)
        }
        val query = catalogQuery
        loadPagedGridFirstPage(
            loadingMessage = "正在加载目录...",
            errorPrefix = "目录加载失败",
            emptyMessage = "当前筛选下没有结果",
            peek = { app.ageRepository.peekCatalog(query) },
            fetch = { app.ageRepository.fetchCatalog(query) },
        )
    }

    private fun loadRecommend() {
        applyFilterActions(emptyList(), showReset = false)
        updatePagination(visible = false)
        launchStaleFirstLoad(
            loadingMessage = "正在加载推荐...",
            errorPrefix = "推荐加载失败",
            peek = { app.ageRepository.peekRecommend() },
            fetch = { app.ageRepository.fetchRecommend() },
        ) { result, animate ->
            currentTotal = result.total
            currentPageSize = result.size
            showGrid(result.items, emptyMessage = "推荐区暂时没有内容", animate = animate)
        }
    }

    private fun loadUpdate() {
        applyFilterActions(emptyList(), showReset = false)
        loadPagedGridFirstPage(
            loadingMessage = "正在加载更新...",
            errorPrefix = "更新加载失败",
            emptyMessage = "更新区暂时没有内容",
            peek = { app.ageRepository.peekUpdate(page = 1, size = 30) },
            fetch = { app.ageRepository.fetchUpdate(page = 1, size = 30) },
        )
    }

    private fun loadRank() {
        applyFilterActions(rankFilterActions(), showReset = false)
        updatePagination(visible = false)
        val year = rankYear
        launchStaleFirstLoad(
            loadingMessage = "正在加载排行...",
            errorPrefix = "排行加载失败",
            peek = { app.ageRepository.peekRankSections(year) },
            fetch = { app.ageRepository.fetchRankSections(year) },
        ) { sections, animate ->
            showSections(sections, emptyMessage = "排行榜暂时没有内容", animate = animate)
        }
    }

    private fun loadHistory() {
        loadLocalHistory()
    }

    private fun loadLocalHistory() {
        gridAdapter.onLongClick = { card ->
            MaterialAlertDialogBuilder(this)
                .setTitle("删除记录")
                .setMessage("删除「${card.title}」的观看记录？")
                .setPositiveButton("删除") { _, _ ->
                    app.playbackStore.deleteRecord(card.animeId)
                    loadHistory()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        renderLoading("正在读取观看记录...")
        applyFilterActions(emptyList(), showReset = false)
        updatePagination(visible = false)
        replaceLoadRequest()
        val history = app.playbackStore.getRecentRecords(60).map(::recordToCard)
        currentTotal = history.size
        currentPageSize = history.size.coerceAtLeast(1)
        showGrid(history, emptyMessage = "还没有播放记录")
        gridAdapter.onLongClick = { card ->
            MaterialAlertDialogBuilder(this)
                .setTitle("删除记录")
                .setMessage("删除「${card.title}」的观看记录？")
                .setPositiveButton("删除") { _, _ ->
                    app.playbackStore.deleteRecord(card.animeId)
                    loadHistory()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun loadMyPage() {
        renderLoading("正在加载我的 Bangumi...")
        applyFilterActions(emptyList(), showReset = false)
        updatePagination(visible = false)
        val requestId = replaceLoadRequest()
        loadJob = lifecycleScope.launch {
            runCatching { app.bangumiAccountService.fetchMyPage() }
                .onSuccess { page ->
                    if (!shouldHandleLoadResult(requestId)) return@launch
                    val localHistory = myPageFallbackSections()
                    val sections = buildList {
                        page?.sections?.let(::addAll)
                        addAll(localHistory)
                    }
                    currentTotal = sections.sumOf { it.items.size }
                    currentPageSize = currentTotal.coerceAtLeast(1)
                    showSections(
                        sections = sections,
                        emptyMessage = if (page == null) "Bangumi 个人页暂时不可用" else "还没有可展示的收藏内容",
                    )
                    if (page == null && sections.isNotEmpty()) {
                        showOverlayMessage("Bangumi 个人页加载失败，已回退到本地记录")
                    }
                }
                .onFailure { error ->
                    if (!shouldHandleLoadResult(requestId)) return@launch
                    val fallbackSections = myPageFallbackSections()
                    if (fallbackSections.isNotEmpty()) {
                        currentTotal = fallbackSections.sumOf { it.items.size }
                        currentPageSize = currentTotal.coerceAtLeast(1)
                        showSections(
                            sections = fallbackSections,
                            emptyMessage = "Bangumi 个人页加载失败，已回退到本地记录",
                        )
                        showOverlayMessage(
                            if (error.message.isNullOrBlank()) {
                                "Bangumi 个人页加载失败，已回退到本地记录"
                            } else {
                                "Bangumi 个人页加载失败：${error.message.orEmpty()}"
                            },
                        )
                    } else {
                        handleLoadFailure(requestId, "我的页面加载失败", error)
                    }
                }
        }
    }

    private fun myPageFallbackSections(): List<BrowseSection> {
        val localHistory = app.playbackStore.getRecentRecords(12).map(::recordToCard)
        if (localHistory.isEmpty()) return emptyList()
        return listOf(BrowseSection("本地继续观看", "电视端播放记录", localHistory))
    }

    private fun loadSearch() {
        val query = currentSearchQuery?.trim().orEmpty()
        if (query.isBlank()) {
            showSearchDialog()
            return
        }
        applyFilterActions(emptyList(), showReset = false)
        loadPagedGridFirstPage(
            loadingMessage = "正在搜索「$query」...",
            errorPrefix = "搜索失败",
            emptyMessage = "没有搜索到相关动画",
            peek = { app.ageRepository.peekSearch(query = query, page = 1, size = 24) },
            fetch = { app.ageRepository.search(query = query, page = 1, size = 24) },
        )
    }

    private fun loadPagedGridFirstPage(
        loadingMessage: String,
        errorPrefix: String,
        emptyMessage: String,
        peek: suspend () -> PagedCards?,
        fetch: suspend () -> PagedCards,
    ) {
        updatePagination(visible = false)
        val requestId = replaceLoadRequest()
        currentPage = 1
        currentTotal = 0
        isAppendingGridPage = false
        loadJob = lifecycleScope.launch {
            var showingCached = false
            try {
                val cached = peek()
                if (!shouldHandleLoadResult(requestId)) return@launch
                if (cached != null) {
                    applyPagedGridResult(cached, emptyMessage = emptyMessage, animate = true, append = false)
                    showingCached = true
                    delay(STALE_REFRESH_DELAY_MS)
                    if (!shouldHandleLoadResult(requestId)) return@launch
                } else {
                    renderLoading(loadingMessage)
                }

                val fresh = fetch()
                if (!shouldHandleLoadResult(requestId)) return@launch
                applyPagedGridResult(fresh, emptyMessage = emptyMessage, animate = !showingCached, append = false)
            } catch (error: Throwable) {
                handleLoadFailure(requestId, errorPrefix, error, preserveContent = showingCached)
            }
        }
    }

    private fun <T> launchStaleFirstLoad(
        loadingMessage: String,
        errorPrefix: String,
        peek: suspend () -> T?,
        fetch: suspend () -> T,
        render: (T, animate: Boolean) -> Unit,
    ) {
        val requestId = replaceLoadRequest()
        loadJob = lifecycleScope.launch {
            var showingCached = false
            try {
                val cached = peek()
                if (!shouldHandleLoadResult(requestId)) return@launch
                if (cached != null) {
                    render(cached, true)
                    showingCached = true
                    delay(STALE_REFRESH_DELAY_MS)
                    if (!shouldHandleLoadResult(requestId)) return@launch
                } else {
                    renderLoading(loadingMessage)
                }

                val fresh = fetch()
                if (!shouldHandleLoadResult(requestId)) return@launch
                render(fresh, !showingCached)
            } catch (error: Throwable) {
                handleLoadFailure(requestId, errorPrefix, error, preserveContent = showingCached)
            }
        }
    }

    private fun applyPagedGridResult(
        result: PagedCards,
        emptyMessage: String,
        animate: Boolean,
        append: Boolean,
    ) {
        currentTotal = result.total
        currentPage = result.page.coerceAtLeast(1)
        currentPageSize = result.size.coerceAtLeast(1)
        if (!append) {
            showGrid(result.items, emptyMessage = emptyMessage, animate = animate)
            return
        }
        appendGrid(result.items, emptyMessage)
    }

    private fun buildHomeSections(feed: io.agedm.tv.data.HomeFeed): List<BrowseSection> {
        return buildList {
            val todaySection = feed.dailySections.firstOrNull { it.title.startsWith(todayWeekdayPrefix()) }
            todaySection?.let { add(BrowseSection("今日更新", it.subtitle, it.items.take(12))) }
            add(BrowseSection("最近更新", "AGE 首页最新上架", feed.latest.take(12)))
            add(BrowseSection("每日推荐", "站内推荐作品", feed.recommend.take(12)))
            addAll(
                feed.dailySections
                    .filterNot { it.title.startsWith(todayWeekdayPrefix()) }
                    .take(4)
                    .map { it.copy(items = it.items.take(12)) },
            )
        }
    }

    private fun showSections(sections: List<BrowseSection>, emptyMessage: String, animate: Boolean = true) {
        prepareBrowseContent()
        binding.loadingLayout.isVisible = false
        binding.contentRecycler.isVisible = true
        binding.contentRecycler.layoutManager = LinearLayoutManager(this)
        binding.contentRecycler.adapter = sectionAdapter
        currentVisibleCards = sections.flatMap { it.items }.distinctBy { it.animeId }
        configureBrowseSelection()
        sectionAdapter.submitList(sections)
        binding.emptyStateText.isVisible = sections.isEmpty()
        binding.emptyStateText.text = emptyMessage
        updateFocusTargets()
        if (animate) animateContentIn(binding.contentRecycler)
    }

    private fun showGrid(items: List<AnimeCard>, emptyMessage: String, animate: Boolean = true) {
        prepareBrowseContent()
        binding.loadingLayout.isVisible = false
        binding.contentRecycler.isVisible = true
        ensureGridRecycler()
        currentVisibleCards = items.distinctBy { it.animeId }
        configureBrowseSelection()
        gridAdapter.submitList(currentVisibleCards)
        binding.emptyStateText.isVisible = currentVisibleCards.isEmpty()
        binding.emptyStateText.text = emptyMessage
        updateFocusTargets()
        if (animate) animateContentIn(binding.contentRecycler)
    }

    private fun appendGrid(items: List<AnimeCard>, emptyMessage: String) {
        prepareBrowseContent()
        binding.loadingLayout.isVisible = false
        binding.contentRecycler.isVisible = true
        ensureGridRecycler()
        val existingIds = currentVisibleCards.asSequence().map { it.animeId }.toHashSet()
        val appendedItems = items
            .asSequence()
            .filter { it.animeId !in existingIds }
            .distinctBy { it.animeId }
            .toList()
        if (appendedItems.isNotEmpty()) {
            currentVisibleCards = currentVisibleCards + appendedItems
            gridAdapter.appendList(appendedItems)
        }
        binding.emptyStateText.isVisible = currentVisibleCards.isEmpty()
        binding.emptyStateText.text = emptyMessage
        updateFocusTargets()
    }

    private fun ensureGridRecycler() {
        val gridLayout = binding.contentRecycler.layoutManager as? GridLayoutManager
        if (gridLayout == null || gridLayout.spanCount != GRID_SPAN_COUNT) {
            binding.contentRecycler.layoutManager = GridLayoutManager(this, GRID_SPAN_COUNT)
        }
        if (binding.contentRecycler.adapter !== gridAdapter) {
            binding.contentRecycler.adapter = gridAdapter
        }
    }

    private fun prepareBrowseContent() {
        binding.castContent.isVisible = false
        binding.castErrorText.isVisible = false
        binding.castQrImage.setImageDrawable(null)
    }

    private fun animateContentIn(target: View) {
        target.animate().cancel()
        val width = binding.contentStage.width
            .takeIf { it > 0 }?.toFloat()
            ?: resources.displayMetrics.widthPixels.toFloat()
        val offset = width * 0.18f
        target.alpha = 0.88f
        target.translationX = if (slideFromRight) offset else -offset
        target.post {
            target.animate().cancel()
            target.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(260)
                .setInterpolator(DecelerateInterpolator(2f))
                .start()
        }
    }

    private fun Screen.navIndex() = when (this) {
        Screen.CAST -> 0
        Screen.HOME -> 1
        Screen.CATALOG -> 2
        Screen.RECOMMEND -> 3
        Screen.UPDATE -> 4
        Screen.RANK -> 5
        Screen.HISTORY -> 6
        Screen.SEARCH -> -1
    }

    private fun renderLoading(message: String) {
        prepareBrowseContent()
        binding.loadingText.text = message
        binding.loadingLayout.isVisible = true
        binding.emptyStateText.isVisible = false
        binding.contentRecycler.isVisible = true
    }

    private fun showError(message: String) {
        prepareBrowseContent()
        binding.loadingLayout.isVisible = false
        binding.emptyStateText.text = message
        binding.emptyStateText.isVisible = true
        binding.contentRecycler.isVisible = true
    }

    private fun replaceLoadRequest(): Long {
        loadJob?.cancel()
        appendJob?.cancel()
        isAppendingGridPage = false
        return ++loadRequestId
    }

    private fun shouldHandleLoadResult(requestId: Long): Boolean {
        return requestId == loadRequestId
    }

    private fun handleLoadFailure(
        requestId: Long,
        prefix: String,
        error: Throwable,
        preserveContent: Boolean = false,
    ) {
        if (requestId != loadRequestId || isCancellationError(error)) return
        val message = if (error.message.isNullOrBlank()) prefix else "$prefix：${error.message.orEmpty()}"
        if (preserveContent) {
            showOverlayMessage(message)
        } else {
            showError(message)
        }
    }

    private fun isCancellationError(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            if (current is CancellationException) return true
            if (current.message?.contains("was cancelled", ignoreCase = true) == true) return true
            current = current.cause
        }
        return false
    }

    private fun updateBottomNav() {
        updateHistoryNavLabel()
        binding.navCastButton.isSelected = currentScreen == Screen.CAST
        binding.navHomeButton.isSelected = currentScreen == Screen.HOME
        binding.navCatalogButton.isSelected = currentScreen == Screen.CATALOG
        binding.navRecommendButton.isSelected = currentScreen == Screen.RECOMMEND
        binding.navUpdateButton.isSelected = currentScreen == Screen.UPDATE
        binding.navRankButton.isSelected = currentScreen == Screen.RANK
        binding.navHistoryButton.isSelected = currentScreen == Screen.HISTORY
        if (currentScreen != Screen.SEARCH) slideNavIndicatorTo(currentNavButton())
    }

    private fun setupNavIndicator() {
        val indicatorHeight = dpToPx(3)
        val pill = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#6ED9B8"))
                cornerRadius = dpToPx(2).toFloat()
            }
        }
        navIndicator = pill
        binding.bottomNav.post {
            val rect = buttonBoundsInNav(currentNavButton())
            if (rect.width() > 0) {
                pill.layout(rect.left, rect.bottom, rect.right, rect.bottom + indicatorHeight)
                binding.bottomNav.overlay.add(pill)
            }
        }
    }

    private fun slideNavIndicatorTo(target: Button) {
        val pill = navIndicator ?: return
        val rect = buttonBoundsInNav(target)
        if (rect.width() == 0) return
        if (!pill.isAttachedToWindow) {
            pill.layout(rect.left, rect.bottom, rect.right, rect.bottom + dpToPx(3))
            binding.bottomNav.overlay.add(pill)
            return
        }
        pill.animate().cancel()
        pill.animate()
            .x(rect.left.toFloat())
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()
    }

    private fun buttonBoundsInNav(button: Button): Rect {
        val rect = Rect(0, 0, button.width, button.height)
        binding.bottomNav.offsetDescendantRectToMyCoords(button, rect)
        return rect
    }

    private fun updatePagination(@Suppress("UNUSED_PARAMETER") visible: Boolean) {
        binding.paginationBar.isVisible = false
        binding.pageIndicator.text = "第 $currentPage 页 / 共 ${maxOf(1, totalPages())} 页"
        binding.prevPageButton.isEnabled = currentPage > 1
        binding.nextPageButton.isEnabled = currentPage * currentPageSize < currentTotal
    }

    private fun totalPages(): Int {
        if (currentPageSize <= 0 || currentTotal <= 0) return 1
        return ((currentTotal - 1) / currentPageSize) + 1
    }

    private fun applyFilterActions(
        actions: List<FilterAction>,
        showReset: Boolean,
        onReset: (() -> Unit)? = null,
    ) {
        val buttons = listOf(
            binding.filterPrimaryButton,
            binding.filterSecondaryButton,
            binding.filterTertiaryButton,
            binding.filterQuaternaryButton,
            binding.filterFifthButton,
        )

        buttons.forEach { button ->
            button.isVisible = false
            button.setOnClickListener(null)
        }

        actions.forEachIndexed { index, action ->
            val button = buttons.getOrNull(index) ?: return@forEachIndexed
            button.isVisible = true
            button.text = action.labelProvider()
            button.setOnClickListener {
                showFilterDialog(action, button.id)
            }
        }

        binding.filterResetButton.isVisible = showReset
        binding.filterResetButton.setOnClickListener { onReset?.invoke() }
        binding.filterScroll.isVisible = actions.isNotEmpty() || showReset
        updateFocusTargets()
    }

    private fun showFilterDialog(action: FilterAction, anchorViewId: Int) {
        val selectedLabel = action.labelProvider().substringAfter('·', "").trim()
        val items = action.options.map { option ->
            if (option.label == selectedLabel) "✓ ${option.label}" else option.label
        }.toTypedArray()
        focusNavJob?.cancel()
        clearNavFocusSwitchArm()
        pendingFocusRestoreViewId = anchorViewId
        MaterialAlertDialogBuilder(this)
            .setTitle(action.title)
            .setItems(items) { _, which ->
                action.onSelected(action.options[which].value)
            }
            .setOnDismissListener {
                binding.root.post {
                    if (!restorePendingFocusIfPossible()) {
                        requestBestContentFocus()
                    }
                }
            }
            .show()
    }

    private fun catalogFilterActions(): List<FilterAction> {
        return listOf(
            FilterAction(
                title = "地区",
                options = REGION_OPTIONS,
                labelProvider = { "地区 · ${labelFor(REGION_OPTIONS, catalogQuery.region)}" },
                onSelected = { value ->
                    catalogQuery = catalogQuery.copy(region = value, page = 1)
                    openScreen(Screen.CATALOG)
                },
            ),
            FilterAction(
                title = "版本",
                options = GENRE_OPTIONS,
                labelProvider = { "版本 · ${labelFor(GENRE_OPTIONS, catalogQuery.genre)}" },
                onSelected = { value ->
                    catalogQuery = catalogQuery.copy(genre = value, page = 1)
                    openScreen(Screen.CATALOG)
                },
            ),
            FilterAction(
                title = "类型",
                options = LABEL_OPTIONS,
                labelProvider = { "类型 · ${labelFor(LABEL_OPTIONS, catalogQuery.label)}" },
                onSelected = { value ->
                    catalogQuery = catalogQuery.copy(label = value, page = 1)
                    openScreen(Screen.CATALOG)
                },
            ),
            FilterAction(
                title = "年份",
                options = YEAR_OPTIONS,
                labelProvider = { "年份 · ${labelFor(YEAR_OPTIONS, catalogQuery.year)}" },
                onSelected = { value ->
                    catalogQuery = catalogQuery.copy(year = value, page = 1)
                    openScreen(Screen.CATALOG)
                },
            ),
            FilterAction(
                title = "排序",
                options = ORDER_OPTIONS,
                labelProvider = { "排序 · ${labelFor(ORDER_OPTIONS, catalogQuery.order)}" },
                onSelected = { value ->
                    catalogQuery = catalogQuery.copy(order = value, page = 1)
                    openScreen(Screen.CATALOG)
                },
            ),
        )
    }

    private fun rankFilterActions(): List<FilterAction> {
        return listOf(
            FilterAction(
                title = "首播年份",
                options = YEAR_OPTIONS,
                labelProvider = { "年份 · ${labelFor(YEAR_OPTIONS, rankYear)}" },
                onSelected = { value ->
                    rankYear = value
                    openScreen(Screen.RANK)
                },
            ),
        )
    }

    private fun updateFocusTargets() {
        val firstFilter = visibleFilterButtons().firstOrNull()
        val contentTarget = when {
            currentScreen == Screen.CAST -> binding.castContent.id
            firstFilter != null -> firstFilter.id
            else -> binding.contentRecycler.id
        }
        navButtons.forEach { button ->
            button.nextFocusDownId = contentTarget
        }
        visibleFilterButtons().forEach { button ->
            button.nextFocusUpId = currentNavButton().id
            button.nextFocusDownId = binding.contentRecycler.id
        }
    }

    private fun routeUpToCurrentNavIfNeeded(focused: View): Boolean {
        if (isInNavArea(focused)) return false
        if (!isDescendantOf(focused, binding.contentStage) && !isDescendantOf(focused, binding.filterScroll)) {
            return false
        }
        val next = focused.focusSearch(View.FOCUS_UP)
        if (next != null && !isInNavArea(next)) return false
        val target = currentNavButton()
        if (!target.isShown || !target.isFocusable) return false
        clearNavFocusSwitchArm()
        lastNavUpPressUptimeMs = 0L
        return target.requestFocus()
    }

    private fun restorePendingFocusIfPossible(): Boolean {
        val targetId = pendingFocusRestoreViewId ?: return false
        val target = findViewById<View>(targetId)
        return if (target != null && target.isShown && target.isFocusable) {
            pendingFocusRestoreViewId = null
            target.requestFocus()
        } else {
            false
        }
    }

    private fun restorePendingAnimeFocusIfPossible(): Boolean {
        val animeId = pendingFocusRestoreAnimeId ?: return false
        val target = binding.contentRecycler.findViewWithTag<View>(animeFocusTag(animeId))
        return if (target != null && target.isShown && target.isFocusable) {
            pendingFocusRestoreAnimeId = null
            target.requestFocus()
        } else {
            false
        }
    }

    private fun currentFocusedAnimeId(): Long? {
        var target: View? = currentFocus
        while (target != null) {
            val tag = target.tag as? String
            if (tag?.startsWith(ANIME_FOCUS_TAG_PREFIX) == true) {
                return tag.removePrefix(ANIME_FOCUS_TAG_PREFIX).toLongOrNull()
            }
            target = target.parent as? View
        }
        return null
    }

    private fun requestBestContentFocus() {
        val firstFilter = visibleFilterButtons().firstOrNull()
        when {
            firstFilter != null && firstFilter.isShown -> firstFilter.requestFocus()
            currentScreen == Screen.CAST && binding.castContent.isVisible -> binding.castContent.requestFocus()
            binding.contentRecycler.findFocus() != null -> binding.contentRecycler.findFocus().requestFocus()
            binding.contentRecycler.getChildAt(0) != null -> binding.contentRecycler.getChildAt(0).requestFocus()
            else -> currentNavButton().requestFocus()
        }
    }

    private fun armNavFocusSwitch() {
        navFocusSwitchArmDeadlineMs = SystemClock.elapsedRealtime() + NAV_FOCUS_ARM_WINDOW_MS
    }

    private fun consumeNavFocusSwitchArm(): Boolean {
        val armed = SystemClock.elapsedRealtime() <= navFocusSwitchArmDeadlineMs
        navFocusSwitchArmDeadlineMs = 0L
        return armed
    }

    private fun clearNavFocusSwitchArm() {
        navFocusSwitchArmDeadlineMs = 0L
    }

    private fun handleInfiniteGridDownPress(focused: View): Boolean {
        if (!supportsInfiniteGridPaging()) return false
        if (!isDescendantOf(focused, binding.contentRecycler)) return false
        if (!isBlockedDirectionalFocus(focused, View.FOCUS_DOWN)) return false
        if (!hasMoreGridPages()) return false
        if (isAppendingGridPage) return true
        if (loadJob?.isActive == true) {
            showOverlayMessage("正在刷新列表...")
            return true
        }
        appendNextGridPage(focused)
        return true
    }

    private fun shouldBlockEdgeNavigation(focused: View, keyCode: Int): Boolean {
        if (isInNavArea(focused)) {
            return keyCode == KeyEvent.KEYCODE_DPAD_UP
        }

        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> return false
        }

        return isBlockedDirectionalFocus(focused, direction)
    }

    private fun isBlockedDirectionalFocus(focused: View, direction: Int): Boolean {
        val next = focused.focusSearch(direction) ?: return true
        if (next === focused) return true
        if (isInNavArea(next)) return true

        val focusedRect = Rect().also(focused::getGlobalVisibleRect)
        val nextRect = Rect().also(next::getGlobalVisibleRect)
        return when (direction) {
            View.FOCUS_LEFT -> nextRect.centerX() >= focusedRect.centerX()
            View.FOCUS_RIGHT -> nextRect.centerX() <= focusedRect.centerX()
            View.FOCUS_DOWN -> nextRect.centerY() <= focusedRect.centerY()
            else -> false
        }
    }

    private fun supportsInfiniteGridPaging(): Boolean {
        return currentScreen == Screen.CATALOG ||
            currentScreen == Screen.UPDATE ||
            currentScreen == Screen.SEARCH
    }

    private fun hasMoreGridPages(): Boolean {
        return supportsInfiniteGridPaging() &&
            currentPageSize > 0 &&
            currentTotal > 0 &&
            currentPage * currentPageSize < currentTotal
    }

    private fun appendNextGridPage(focused: View) {
        val requestId = loadRequestId
        val nextPage = currentPage + 1
        val startIndex = currentVisibleCards.size
        val targetColumn = currentGridAdapterPosition(focused)
            ?.let { position ->
                val spanCount = (binding.contentRecycler.layoutManager as? GridLayoutManager)?.spanCount ?: 1
                position % spanCount
            }
            ?: 0
        isAppendingGridPage = true
        showOverlayMessage("正在加载更多...")
        appendJob?.cancel()
        appendJob = lifecycleScope.launch {
            try {
                val result = fetchGridPage(nextPage)
                if (!shouldHandleLoadResult(requestId)) return@launch
                if (result.items.isEmpty()) {
                    currentTotal = currentVisibleCards.size
                    return@launch
                }
                applyPagedGridResult(result, emptyMessage = currentGridEmptyMessage(), animate = false, append = true)
                val targetIndex = (startIndex + targetColumn).coerceAtMost(currentVisibleCards.lastIndex)
                val targetAnimeId = currentVisibleCards.getOrNull(targetIndex)?.animeId
                if (targetAnimeId != null) {
                    focusGridItem(targetIndex, targetAnimeId)
                }
            } catch (error: Throwable) {
                if (!shouldHandleLoadResult(requestId) || isCancellationError(error)) return@launch
                val prefix = currentLoadMoreErrorPrefix()
                val message = error.message?.takeIf { it.isNotBlank() }?.let { "$prefix：$it" } ?: prefix
                showOverlayMessage(message)
            } finally {
                isAppendingGridPage = false
            }
        }
    }

    private suspend fun fetchGridPage(page: Int): PagedCards {
        return when (currentScreen) {
            Screen.CATALOG -> app.ageRepository.fetchCatalog(catalogQuery.copy(page = page, size = 30))
            Screen.UPDATE -> app.ageRepository.fetchUpdate(page = page, size = 30)
            Screen.SEARCH -> app.ageRepository.search(
                query = currentSearchQuery?.trim().orEmpty(),
                page = page,
                size = 24,
            )
            else -> error("Current screen does not support infinite paging")
        }
    }

    private fun currentGridEmptyMessage(): String {
        return when (currentScreen) {
            Screen.CATALOG -> "当前筛选下没有结果"
            Screen.UPDATE -> "更新区暂时没有内容"
            Screen.SEARCH -> "没有搜索到相关动画"
            else -> ""
        }
    }

    private fun currentLoadMoreErrorPrefix(): String {
        return when (currentScreen) {
            Screen.CATALOG -> "加载更多目录失败"
            Screen.UPDATE -> "加载更多更新失败"
            Screen.SEARCH -> "加载更多搜索结果失败"
            else -> "加载更多失败"
        }
    }

    private fun currentGridAdapterPosition(focused: View): Int? {
        var target: View? = focused
        while (target != null) {
            val holder = binding.contentRecycler.findContainingViewHolder(target)
            val position = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
            if (position != RecyclerView.NO_POSITION) {
                return position
            }
            target = target.parent as? View
        }
        return null
    }

    private fun focusGridItem(position: Int, animeId: Long) {
        val layoutManager = binding.contentRecycler.layoutManager as? GridLayoutManager ?: return
        pendingFocusRestoreAnimeId = animeId
        binding.contentRecycler.post {
            layoutManager.scrollToPositionWithOffset(position, 0)
            binding.contentRecycler.post {
                if (!restorePendingAnimeFocusIfPossible()) {
                    binding.contentRecycler.smoothScrollToPosition(position)
                    binding.contentRecycler.post {
                        restorePendingAnimeFocusIfPossible()
                    }
                }
            }
        }
    }

    private fun isInNavArea(view: View): Boolean {
        return isDescendantOf(view, binding.bottomNav)
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun currentNavButton(): Button {
        return when (currentScreen) {
            Screen.CAST -> binding.navCastButton
            Screen.HOME -> binding.navHomeButton
            Screen.CATALOG -> binding.navCatalogButton
            Screen.RECOMMEND -> binding.navRecommendButton
            Screen.UPDATE -> binding.navUpdateButton
            Screen.RANK -> binding.navRankButton
            Screen.HISTORY -> binding.navHistoryButton
            Screen.SEARCH -> binding.navCastButton
        }
    }

    private fun visibleFilterButtons(): List<Button> {
        return listOf(
            binding.filterPrimaryButton,
            binding.filterSecondaryButton,
            binding.filterTertiaryButton,
            binding.filterQuaternaryButton,
            binding.filterFifthButton,
            binding.filterResetButton,
        ).filter { it.isVisible }
    }

    private fun showSearchDialog(initialQuery: String? = null) {
        val input = EditText(this).apply {
            hint = "输入动画名或关键词"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(initialQuery.orEmpty())
            setSelection(text?.length ?: 0)
            setPadding(48, 36, 48, 36)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("搜索 AGE 动画")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("搜索") { _, _ ->
                val query = input.text?.toString()?.trim().orEmpty()
                if (query.isBlank()) {
                    showOverlayMessage("请输入搜索关键词")
                } else {
                    currentSearchQuery = query
                    openScreen(Screen.SEARCH)
                }
            }
            .show()
    }

    private fun updateMirrorPanel(state: MirrorState) {
        val hasQuery = state.query.isNotBlank()
        binding.mirrorQueryText.text = if (hasQuery) "「${state.query}」" else "等待手机搜索..."
        binding.mirrorQueryText.setTextColor(
            if (hasQuery) getColor(R.color.age_text) else getColor(R.color.age_text_muted),
        )

        val cards = state.results.map { item ->
            AnimeCard(
                animeId = item.animeId,
                title = item.title,
                cover = item.cover,
                badge = item.badge,
                subtitle = "",
            )
        }
        mirrorAdapter.submitList(cards)

        val hasResults = cards.isNotEmpty()
        binding.mirrorRecycler.isVisible = hasResults
        binding.mirrorEmptyText.isVisible = !hasResults
        binding.mirrorEmptyText.text = if (hasQuery) "没有找到相关动画" else "搜索结果会实时显示在这里"

        if (hasResults) {
            binding.mirrorRecycler.alpha = 0f
            binding.mirrorRecycler.animate().alpha(1f).setDuration(160).start()
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun navigateBack() {
        if (selectionMode) {
            exitSelectionMode()
            return
        }
        when (currentScreen) {
            Screen.HOME -> finish()
            Screen.SEARCH -> openScreen(Screen.HOME)
            Screen.HISTORY -> openScreen(Screen.HOME)
            else -> openScreen(Screen.HOME)
        }
    }

    private fun openDetail(card: AnimeCard) {
        openDetail(card.animeId)
    }

    private fun openDetail(animeId: Long) {
        startActivity(DetailActivity.createIntent(this, animeId))
    }

    private fun launchPlayer(route: AgeRoute.Play) {
        startActivity(
            PlayerActivity.createIntent(
                context = this,
                animeId = route.animeId,
                sourceIndex = route.sourceIndex,
                episodeIndex = route.episodeIndex,
            ),
        )
    }

    private fun launchPlayerForRecord(record: PlaybackRecord) {
        startActivity(
            PlayerActivity.createIntent(
                context = this,
                animeId = record.animeId,
                sourceIndex = 1,
                episodeIndex = record.episodeIndex + 1,
                preferredSourceKey = record.sourceKey,
                resumePositionMs = record.positionMs,
                preferResumePrompt = false,
            ),
        )
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

    private fun updateHistoryNavLabel() {
        binding.navHistoryButton.text = getString(R.string.btn_history)
    }

    private fun configureBrowseSelection(
        focusAnimeId: Long? = pendingFocusRestoreAnimeId ?: currentFocusedAnimeId(),
        changedAnimeIds: Collection<Long> = emptyList(),
        restoreFocus: Boolean = changedAnimeIds.isEmpty(),
    ) {
        pendingFocusRestoreAnimeId = focusAnimeId
        val enabled = canUseBatchSelection()
        val longClickHandler = if (enabled) {
            { card: AnimeCard -> handleBatchSelectionLongClick(card) }
        } else {
            null
        }
        val selectionEnabled = enabled && selectionMode
        val selected = if (selectionEnabled) selectedAnimeIds else emptySet()
        gridAdapter.onLongClick = longClickHandler
        gridAdapter.updateSelectionState(selectionEnabled, selected)
        sectionAdapter.onLongClick = longClickHandler
        sectionAdapter.updateSelectionState(selectionEnabled, selected)
        when (binding.contentRecycler.adapter) {
            gridAdapter -> gridAdapter.refreshSelection(changedAnimeIds)
            sectionAdapter -> sectionAdapter.updateSelectionState(selectionEnabled, selected, changedAnimeIds)
        }
        if (restoreFocus) {
            binding.contentRecycler.post {
                restorePendingAnimeFocusIfPossible()
            }
        }
    }

    private fun canUseBatchSelection(): Boolean {
        return app.bangumiAccountService.isLoggedIn() &&
            currentScreen != Screen.CAST &&
            currentScreen != Screen.HISTORY
    }

    private fun handleBatchSelectionLongClick(card: AnimeCard) {
        pendingFocusRestoreAnimeId = card.animeId
        if (!selectionMode) {
            selectionMode = true
            selectedAnimeIds.clear()
            selectedAnimeIds += card.animeId
            configureBrowseSelection(card.animeId, changedAnimeIds = listOf(card.animeId))
            showOverlayMessage("多选模式：按 OK 勾选，长按执行批量标记，返回退出")
            return
        }
        selectedAnimeIds += card.animeId
        configureBrowseSelection(card.animeId, changedAnimeIds = listOf(card.animeId))
        showBatchStatusDialog()
    }

    private fun toggleCardSelection(card: AnimeCard) {
        if (!selectionMode) {
            openDetail(card)
            return
        }
        if (!selectedAnimeIds.add(card.animeId)) {
            selectedAnimeIds.remove(card.animeId)
        }
        if (selectedAnimeIds.isEmpty()) {
            exitSelectionMode(silent = true, focusAnimeId = card.animeId, changedAnimeIds = listOf(card.animeId))
        } else {
            configureBrowseSelection(card.animeId, changedAnimeIds = listOf(card.animeId))
        }
    }

    private fun exitSelectionMode(
        silent: Boolean = false,
        focusAnimeId: Long? = pendingFocusRestoreAnimeId ?: currentFocusedAnimeId(),
        changedAnimeIds: Collection<Long> = selectedAnimeIds.toList(),
    ) {
        if (!selectionMode && selectedAnimeIds.isEmpty()) return
        pendingFocusRestoreAnimeId = focusAnimeId
        selectionMode = false
        selectedAnimeIds.clear()
        configureBrowseSelection(
            focusAnimeId = focusAnimeId,
            changedAnimeIds = changedAnimeIds,
            restoreFocus = true,
        )
        if (!silent) {
            showOverlayMessage("已退出多选模式")
        }
    }

    private fun showBatchStatusDialog() {
        val cards = currentVisibleCards.filter { it.animeId in selectedAnimeIds }
        if (cards.isEmpty()) {
            exitSelectionMode(silent = true)
            return
        }
        val focusAnimeId = pendingFocusRestoreAnimeId ?: currentFocusedAnimeId() ?: cards.firstOrNull()?.animeId
        MaterialAlertDialogBuilder(this)
            .setTitle("批量标记 ${cards.size} 项")
            .setItems(arrayOf("标记看过", "标记抛弃")) { _, which ->
                val status = if (which == 0) BangumiCollectionStatus.COLLECT else BangumiCollectionStatus.DROPPED
                app.bangumiAccountService.enqueueBatchStatusUpdate(cards, status)
                exitSelectionMode(silent = true, focusAnimeId = focusAnimeId)
                showOverlayMessage("已加入 Bangumi 同步队列：${cards.size} 项")
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener {
                binding.root.post {
                    if (!restorePendingAnimeFocusIfPossible()) {
                        requestBestContentFocus()
                    }
                }
            }
            .show()
    }

    private fun recordToCard(record: PlaybackRecord): AnimeCard {
        return AnimeCard(
            animeId = record.animeId,
            title = record.animeTitle,
            cover = app.ageRepository.buildCoverUrl(record.animeId),
            badge = record.episodeLabel,
            subtitle = "看到 ${formatPlaybackTime(record.positionMs)}",
            bgmScore = app.ageRepository.peekBangumiScore(record.animeId).orEmpty(),
        )
    }

    private fun todayWeekdayPrefix(): String {
        return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "周一"
            Calendar.TUESDAY -> "周二"
            Calendar.WEDNESDAY -> "周三"
            Calendar.THURSDAY -> "周四"
            Calendar.FRIDAY -> "周五"
            Calendar.SATURDAY -> "周六"
            else -> "周日"
        }
    }

    private fun labelFor(options: List<FilterOption>, value: String): String {
        return options.firstOrNull { it.value == value }?.label ?: "全部"
    }

    companion object {
        const val EXTRA_OPEN_ROUTE = "extra_open_route"
        private const val NAV_FOCUS_DELAY_MS = 300L
        private const val NAV_FOCUS_ARM_WINDOW_MS = 600L
        private const val SETTINGS_SHORTCUT_WINDOW_MS = 1_100L
        private const val STALE_REFRESH_DELAY_MS = 180L
        private const val GRID_SPAN_COUNT = 6
        private const val ANIME_FOCUS_TAG_PREFIX = "anime-card:"

        fun createIntent(context: Context, route: AgeRoute? = null): Intent {
            return Intent(context, MainActivity::class.java).apply {
                route?.let { putExtra(EXTRA_OPEN_ROUTE, AgeLinks.buildWebUrl(it)) }
            }
        }

        fun animeFocusTag(animeId: Long): String = "$ANIME_FOCUS_TAG_PREFIX$animeId"

        private val REGION_OPTIONS = listOf(
            FilterOption("全部", "all"),
            FilterOption("日本", "日本"),
            FilterOption("中国", "中国"),
            FilterOption("欧美", "欧美"),
        )

        private val GENRE_OPTIONS = listOf(
            FilterOption("全部", "all"),
            FilterOption("TV", "TV"),
            FilterOption("剧场版", "剧场版"),
            FilterOption("OVA", "OVA"),
        )

        private val LABEL_OPTIONS = listOf(
            FilterOption("全部", "all"),
            FilterOption("热血", "热血"),
            FilterOption("战斗", "战斗"),
            FilterOption("恋爱", "恋爱"),
            FilterOption("校园", "校园"),
            FilterOption("奇幻", "奇幻"),
            FilterOption("科幻", "科幻"),
            FilterOption("冒险", "冒险"),
            FilterOption("机战", "机战"),
            FilterOption("搞笑", "搞笑"),
            FilterOption("悬疑", "悬疑"),
            FilterOption("百合", "百合"),
        )

        private val ORDER_OPTIONS = listOf(
            FilterOption("更新时间", "time"),
            FilterOption("名称", "name"),
            FilterOption("点击量", "hits"),
        )

        private val YEAR_OPTIONS: List<FilterOption> = buildList {
            add(FilterOption("全部", "all"))
            for (year in Calendar.getInstance().get(Calendar.YEAR) downTo 2000) {
                add(FilterOption(year.toString(), year.toString()))
            }
            add(FilterOption("2000以前", "2000"))
        }
    }
}
