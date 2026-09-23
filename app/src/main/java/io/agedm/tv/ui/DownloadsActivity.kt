package io.agedm.tv.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadsActivity : AppCompatActivity() {
    private val store get() = (application as AgeTvApplication).offlineDownloads
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private val rows = linkedMapOf<String, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 20, 32, 20)
            setBackgroundColor(getColor(R.color.age_bg))
        }
        root.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        summary = TextView(this).apply { textSize = 18f; setTextColor(getColor(R.color.age_text)); setPadding(0, 12, 0, 16) }
        root.addView(summary)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        DownloadService.sendResumeDownloads(this, MediaDownloadService::class.java, false)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val downloads = withContext(Dispatchers.IO) { store.all() }
                    render(downloads)
                    delay(1000L)
                }
            }
        }
    }

    private fun render(downloads: List<Download>) {
        summary.text = "下载 · 已占用 ${Formatter.formatFileSize(this, store.cache.cacheSpace)}\n" +
            if (downloads.isEmpty()) "暂无下载。在动画详情页选择“下载选集”。" else "选择条目播放或管理；下载完成后可断网观看。"
        val ids = downloads.map { it.request.id }.toSet()
        rows.keys.filter { it !in ids }.forEach { id -> list.removeView(rows.remove(id)) }
        downloads.forEach { download ->
            val row = rows.getOrPut(download.request.id) {
                Button(this).apply {
                    setBackgroundResource(R.drawable.bg_focusable_small)
                    setTextColor(getColor(R.color.age_text))
                    isAllCaps = false
                    minHeight = 72
                    list.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
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
            row.text = "${metadata?.title ?: "动画"} · ${metadata?.episodeLabel.orEmpty()}\n$state · ${Formatter.formatFileSize(this, download.bytesDownloaded)}"
            row.isEnabled = download.state != Download.STATE_REMOVING
            row.setOnClickListener { showActions(download) }
        }
    }

    private fun showActions(download: Download) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        when (download.state) {
            Download.STATE_COMPLETED -> actions += "离线播放" to {
                startActivity(Intent(this, OfflinePlayerActivity::class.java).putExtra("download_id", download.request.id))
            }
            Download.STATE_FAILED -> actions += "重试下载" to { store.retry(download.request) }
            Download.STATE_STOPPED -> actions += "继续下载" to { store.resume(download.request.id) }
            else -> actions += "暂停下载" to { store.pause(download.request.id) }
        }
        actions += "删除下载" to {
            MaterialAlertDialogBuilder(this).setTitle("删除这集下载？")
                .setMessage("将释放已下载的视频空间。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ -> store.remove(download.request.id) }.show()
        }
        MaterialAlertDialogBuilder(this).setTitle("下载管理")
            .setItems(actions.map { it.first }.toTypedArray()) { _, index ->
                runCatching { actions[index].second() }.onFailure {
                    Toast.makeText(this, "操作失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }.show()
    }
}
