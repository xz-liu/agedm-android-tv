package io.agedm.tv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.data.PlaybackStore
import io.agedm.tv.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

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
        binding.sourceOrderSettingButton.setOnClickListener { openSourceOrderSelector() }
        binding.updateSettingButton.setOnClickListener { openLatestApk() }
    }

    private fun renderSettings() {
        val speed = app.playbackStore.getPlaybackSpeed()
        val autoNextEnabled = app.playbackStore.isAutoNextEnabled()
        val sourceOrder = app.playbackStore.getSourcePriority()

        binding.speedValueText.text = "${formatSpeed(speed)}x"
        binding.autoNextValueText.text = if (autoNextEnabled) "开" else "关"
        binding.sourceOrderValueText.text = formatSourceOrder(sourceOrder)
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
