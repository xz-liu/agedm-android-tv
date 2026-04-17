package io.agedm.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.data.AgeRoute
import io.agedm.tv.data.AgeRelatedItem
import io.agedm.tv.data.AnimeCard
import io.agedm.tv.data.AnimeDetail
import io.agedm.tv.data.EpisodeItem
import io.agedm.tv.data.EpisodeSource
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

    private val app: AgeTvApplication
        get() = application as AgeTvApplication

    private var detail: AnimeDetail? = null
    private var selectedSourceIndex: Int = 0
    private var selectedEpisodeIndex: Int = 0

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
    }

    private fun setupLists() {
        sourceAdapter = SourceAdapter(::onSourceSelected)
        episodeAdapter = EpisodeAdapter(::onEpisodeSelected)
        relatedAdapter = PosterCardAdapter { openDetail(it.animeId) }
        similarAdapter = PosterCardAdapter { openDetail(it.animeId) }

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
            val source = loadedDetail.sources.getOrNull(selectedSourceIndex) ?: return@setOnClickListener
            val episode = source.episodes.getOrNull(selectedEpisodeIndex) ?: return@setOnClickListener
            launchPlayer(
                sourceIndex = selectedSourceIndex,
                episodeIndex = episode.index,
                preferredSourceKey = source.key,
            )
        }
        binding.continueButton.setOnClickListener {
            val record = detail?.animeId?.let(app.playbackStore::getRecord) ?: return@setOnClickListener
            launchPlayer(
                sourceIndex = selectedSourceIndexFor(record.sourceKey),
                episodeIndex = record.episodeIndex,
                preferredSourceKey = record.sourceKey,
                resumePositionMs = record.positionMs,
                preferResumePrompt = false,
            )
        }
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
        binding.loadingLayout.isVisible = true
        binding.errorText.isVisible = false
        binding.detailScrollView.isVisible = false
        lifecycleScope.launch {
            runCatching { app.ageRepository.fetchDetail(animeId) }
                .onSuccess { loadedDetail ->
                    detail = loadedDetail
                    bindDetail(loadedDetail)
                }
                .onFailure { error ->
                    showError("详情加载失败：${error.message.orEmpty()}")
                }
        }
    }

    private fun bindDetail(loadedDetail: AnimeDetail) {
        binding.loadingLayout.isVisible = false
        binding.errorText.isVisible = false
        binding.detailScrollView.isVisible = true

        binding.pageTitle.text = loadedDetail.title
        binding.pageSubtitle.text = "共 ${loadedDetail.sources.sumOf { it.episodes.size }} 个可选分集"
        binding.titleText.text = loadedDetail.title
        binding.metaText.text = listOf(loadedDetail.status, loadedDetail.tags)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        binding.tagsText.text = buildTagsLine(loadedDetail)
        binding.introText.text = htmlToPlainText(loadedDetail.introHtml).ifBlank { "暂无简介" }
        binding.coverImage.loadPosterImage(loadedDetail.cover)

        val record = app.playbackStore.getRecord(loadedDetail.animeId)
        selectedSourceIndex = record?.sourceKey?.let(::selectedSourceIndexFor) ?: 0

        val selectedSource = loadedDetail.sources.getOrNull(selectedSourceIndex)
        selectedEpisodeIndex = record?.episodeIndex
            ?.coerceIn(0, selectedSource?.episodes?.lastIndex ?: 0)
            ?: 0
        sourceAdapter.submitList(loadedDetail.sources, selectedSource?.key)
        episodeAdapter.submitList(selectedSource?.episodes.orEmpty(), selectedEpisodeIndex)
        binding.sourceRecycler.scrollToPosition(selectedSourceIndex)
        binding.episodeRecycler.scrollToPosition(selectedEpisodeIndex)

        relatedAdapter.submitList(loadedDetail.related.map(::toCard))
        similarAdapter.submitList(loadedDetail.similar.map(::toCard))
        binding.relatedTitle.isVisible = loadedDetail.related.isNotEmpty()
        binding.relatedRecycler.isVisible = loadedDetail.related.isNotEmpty()
        binding.similarTitle.isVisible = loadedDetail.similar.isNotEmpty()
        binding.similarRecycler.isVisible = loadedDetail.similar.isNotEmpty()

        binding.continueButton.isVisible = record != null
        binding.continueButton.text = record?.let { "继续 ${it.episodeLabel}" } ?: getString(io.agedm.tv.R.string.btn_continue)
        binding.playButton.requestFocus()
    }

    private fun onSourceSelected(source: EpisodeSource) {
        val loadedDetail = detail ?: return
        val index = loadedDetail.sources.indexOfFirst { it.key == source.key }
        if (index < 0) return
        selectedSourceIndex = index
        selectedEpisodeIndex = app.playbackStore.getRecord(loadedDetail.animeId)
            ?.takeIf { it.sourceKey == source.key }
            ?.episodeIndex
            ?.coerceIn(0, source.episodes.lastIndex)
            ?: 0
        sourceAdapter.submitList(loadedDetail.sources, source.key)
        episodeAdapter.submitList(source.episodes, selectedEpisodeIndex)
        binding.episodeRecycler.scrollToPosition(selectedEpisodeIndex)
    }

    private fun onEpisodeSelected(episode: EpisodeItem) {
        val loadedDetail = detail ?: return
        val source = loadedDetail.sources.getOrNull(selectedSourceIndex) ?: return
        selectedEpisodeIndex = episode.index
        episodeAdapter.submitList(source.episodes, selectedEpisodeIndex)
        launchPlayer(
            sourceIndex = selectedSourceIndex,
            episodeIndex = episode.index,
            preferredSourceKey = source.key,
        )
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
