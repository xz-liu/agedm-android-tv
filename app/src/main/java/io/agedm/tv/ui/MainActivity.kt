package io.agedm.tv.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import io.agedm.tv.data.PlaybackRecord
import io.agedm.tv.databinding.ActivityMainBinding
import io.agedm.tv.ui.adapter.BrowseSectionAdapter
import io.agedm.tv.ui.adapter.PosterCardAdapter
import java.util.Calendar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private enum class Screen {
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

    private val app: AgeTvApplication
        get() = application as AgeTvApplication

    private var currentScreen: Screen = Screen.HOME
    private var currentSearchQuery: String? = null
    private var currentPage: Int = 1
    private var currentPageSize: Int = 30
    private var currentTotal: Int = 0
    private var catalogQuery = CatalogQuery()
    private var rankYear = "all"
    private var loadJob: Job? = null
    private var overlayJob: Job? = null

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
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateContinueButton()
        if (currentScreen == Screen.HISTORY) {
            loadHistory()
        }
    }

    private fun setupRecycler() {
        sectionAdapter = BrowseSectionAdapter(::openDetail)
        gridAdapter = PosterCardAdapter(::openDetail)
        binding.contentRecycler.itemAnimator = null
        binding.contentRecycler.layoutManager = LinearLayoutManager(this)
        binding.contentRecycler.adapter = sectionAdapter
    }

    private fun setupChrome() {
        binding.backButton.setOnClickListener { navigateBack() }
        binding.homeButton.setOnClickListener { openScreen(Screen.HOME) }
        binding.searchButton.setOnClickListener { showSearchDialog(currentSearchQuery) }
        binding.castButton.setOnClickListener {
            startActivity(Intent(this, LinkCastActivity::class.java))
        }
        binding.continueButton.setOnClickListener {
            app.playbackStore.getRecentRecords(1).firstOrNull()?.let(::launchPlayerForRecord)
        }
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
        binding.navHomeButton.setOnClickListener { openScreen(Screen.HOME) }
        binding.navCatalogButton.setOnClickListener { openScreen(Screen.CATALOG) }
        binding.navRecommendButton.setOnClickListener { openScreen(Screen.RECOMMEND) }
        binding.navUpdateButton.setOnClickListener { openScreen(Screen.UPDATE) }
        binding.navRankButton.setOnClickListener { openScreen(Screen.RANK) }
        binding.navHistoryButton.setOnClickListener { openScreen(Screen.HISTORY) }
        updateBottomNav()
    }

    private fun setupBackBehavior() {
        onBackPressedDispatcher.addCallback(this) {
            navigateBack()
        }
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
        currentScreen = screen
        currentPage = page
        currentPageSize = when (screen) {
            Screen.SEARCH -> 24
            Screen.RECOMMEND -> 100
            else -> 30
        }
        updateBottomNav()
        updateContinueButton()
        when (screen) {
            Screen.HOME -> loadHome()
            Screen.CATALOG -> loadCatalog(page)
            Screen.RECOMMEND -> loadRecommend()
            Screen.UPDATE -> loadUpdate(page)
            Screen.RANK -> loadRank()
            Screen.HISTORY -> loadHistory()
            Screen.SEARCH -> loadSearch(page)
        }
    }

    private fun loadHome() {
        renderLoading("正在整理首页...")
        applyFilterActions(emptyList(), showReset = false)
        updatePagination(visible = false)
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            runCatching {
                val feed = app.ageRepository.fetchHomeFeed()
                buildList {
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
            }.onSuccess { sections ->
                binding.pageTitle.text = getString(R.string.app_name)
                binding.pageSubtitle.text = "推荐、更新与每日追番"
                showSections(sections, emptyMessage = "首页暂时没有内容")
            }.onFailure { error ->
                showError("首页加载失败：${error.message.orEmpty()}")
            }
        }
    }

    private fun loadCatalog(page: Int) {
        catalogQuery = catalogQuery.copy(page = page, size = 30)
        renderLoading("正在加载目录...")
        applyFilterActions(catalogFilterActions(), showReset = true) {
            catalogQuery = CatalogQuery()
            openScreen(Screen.CATALOG)
        }
        updatePagination(visible = false)
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            runCatching { app.ageRepository.fetchCatalog(catalogQuery) }
                .onSuccess { result ->
                    currentTotal = result.total
                    currentPageSize = result.size
                    binding.pageTitle.text = "目录"
                    binding.pageSubtitle.text = "按 AGE 分类快速找番"
                    showGrid(result.items, emptyMessage = "当前筛选下没有结果")
                    updatePagination(visible = result.total > result.size)
                }
                .onFailure { error ->
                    showError("目录加载失败：${error.message.orEmpty()}")
                }
        }
    }

    private fun loadRecommend() {
        renderLoading("正在加载推荐...")
        applyFilterActions(emptyList(), showReset = false)
        updatePagination(visible = false)
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            runCatching { app.ageRepository.fetchRecommend() }
                .onSuccess { result ->
                    currentTotal = result.total
                    binding.pageTitle.text = "推荐"
                    binding.pageSubtitle.text = "AGE 站内精选片单"
                    showGrid(result.items, emptyMessage = "推荐区暂时没有内容")
                }
                .onFailure { error ->
                    showError("推荐加载失败：${error.message.orEmpty()}")
                }
        }
    }

    private fun loadUpdate(page: Int) {
        renderLoading("正在加载更新...")
        applyFilterActions(emptyList(), showReset = false)
        updatePagination(visible = false)
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            runCatching { app.ageRepository.fetchUpdate(page = page, size = 30) }
                .onSuccess { result ->
                    currentTotal = result.total
                    currentPageSize = result.size
                    binding.pageTitle.text = "更新"
                    binding.pageSubtitle.text = "最近更新的动画作品"
                    showGrid(result.items, emptyMessage = "更新区暂时没有内容")
                    updatePagination(visible = result.total > result.size)
                }
                .onFailure { error ->
                    showError("更新加载失败：${error.message.orEmpty()}")
                }
        }
    }

    private fun loadRank() {
        renderLoading("正在加载排行...")
        applyFilterActions(rankFilterActions(), showReset = false)
        updatePagination(visible = false)
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            runCatching { app.ageRepository.fetchRankSections(rankYear) }
                .onSuccess { sections ->
                    binding.pageTitle.text = "排行"
                    binding.pageSubtitle.text = if (rankYear == "all") {
                        "站内热门 Top 榜单"
                    } else {
                        "$rankYear 年度热门榜单"
                    }
                    showSections(sections, emptyMessage = "排行榜暂时没有内容")
                }
                .onFailure { error ->
                    showError("排行加载失败：${error.message.orEmpty()}")
                }
        }
    }

    private fun loadHistory() {
        renderLoading("正在读取观看记录...")
        applyFilterActions(emptyList(), showReset = false)
        updatePagination(visible = false)
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val history = app.playbackStore.getRecentRecords(60).map(::recordToCard)
            currentTotal = history.size
            currentPageSize = history.size.coerceAtLeast(1)
            binding.pageTitle.text = "记录"
            binding.pageSubtitle.text = "最近观看与续播历史"
            showGrid(history, emptyMessage = "还没有播放记录")
        }
    }

    private fun loadSearch(page: Int) {
        val query = currentSearchQuery?.trim().orEmpty()
        if (query.isBlank()) {
            showSearchDialog()
            return
        }
        renderLoading("正在搜索「$query」...")
        applyFilterActions(emptyList(), showReset = false)
        updatePagination(visible = false)
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            runCatching { app.ageRepository.search(query = query, page = page, size = 24) }
                .onSuccess { result ->
                    currentTotal = result.total
                    currentPageSize = result.size
                    binding.pageTitle.text = "搜索"
                    binding.pageSubtitle.text = "「$query」相关结果"
                    showGrid(result.items, emptyMessage = "没有搜索到相关动画")
                    updatePagination(visible = result.total > result.size)
                }
                .onFailure { error ->
                    showError("搜索失败：${error.message.orEmpty()}")
                }
        }
    }

    private fun showSections(sections: List<BrowseSection>, emptyMessage: String) {
        binding.loadingLayout.isVisible = false
        binding.contentRecycler.isVisible = true
        binding.contentRecycler.layoutManager = LinearLayoutManager(this)
        binding.contentRecycler.adapter = sectionAdapter
        sectionAdapter.submitList(sections)
        binding.emptyStateText.isVisible = sections.isEmpty()
        binding.emptyStateText.text = emptyMessage
        updateFocusTargets()
    }

    private fun showGrid(items: List<AnimeCard>, emptyMessage: String) {
        binding.loadingLayout.isVisible = false
        binding.contentRecycler.isVisible = true
        binding.contentRecycler.layoutManager = GridLayoutManager(this, 6)
        binding.contentRecycler.adapter = gridAdapter
        gridAdapter.submitList(items)
        binding.emptyStateText.isVisible = items.isEmpty()
        binding.emptyStateText.text = emptyMessage
        updateFocusTargets()
    }

    private fun renderLoading(message: String) {
        binding.loadingText.text = message
        binding.loadingLayout.isVisible = true
        binding.emptyStateText.isVisible = false
        binding.contentRecycler.isVisible = true
    }

    private fun showError(message: String) {
        binding.loadingLayout.isVisible = false
        binding.emptyStateText.text = message
        binding.emptyStateText.isVisible = true
        binding.contentRecycler.isVisible = true
    }

    private fun updateContinueButton() {
        val recent = app.playbackStore.getRecentRecords(1).firstOrNull()
        binding.continueButton.isVisible = recent != null
        binding.continueButton.text = recent?.let { "继续 ${it.animeTitle}" } ?: getString(R.string.btn_continue)
    }

    private fun updateBottomNav() {
        binding.navHomeButton.isSelected = currentScreen == Screen.HOME
        binding.navCatalogButton.isSelected = currentScreen == Screen.CATALOG
        binding.navRecommendButton.isSelected = currentScreen == Screen.RECOMMEND
        binding.navUpdateButton.isSelected = currentScreen == Screen.UPDATE
        binding.navRankButton.isSelected = currentScreen == Screen.RANK
        binding.navHistoryButton.isSelected = currentScreen == Screen.HISTORY
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
        listOf(
            binding.navHomeButton,
            binding.navCatalogButton,
            binding.navRecommendButton,
            binding.navUpdateButton,
            binding.navRankButton,
            binding.navHistoryButton,
        ).forEach { button ->
            button.nextFocusDownId = firstFilter?.id ?: binding.contentRecycler.id
        }

        visibleFilterButtons().forEach { button ->
            button.nextFocusUpId = currentNavButton().id
            button.nextFocusDownId = binding.contentRecycler.id
        }
    }

    private fun currentNavButton(): Button {
        return when (currentScreen) {
            Screen.HOME -> binding.navHomeButton
            Screen.CATALOG -> binding.navCatalogButton
            Screen.RECOMMEND -> binding.navRecommendButton
            Screen.UPDATE -> binding.navUpdateButton
            Screen.RANK -> binding.navRankButton
            Screen.HISTORY -> binding.navHistoryButton
            Screen.SEARCH -> binding.navHomeButton
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
