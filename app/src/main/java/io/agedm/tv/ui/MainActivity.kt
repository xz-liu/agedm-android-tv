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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.R
import io.agedm.tv.data.AgeLinks
import io.agedm.tv.data.AgeRoute
import io.agedm.tv.data.AnimeCard
import io.agedm.tv.data.BrowseSection
import io.agedm.tv.data.CatalogQuery
import io.agedm.tv.data.MirrorState
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
    private var loadRequestId: Long = 0L
    private var overlayJob: Job? = null
    private var focusNavJob: Job? = null
    private var lastNavUpPressUptimeMs: Long = 0L
    private var slideFromRight = true
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
        if (currentScreen == Screen.HISTORY) {
            loadHistory()
        }
        app.linkCastManager.consumePendingRoute()?.let { route ->
            showOverlayMessage("已收到手机投送：${AgeLinks.describe(route)}")
            openRoute(route)
        }
    }

    private fun setupRecycler() {
        sectionAdapter = BrowseSectionAdapter(::openDetail)
        gridAdapter = PosterCardAdapter(::openDetail)
        mirrorAdapter = PosterCardAdapter(::openDetail)
        binding.contentRecycler.itemAnimator = null
        binding.contentRecycler.layoutManager = LinearLayoutManager(this)
        binding.contentRecycler.adapter = sectionAdapter
        binding.mirrorRecycler.itemAnimator = null
        binding.mirrorRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.mirrorRecycler.adapter = mirrorAdapter
    }

    private fun setupChrome() {
        binding.prevPageButton.setOnClickListener {
            if (currentPage > 1) {
                openScreen(currentScreen, currentPage - 1)
            }
        }
        binding.nextPageButton.setOnClickListener {
            if (currentPage * currentPageSize < currentTotal) {
                openScreen(currentScreen, currentPage + 1)
            }
        }
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
                openScreen(screen)
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
            if (event.keyCode != KeyEvent.KEYCODE_DPAD_UP || focused == null || !isInNavArea(focused)) {
                lastNavUpPressUptimeMs = 0L
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
        slideFromRight = when {
            page != currentPage -> page > currentPage
            else -> screen.navIndex() >= currentScreen.navIndex()
        }
        currentScreen = screen
        currentPage = page
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
            Screen.CATALOG -> loadCatalog(page)
            Screen.RECOMMEND -> loadRecommend()
            Screen.UPDATE -> loadUpdate(page)
            Screen.RANK -> loadRank()
            Screen.HISTORY -> loadHistory()
            Screen.SEARCH -> loadSearch(page)
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

    private fun loadCatalog(page: Int) {
        catalogQuery = catalogQuery.copy(page = page, size = 30)
        applyFilterActions(catalogFilterActions(), showReset = true) {
            catalogQuery = CatalogQuery()
            openScreen(Screen.CATALOG)
        }
        updatePagination(visible = false)
        val query = catalogQuery
        launchStaleFirstLoad(
            loadingMessage = "正在加载目录...",
            errorPrefix = "目录加载失败",
            peek = { app.ageRepository.peekCatalog(query) },
            fetch = { app.ageRepository.fetchCatalog(query) },
        ) { result, animate ->
            currentTotal = result.total
            currentPageSize = result.size
            showGrid(result.items, emptyMessage = "当前筛选下没有结果", animate = animate)
            updatePagination(visible = result.total > result.size)
        }
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

    private fun loadUpdate(page: Int) {
        applyFilterActions(emptyList(), showReset = false)
        updatePagination(visible = false)
        launchStaleFirstLoad(
            loadingMessage = "正在加载更新...",
            errorPrefix = "更新加载失败",
            peek = { app.ageRepository.peekUpdate(page = page, size = 30) },
            fetch = { app.ageRepository.fetchUpdate(page = page, size = 30) },
        ) { result, animate ->
            currentTotal = result.total
            currentPageSize = result.size
            showGrid(result.items, emptyMessage = "更新区暂时没有内容", animate = animate)
            updatePagination(visible = result.total > result.size)
        }
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
        renderLoading("正在读取观看记录...")
        applyFilterActions(emptyList(), showReset = false)
        updatePagination(visible = false)
        replaceLoadRequest()
        val history = app.playbackStore.getRecentRecords(60).map(::recordToCard)
        currentTotal = history.size
        currentPageSize = history.size.coerceAtLeast(1)
        showGrid(history, emptyMessage = "还没有播放记录")
    }

    private fun loadSearch(page: Int) {
        val query = currentSearchQuery?.trim().orEmpty()
        if (query.isBlank()) {
            showSearchDialog()
            return
        }
        applyFilterActions(emptyList(), showReset = false)
        updatePagination(visible = false)
        launchStaleFirstLoad(
            loadingMessage = "正在搜索「$query」...",
            errorPrefix = "搜索失败",
            peek = { app.ageRepository.peekSearch(query = query, page = page, size = 24) },
            fetch = { app.ageRepository.search(query = query, page = page, size = 24) },
        ) { result, animate ->
            currentTotal = result.total
            currentPageSize = result.size
            showGrid(result.items, emptyMessage = "没有搜索到相关动画", animate = animate)
            updatePagination(visible = result.total > result.size)
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
        binding.contentRecycler.layoutManager = GridLayoutManager(this, 6)
        binding.contentRecycler.adapter = gridAdapter
        gridAdapter.submitList(items)
        binding.emptyStateText.isVisible = items.isEmpty()
        binding.emptyStateText.text = emptyMessage
        updateFocusTargets()
        if (animate) animateContentIn(binding.contentRecycler)
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

    private fun updatePagination(visible: Boolean) {
        binding.paginationBar.isVisible = visible
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
                showFilterDialog(action)
            }
        }

        binding.filterResetButton.isVisible = showReset
        binding.filterResetButton.setOnClickListener { onReset?.invoke() }
        binding.filterScroll.isVisible = actions.isNotEmpty() || showReset
        updateFocusTargets()
    }

    private fun showFilterDialog(action: FilterAction) {
        val selectedLabel = action.labelProvider().substringAfter('·', "").trim()
        val items = action.options.map { option ->
            if (option.label == selectedLabel) "✓ ${option.label}" else option.label
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(action.title)
            .setItems(items) { _, which ->
                action.onSelected(action.options[which].value)
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

    private fun recordToCard(record: PlaybackRecord): AnimeCard {
        return AnimeCard(
            animeId = record.animeId,
            title = record.animeTitle,
            cover = app.ageRepository.buildCoverUrl(record.animeId),
            badge = record.episodeLabel,
            subtitle = "看到 ${formatPlaybackTime(record.positionMs)}",
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
        private const val SETTINGS_SHORTCUT_WINDOW_MS = 1_100L
        private const val STALE_REFRESH_DELAY_MS = 180L

        fun createIntent(context: Context, route: AgeRoute? = null): Intent {
            return Intent(context, MainActivity::class.java).apply {
                route?.let { putExtra(EXTRA_OPEN_ROUTE, AgeLinks.buildWebUrl(it)) }
            }
        }

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
