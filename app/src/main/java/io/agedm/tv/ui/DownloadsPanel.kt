package io.agedm.tv.ui

import android.os.StatFs
import android.os.SystemClock
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.exoplayer.offline.DownloadService
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.R
import io.agedm.tv.data.*
import io.agedm.tv.service.MediaDownloadService
import io.agedm.tv.ui.adapter.DownloadEpisodeAdapter
import io.agedm.tv.ui.adapter.DownloadPosterAdapter
import io.agedm.tv.ui.adapter.statusLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Same poster grid as the library, with an episode grid inside each downloaded show. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class DownloadsPanel(
    private val activity: AppCompatActivity,
    private val root: LinearLayout,
    private val navViewId: Int,
) {
    private val app get() = activity.application as AgeTvApplication
    private val store get() = app.offlineDownloads
    private val active = MutableStateFlow(false)
    private val speedTracker = DownloadSpeedTracker()
    private var entries: List<DownloadEntry> = emptyList()
    private var selectedAnimeId: Long? = null
    private var filter = 0
    private var libraryPosition = 0
    private val summary = TextView(activity).apply {
        textSize = 15f; setTextColor(activity.getColor(R.color.age_text)); setPadding(dp(8), dp(4), 0, dp(8))
        text = "正在读取下载…"
    }
    private val toolbar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    private val empty = TextView(activity).apply {
        textSize = 16f; gravity = Gravity.CENTER; setTextColor(activity.getColor(R.color.age_text_muted))
    }
    private val recycler = RecyclerView(activity).apply {
        id = View.generateViewId(); itemAnimator = null; clipToPadding = false
        setPadding(0, dp(8), 0, dp(8))
    }
    private val posters = DownloadPosterAdapter(::openShow)
    private val episodes = DownloadEpisodeAdapter(::onEpisodeClick, ::showEpisodeActions)
    private val allButton = button("全部") { applyFilter(0) }
    private val pendingButton = button("未完成") { applyFilter(1) }
    private val completedButton = button("已完成") { applyFilter(2) }
    private val backButton = button("返回作品") { showLibrary() }
    private val pauseButton = button("暂停本部") { act { store.pause(selectedEntries().filter { it.status in ACTIVE_STATUSES }.mapTo(mutableSetOf()) { it.id }) } }
    private val resumeButton = button("继续 / 重试") { act { store.resume(selectedEntries().filter { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED }.mapTo(mutableSetOf()) { it.id }) } }
    private val deleteButton = button("删除本部") { confirmDelete(selectedEntries()) }
    private val libraryButtons get() = listOf(allButton, pendingButton, completedButton)
    private val showButtons get() = listOf(backButton, pauseButton, resumeButton, deleteButton)

    init {
        root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        root.nextFocusUpId = navViewId
        root.addView(summary)
        root.addView(toolbar)
        (libraryButtons + showButtons).forEach { toolbar.addView(it, LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(8) }) }
        root.addView(FrameLayout(activity).apply {
            addView(recycler, FrameLayout.LayoutParams(-1, -1))
            addView(empty, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        recycler.layoutManager = GridLayoutManager(activity, 6)
        recycler.adapter = posters
        updateToolbar()
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                active.collectLatest { visible ->
                    if (!visible) return@collectLatest
                    while (true) {
                        try {
                            val now = SystemClock.elapsedRealtime()
                            entries = store.snapshot().map { entry ->
                                entry.copy(cover = entry.cover.ifBlank { app.ageRepository.buildCoverUrl(entry.animeId) },
                                    bytesPerSecond = speedTracker.sample(entry.id, entry.bytes, entry.status == DownloadStatus.DOWNLOADING, now))
                            }
                            speedTracker.retain(entries.mapTo(mutableSetOf()) { it.id })
                            render()
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            summary.text = "读取下载失败：${error.message.orEmpty()}"
                        }
                        delay(1000L)
                    }
                }
            }
        }
    }

    fun start() {
        DownloadService.sendResumeDownloads(activity, MediaDownloadService::class.java, false)
        store.resumeQueue()
        active.value = true
    }
    fun stop() { active.value = false; speedTracker.retain(emptySet()) }
    fun requestContentFocus(): Boolean = recycler.getChildAt(0)?.requestFocus() ?: firstToolbarButton().requestFocus()
    fun handleBack(): Boolean {
        if (selectedAnimeId == null) return false
        showLibrary()
        return true
    }

    private fun button(label: String, action: () -> Unit) = Button(activity).apply {
        id = View.generateViewId(); text = label; textSize = 13f
        setTextColor(activity.getColor(R.color.age_text)); setBackgroundResource(R.drawable.bg_focusable_small)
        setPadding(dp(12), 0, dp(12), 0); nextFocusUpId = navViewId
        setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun size(bytes: Long) = Formatter.formatFileSize(activity, bytes)
    private fun firstToolbarButton() = if (selectedAnimeId == null) libraryButtons[filter] else backButton
    private fun selectedEntries() = entries.filter { it.animeId == selectedAnimeId }

    private fun applyFilter(value: Int) { filter = value; updateToolbar(); render() }

    private fun updateToolbar() {
        libraryButtons.forEachIndexed { index, button -> button.isVisible = selectedAnimeId == null; button.isSelected = index == filter }
        showButtons.forEach { it.isVisible = selectedAnimeId != null }
        val buttons = if (selectedAnimeId == null) libraryButtons else showButtons
        buttons.forEachIndexed { index, button ->
            button.nextFocusLeftId = buttons.getOrNull(index - 1)?.id ?: button.id
            button.nextFocusRightId = buttons.getOrNull(index + 1)?.id ?: button.id
            button.nextFocusDownId = recycler.id
        }
        recycler.nextFocusUpId = firstToolbarButton().id
    }

    private fun openShow(show: DownloadShow) {
        libraryPosition = (recycler.layoutManager as GridLayoutManager).findFirstVisibleItemPosition().coerceAtLeast(0)
        selectedAnimeId = show.animeId
        recycler.layoutManager = GridLayoutManager(activity, 4)
        recycler.adapter = episodes
        updateToolbar()
        render { recycler.post { requestContentFocus() } }
    }

    private fun showLibrary() {
        val previous = selectedAnimeId
        selectedAnimeId = null
        recycler.layoutManager = GridLayoutManager(activity, 6)
        recycler.adapter = posters
        updateToolbar()
        render {
            val position = posters.currentList.indexOfFirst { it.animeId == previous }.takeIf { it >= 0 } ?: libraryPosition
            recycler.scrollToPosition(position.coerceIn(0, (posters.itemCount - 1).coerceAtLeast(0)))
            recycler.post { recycler.findViewWithTag<View>("download_show_$previous")?.requestFocus() ?: firstToolbarButton().requestFocus() }
        }
    }

    private fun render(after: (() -> Unit)? = null) {
        val groups = groupDownloads(entries)
        val selected = groups.firstOrNull { it.animeId == selectedAnimeId }
        if (selectedAnimeId != null && selected == null) { showLibrary(); return }
        val speed = entries.sumOf { it.bytesPerSecond }
        val available = StatFs(activity.filesDir.path).availableBytes
        summary.text = if (selected == null) {
            "下载 · ${groups.size} 部 / ${entries.size} 集    ${size(speed)}/s    已占用 ${size(store.cache.cacheSpace)} · 可用 ${size(available)}"
        } else "${selected.title} · 已完成 ${selected.complete}/${selected.entries.size} 集    ${size(selected.speed)}/s    长按分集可管理"
        if (selected != null) {
            episodes.submitList(selected.entries, after)
            empty.isVisible = false
        } else {
            val filtered = groups.filter { show -> when (filter) {
                1 -> show.complete < show.entries.size
                2 -> show.complete > 0
                else -> true
            } }
            posters.submitList(filtered, after)
            empty.isVisible = filtered.isEmpty()
            empty.text = if (entries.isEmpty()) "还没有下载\n在详情页长按选集多选，或下载全集" else "这个分类下暂无下载"
        }
    }

    private fun onEpisodeClick(entry: DownloadEntry) {
        if (entry.status == DownloadStatus.COMPLETED) {
            val record = app.playbackStore.getRecord(entry.animeId)?.takeIf {
                !it.completed && it.sourceKey == entry.sourceKey && it.episodeIndex == entry.episodeIndex
            }
            activity.startActivity(PlayerActivity.createIntent(activity, entry.animeId, 1, entry.episodeIndex + 1,
                preferredSourceKey = entry.sourceKey, resumePositionMs = record?.positionMs ?: 0L,
                preferResumePrompt = false, returnToDownloads = true))
        } else showEpisodeActions(entry)
    }

    private fun showEpisodeActions(entry: DownloadEntry) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        when (entry.status) {
            DownloadStatus.COMPLETED -> actions += "播放" to { onEpisodeClick(entry) }
            DownloadStatus.PAUSED, DownloadStatus.FAILED -> actions += "继续 / 重新解析下载" to { store.resume(setOf(entry.id)) }
            DownloadStatus.REMOVING -> return
            else -> actions += "暂停本集" to { store.pause(setOf(entry.id)) }
        }
        actions += "删除本集" to { confirmDelete(listOf(entry)) }
        MaterialAlertDialogBuilder(activity).setTitle("${entry.episodeLabel} · ${entry.statusLabel()}")
            .setItems(actions.map { it.first }.toTypedArray()) { _, index -> act { actions[index].second() } }.show()
    }

    private fun confirmDelete(items: List<DownloadEntry>) {
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(activity).setTitle("删除 ${items.size} 集下载？")
            .setMessage("将取消所选任务并释放已下载的视频空间。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> act { store.remove(items.mapTo(mutableSetOf()) { it.id }) } }.show()
    }

    private fun act(action: () -> Unit) {
        runCatching(action).onFailure { Toast.makeText(activity, "操作失败：${it.message}", Toast.LENGTH_LONG).show() }
    }

    companion object {
        private val ACTIVE_STATUSES = setOf(DownloadStatus.WAITING, DownloadStatus.PREPARING, DownloadStatus.DOWNLOADING)
    }
}
