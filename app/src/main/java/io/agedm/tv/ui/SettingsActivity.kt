package io.agedm.tv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.data.BangumiMatchCandidate
import io.agedm.tv.data.BangumiMatchIssue
import io.agedm.tv.data.PlaybackStore
import io.agedm.tv.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var bangumiReviewJob: Job? = null
    private var suspiciousBangumiMatches: List<BangumiMatchIssue> = emptyList()

    private val app: AgeTvApplication
        get() = application as AgeTvApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        renderSettings()
    }

    override fun onResume() {
        super.onResume()
        renderSettings()
    }

    private fun setupButtons() {
        binding.backButton.setOnClickListener { finish() }
        binding.speedSettingButton.setOnClickListener { openSpeedSelector() }
        binding.autoNextSettingButton.setOnClickListener { toggleAutoNext() }
        binding.skipIntroSettingButton.setOnClickListener { openSkipIntroSelector() }
        binding.sourceOrderSettingButton.setOnClickListener { openSourceOrderSelector() }
        binding.bangumiLoginSettingButton.setOnClickListener { openBangumiAccount() }
        binding.bangumiMatchReviewSettingButton.setOnClickListener { openBangumiMatchReview() }
        binding.updateSettingButton.setOnClickListener { openLatestApk() }
        binding.clearCacheSettingButton.setOnClickListener { confirmClearCache() }
        binding.clearHistorySettingButton.setOnClickListener { confirmClearHistory() }
    }

    private fun renderSettings() {
        val speed = app.playbackStore.getPlaybackSpeed()
        val autoNextEnabled = app.playbackStore.isAutoNextEnabled()
        val sourceOrder = app.playbackStore.getSourcePriority()
        val skipIntroMs = app.playbackStore.getSkipIntroDurationMs()
        val bangumiAccount = app.bangumiAccountService.currentAccount()

        binding.speedValueText.text = "${formatSpeed(speed)}x"
        binding.autoNextValueText.text = if (autoNextEnabled) "开" else "关"
        binding.skipIntroValueText.text = formatSkipDuration(skipIntroMs)
        binding.sourceOrderValueText.text = formatSourceOrder(sourceOrder)
        binding.bangumiLoginValueText.text = bangumiAccount?.displayName?.ifBlank { bangumiAccount.username } ?: "未登录"
        binding.bangumiLoginHintText.text = if (bangumiAccount == null) {
            "扫码后在手机上输入用户名、密码和验证码。"
        } else {
            "当前账号：${bangumiAccount.username}。登录失效会自动清理，可重新扫码切换，或直接退出。"
        }
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { app.contentCache.sizeBytes() }
            binding.cacheSizeText.text = formatBytes(bytes)
        }
        renderBangumiMatchReviewSummary()
    }

    private fun openSpeedSelector() {
        val labels = arrayOf("1.0x", "1.25x", "1.5x", "2.0x")
        val values = floatArrayOf(1f, 1.25f, 1.5f, 2f)
        val currentSpeed = app.playbackStore.getPlaybackSpeed()
        val currentIndex = values.indexOfFirst { it == currentSpeed }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("默认倍速")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                app.playbackStore.setPlaybackSpeed(values[which])
                renderSettings()
                dialog.dismiss()
            }
            .show()
    }

    private fun toggleAutoNext() {
        val nextValue = !app.playbackStore.isAutoNextEnabled()
        app.playbackStore.setAutoNextEnabled(nextValue)
        renderSettings()
    }

    private fun openSourceOrderSelector() {
        val options = listOf(
            listOf(PlaybackStore.SOURCE_AGE, PlaybackStore.SOURCE_AAFUN, PlaybackStore.SOURCE_DM84),
            listOf(PlaybackStore.SOURCE_AGE, PlaybackStore.SOURCE_DM84, PlaybackStore.SOURCE_AAFUN),
        )
        val labels = arrayOf(
            "AGE → AAFun → DM84",
            "AGE → DM84 → AAFun",
        )
        val currentOrder = app.playbackStore.getSourcePriority()
        val currentIndex = options.indexOfFirst { it == currentOrder }.takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(this)
            .setTitle("外部源顺序")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                app.playbackStore.setSourcePriority(options[which])
                renderSettings()
                dialog.dismiss()
            }
            .show()
    }

    private fun openSkipIntroSelector() {
        val labels = arrayOf("60 秒", "75 秒", "88 秒", "90 秒", "100 秒")
        val values = longArrayOf(60_000L, 75_000L, 88_000L, 90_000L, 100_000L)
        val current = app.playbackStore.getSkipIntroDurationMs()
        val currentIndex = values.indexOfFirst { it == current }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("跳过片头时长")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                app.playbackStore.setSkipIntroDurationMs(values[which])
                renderSettings()
                dialog.dismiss()
            }
            .show()
    }

    private fun openBangumiAccount() {
        val account = app.bangumiAccountService.currentAccount()
        if (account == null) {
            startActivity(BangumiLoginActivity.createIntent(this))
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Bangumi 账户")
            .setMessage("当前已登录：${account.displayName.ifBlank { account.username }}")
            .setPositiveButton("重新扫码登录") { _, _ ->
                startActivity(BangumiLoginActivity.createIntent(this))
            }
            .setNeutralButton("退出登录") { _, _ ->
                app.bangumiAccountService.logout()
                renderSettings()
                Toast.makeText(this, "已退出 Bangumi 账号", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderBangumiMatchReviewSummary() {
        bangumiReviewJob?.cancel()
        binding.bangumiMatchReviewValueText.text = "扫描中"
        binding.bangumiMatchReviewHintText.text = "检查 AGE -> Bangumi 缓存里标题明显不一致的项目，并可手动重新匹配。"
        bangumiReviewJob = lifecycleScope.launch {
            val issues = withContext(Dispatchers.IO) { app.ageRepository.listSuspiciousBangumiMatches() }
            suspiciousBangumiMatches = issues
            updateBangumiMatchReviewSummary(issues)
        }
    }

    private fun updateBangumiMatchReviewSummary(issues: List<BangumiMatchIssue>) {
        binding.bangumiMatchReviewValueText.text = if (issues.isEmpty()) "未发现" else "${issues.size} 项"
        binding.bangumiMatchReviewHintText.text = if (issues.isEmpty()) {
            "当前没有发现标题明显不一致的 Bangumi 匹配缓存。"
        } else {
            "发现 ${issues.size} 项可能误配，打开后可按 OK 进入手动匹配，也可批量重新匹配。"
        }
    }

    private fun openBangumiMatchReview() {
        lifecycleScope.launch {
            val issues = withContext(Dispatchers.IO) { app.ageRepository.listSuspiciousBangumiMatches() }
            suspiciousBangumiMatches = issues
            updateBangumiMatchReviewSummary(issues)
            showBangumiMatchReviewDialog(issues)
        }
    }

    private fun showBangumiMatchReviewDialog(issues: List<BangumiMatchIssue>) {
        if (issues.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Bangumi 可疑匹配")
                .setMessage("当前没有发现标题明显不一致的 AGE -> Bangumi 匹配缓存。")
                .setPositiveButton("关闭", null)
                .show()
            return
        }

        val labels = issues.map { issue ->
            "AGE：${issue.ageTitle}\nBGM：${issue.bangumiTitle}\n${issue.reason}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Bangumi 可疑匹配（${issues.size} 项）")
            .setItems(labels) { _, which ->
                openManualBangumiMatch(issues[which])
            }
            .setPositiveButton("重新匹配这些项") { _, _ ->
                refreshBangumiMatchIssues()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openManualBangumiMatch(issue: BangumiMatchIssue) {
        lifecycleScope.launch {
            val candidates = withContext(Dispatchers.IO) {
                app.ageRepository.searchBangumiManualMatchCandidates(issue.animeId, issue.ageTitle)
            }
            showManualBangumiMatchDialog(issue, candidates)
        }
    }

    private fun showManualBangumiMatchDialog(
        issue: BangumiMatchIssue,
        candidates: List<BangumiMatchCandidate>,
    ) {
        if (candidates.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("手动匹配")
                .setMessage("没有找到可供手动选择的 Bangumi 候选。")
                .setPositiveButton("关闭", null)
                .show()
            return
        }
        val labels = candidates.map { candidate ->
            listOf(candidate.title, candidate.subtitle)
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("手动匹配：${issue.ageTitle}")
            .setItems(labels) { _, which ->
                assignManualBangumiMatch(issue, candidates[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun assignManualBangumiMatch(
        issue: BangumiMatchIssue,
        candidate: BangumiMatchCandidate,
    ) {
        lifecycleScope.launch {
            val metadata = withContext(Dispatchers.IO) {
                app.ageRepository.assignManualBangumiMatch(
                    animeId = issue.animeId,
                    title = issue.ageTitle,
                    subjectId = candidate.subjectId,
                )
            }
            if (metadata == null) {
                Toast.makeText(this@SettingsActivity, "手动匹配失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val issues = withContext(Dispatchers.IO) { app.ageRepository.listSuspiciousBangumiMatches() }
            suspiciousBangumiMatches = issues
            updateBangumiMatchReviewSummary(issues)
            Toast.makeText(
                this@SettingsActivity,
                "已手动匹配到「${candidate.title}」",
                Toast.LENGTH_SHORT,
            ).show()
            showBangumiMatchReviewDialog(issues)
        }
    }

    private fun refreshBangumiMatchIssues() {
        val currentIssues = suspiciousBangumiMatches
        lifecycleScope.launch {
            val remaining = withContext(Dispatchers.IO) { app.ageRepository.refreshSuspiciousBangumiMatches() }
            suspiciousBangumiMatches = remaining
            updateBangumiMatchReviewSummary(remaining)
            val fixedCount = (currentIssues.size - remaining.size).coerceAtLeast(0)
            Toast.makeText(
                this@SettingsActivity,
                if (remaining.isEmpty()) {
                    "已重新处理 $fixedCount 项可疑匹配"
                } else {
                    "已重新处理 $fixedCount 项，剩余 ${remaining.size} 项"
                },
                Toast.LENGTH_SHORT,
            ).show()
            if (remaining.isNotEmpty()) {
                showBangumiMatchReviewDialog(remaining)
            }
        }
    }

    private fun confirmClearCache() {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { app.contentCache.sizeBytes() }
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle("清除缓存")
                .setMessage("将清除 ${formatBytes(bytes)} 的缓存数据，下次打开各页面时需要重新加载。")
                .setPositiveButton("清除") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { app.contentCache.clearAll() }
                        renderSettings()
                        Toast.makeText(this@SettingsActivity, "缓存已清除", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清除观看记录")
            .setMessage("将删除全部观看历史，此操作不可恢复。")
            .setPositiveButton("清除") { _, _ ->
                app.playbackStore.clearAllRecords()
                Toast.makeText(this, "观看记录已清除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openLatestApk() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LATEST_APK_URL))
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, "当前设备没有可用的安装入口", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(intent)
    }

    private fun formatSpeed(speed: Float): String {
        return if (speed % 1f == 0f) {
            String.format("%.1f", speed)
        } else {
            speed.toString()
        }
    }

    private fun formatSkipDuration(ms: Long): String {
        val sec = ms / 1000
        val min = sec / 60
        val rem = sec % 60
        return if (min > 0) "$min 分 $rem 秒" else "$sec 秒"
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
        }
    }

    private fun formatSourceOrder(order: List<String>): String {
        return order.joinToString(" / ") { provider ->
            when (provider) {
                PlaybackStore.SOURCE_AGE -> "AGE"
                PlaybackStore.SOURCE_AAFUN -> "AAFun"
                PlaybackStore.SOURCE_DM84 -> "DM84"
                else -> provider.uppercase()
            }
        }
    }

    companion object {
        private const val LATEST_APK_URL =
            "https://github.com/xz-liu/agedm-android-tv/raw/refs/heads/main/release-assets/agedm-tv-release.apk"

        fun createIntent(context: Context): Intent {
            return Intent(context, SettingsActivity::class.java)
        }
    }
}
