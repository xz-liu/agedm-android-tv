package io.agedm.tv.ui

import android.content.Intent
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.R
import io.agedm.tv.data.OfflineEpisode
import io.agedm.tv.service.MediaDownloadService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Download content within the main navigation, retaining rows and focus across progress updates. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class DownloadsPanel(
    private val activity: AppCompatActivity,
    private val root: LinearLayout,
    private val navViewId: Int,
) {
    private val store get() = (activity.application as AgeTvApplication).offlineDownloads
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val summary = TextView(activity).apply {
        textSize = 16f
        setTextColor(activity.getColor(R.color.age_text))
        setPadding(0, dp(8), 0, dp(12))
        text = "正在读取下载…"
    }
    private val rows = linkedMapOf<String, Button>()
    private val active = MutableStateFlow(false)

    init {
        root.setPadding(dp(12), dp(4), dp(12), dp(8))
        root.nextFocusUpId = navViewId
        root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        root.addView(summary)
        root.addView(ScrollView(activity).apply {
            isFocusable = false
            addView(list)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                active.collectLatest { visible ->
                    if (!visible) return@collectLatest
                    while (true) {
                        try {
                            val downloads = withContext(Dispatchers.IO) { store.all() }
                            render(downloads)
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
        active.value = true
    }

    fun stop() {
        active.value = false
    }

    fun requestContentFocus(): Boolean =
        rows.values.firstOrNull { it.isEnabled }?.requestFocus() ?: root.requestFocus()

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun render(downloads: List<Download>) {
        summary.text = "下载 · 已占用 ${Formatter.formatFileSize(activity, store.cache.cacheSpace)}\n" +
            if (downloads.isEmpty()) "暂无下载。在动画详情页选择“下载选集”。" else "选择条目播放或管理；下载完成后可断网观看。"
        val ids = downloads.map { it.request.id }.toSet()
        var removedFocusedRow = false
        rows.keys.filter { it !in ids }.forEach { id ->
            val row = rows.remove(id) ?: return@forEach
            removedFocusedRow = removedFocusedRow || row.hasFocus()
            list.removeView(row)
        }
        downloads.forEach { download ->
            val row = rows.getOrPut(download.request.id) {
                Button(activity).apply {
                    setBackgroundResource(R.drawable.bg_focusable_small)
                    setTextColor(activity.getColor(R.color.age_text))
                    id = View.generateViewId()
                    isAllCaps = false
                    minHeight = dp(56)
                    list.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
                }
            }
            val metadata = runCatching { OfflineEpisode.decode(download.request) }.getOrNull()
            val state = when (download.state) {
                Download.STATE_COMPLETED -> "已完成 · 可离线播放"
                Download.STATE_DOWNLOADING -> "下载中 ${download.percentDownloaded.takeIf { it >= 0 }?.toInt()?.let { "$it%" } ?: ""}"
                Download.STATE_STOPPED -> "已暂停"
                Download.STATE_FAILED -> "下载失败 · 可重试；地址过期请删除后到详情页重新下载"
                Download.STATE_REMOVING -> "正在删除"
                else -> if (store.manager.notMetRequirements != 0) "等待网络" else "排队中"
            }
            row.text = "${metadata?.title ?: "动画"} · ${metadata?.episodeLabel.orEmpty()}\n$state · ${Formatter.formatFileSize(activity, download.bytesDownloaded)}"
            row.isEnabled = download.state != Download.STATE_REMOVING
            row.setOnClickListener { showActions(download) }
        }
        val enabledRows = rows.values.filter { it.isEnabled }
        enabledRows.forEachIndexed { index, row ->
            row.nextFocusUpId = enabledRows.getOrNull(index - 1)?.id ?: navViewId
            row.nextFocusDownId = enabledRows.getOrNull(index + 1)?.id ?: row.id
            row.nextFocusLeftId = row.id
            row.nextFocusRightId = row.id
        }
        if (removedFocusedRow && root.isShown) requestContentFocus()
    }

    private fun showActions(download: Download) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        when (download.state) {
            Download.STATE_COMPLETED -> actions += "离线播放" to {
                activity.startActivity(Intent(activity, OfflinePlayerActivity::class.java).putExtra("download_id", download.request.id))
            }
            Download.STATE_FAILED -> actions += "重试下载" to { store.retry(download.request) }
            Download.STATE_STOPPED -> actions += "继续下载" to { store.resume(download.request.id) }
            else -> actions += "暂停下载" to { store.pause(download.request.id) }
        }
        actions += "删除下载" to {
            MaterialAlertDialogBuilder(activity).setTitle("删除这集下载？")
                .setMessage("将释放已下载的视频空间。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ -> store.remove(download.request.id) }.show()
        }
        MaterialAlertDialogBuilder(activity).setTitle("下载管理")
            .setItems(actions.map { it.first }.toTypedArray()) { _, index ->
                runCatching { actions[index].second() }.onFailure {
                    Toast.makeText(activity, "操作失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }.show()
    }
}
