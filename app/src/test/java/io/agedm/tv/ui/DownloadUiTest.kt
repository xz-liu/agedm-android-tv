package io.agedm.tv.ui

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import io.agedm.tv.R
import io.agedm.tv.data.EpisodeItem
import io.agedm.tv.data.DownloadEntry
import io.agedm.tv.data.DownloadStatus
import io.agedm.tv.data.groupDownloads
import io.agedm.tv.databinding.ItemDownloadPosterBinding
import io.agedm.tv.ui.adapter.EpisodeAdapter
import io.agedm.tv.ui.adapter.DownloadPosterAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-television-mdpi")
class DownloadUiTest {
    private fun parent() = FrameLayout(ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_AgeTv))

    @Test fun parallelSettingIsReachableFromPlaybackAndSourceRows() {
        val context = parent().context
        val binding = io.agedm.tv.databinding.ActivitySettingsBinding.inflate(android.view.LayoutInflater.from(context))
        assertTrue(binding.downloadParallelSettingButton.isFocusable)
        assertEquals(binding.downloadParallelSettingButton.id, binding.skipIntroSettingButton.nextFocusDownId)
        assertEquals(binding.skipIntroSettingButton.id, binding.downloadParallelSettingButton.nextFocusUpId)
        assertEquals(binding.sourceOrderSettingButton.id, binding.downloadParallelSettingButton.nextFocusDownId)
        assertEquals(binding.downloadParallelSettingButton.id, binding.sourceOrderSettingButton.nextFocusUpId)
    }

    @Test fun longPressSelectsOriginalEpisodeWithoutStartingPlayback() {
        var played: Int? = null
        var selected: Int? = null
        val adapter = EpisodeAdapter({ played = it.index }, { selected = it.index })
        val episodes = (0..1200).map { EpisodeItem(it, "第${it + 1}集", "token") }.reversed()
        adapter.submitList(episodes, 999)
        val holder = adapter.onCreateViewHolder(parent(), 0)
        adapter.onBindViewHolder(holder, 201)
        assertTrue(holder.itemView.performLongClick())
        assertEquals(999, selected)
        assertNull(played)
        adapter.setMultiSelection(setOf(999))
        adapter.onBindViewHolder(holder, 201, mutableListOf("selection"))
        assertTrue((holder.itemView as TextView).text.startsWith("✓"))
        assertEquals("✓ 第1000集", holder.itemView.contentDescription.toString())
    }

    @Test fun posterProgressUpdatesReuseFocusedCardAndFitLongSeriesCounts() {
        val adapter = DownloadPosterAdapter { }
        val entry = DownloadEntry("id", 1, "这是一部拥有很多集数的长篇动画", "", "s", "S", 0, "1", DownloadStatus.DOWNLOADING)
        val show = groupDownloads(listOf(entry)).single()
        adapter.submitList(listOf(show))
        val parent = parent()
        val holder = adapter.onCreateViewHolder(parent, 0)
        parent.addView(holder.itemView)
        adapter.onBindViewHolder(holder, 0)
        holder.itemView.requestFocus()
        val id = adapter.getItemId(0)
        adapter.onBindViewHolder(holder, 0, mutableListOf("progress"))
        assertTrue(holder.itemView.isFocused)
        assertEquals(id, adapter.getItemId(0))
        val binding = ItemDownloadPosterBinding.bind(holder.itemView)
        binding.countText.text = "已完成 999 / 1200 集"
        holder.itemView.measure(View.MeasureSpec.makeMeasureSpec(146, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.AT_MOST))
        holder.itemView.layout(0, 0, holder.itemView.measuredWidth, holder.itemView.measuredHeight)
        assertTrue("counts must fit the poster card", binding.countText.paint.measureText(binding.countText.text.toString()) <= binding.countText.width)
        assertEquals(196, binding.posterImage.height)
        val bitmap = android.graphics.Bitmap.createBitmap(holder.itemView.width, holder.itemView.height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(parent.context.getColor(R.color.age_bg))
        holder.itemView.draw(canvas)
        java.io.File("build/reports/download-card.png").apply { parentFile.mkdirs() }.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        assertTrue("card=${holder.itemView.measuredHeight}, count=${binding.countText.height}, speed=${binding.speedText.height}, state=${binding.stateText.height}", holder.itemView.measuredHeight < 360)
    }
}
