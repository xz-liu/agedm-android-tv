package io.agedm.tv.ui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.agedm.tv.R
import io.agedm.tv.data.AnimeCard
import io.agedm.tv.data.BrowseSection
import io.agedm.tv.data.EpisodeItem
import io.agedm.tv.data.EpisodeSource
import io.agedm.tv.ui.adapter.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h720dp-land-mdpi")
class RecyclerFocusTest {
    private fun withRecycler(block: (Activity, RecyclerView, Button, () -> Unit) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            activity.setTheme(R.style.Theme_AgeTv)
            val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val nav = Button(activity).apply { text = "导航"; isFocusableInTouchMode = true }
            val recycler = RecyclerView(activity).apply {
                id = View.generateViewId()
                itemAnimator = null
                layoutManager = LinearLayoutManager(activity)
            }
            root.addView(nav, LinearLayout.LayoutParams(200, 60))
            root.addView(recycler, LinearLayout.LayoutParams(960, 660))
            activity.setContentView(root)
            val layout = {
                shadowOf(Looper.getMainLooper()).idle()
                root.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 960, 720)
                recycler.requestLayout()
                recycler.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(660, View.MeasureSpec.EXACTLY))
                recycler.layout(0, 60, 960, 720)
                Unit
            }
            block(activity, recycler, nav, layout)
        } finally { controller.pause().stop().destroy() }
    }

    private fun episodes(count: Int) = (0 until count).map { EpisodeItem(it, "第${it + 1}集", "token$it") }
    private fun source(key: String) = EpisodeSource(key, key, false, episodes(8))
    private fun cards() = (1L..4L).map { AnimeCard(it, "动画$it", "") }

    @Test fun focusedEpisodeSurvivesNewEpisodeSelectionChangeAndReverseOrder() = withRecycler { activity, recycler, _, layout ->
        val adapter = EpisodeAdapter({})
        recycler.layoutManager = GridLayoutManager(activity, 4)
        recycler.adapter = adapter
        adapter.submitList(episodes(8), 0, "a")
        layout()
        val focused = recycler.findViewHolderForAdapterPosition(5)!!
        val stableId = focused.itemId
        assertTrue(focused.itemView.requestFocus())
        adapter.submitList(episodes(9).reversed(), 8, "a")
        layout()
        val current = recycler.findContainingViewHolder((recycler.parent as View).findFocus()!!)!!
        assertEquals(stableId, current.itemId)
        assertEquals(3, current.bindingAdapterPosition)
        // The same episode number in a different source is not the same playable item.
        adapter.submitList(episodes(9).reversed(), 8, "b")
        layout()
        assertNotEquals(stableId, adapter.getItemId(3))
    }

    @Test fun focusedSourceActionSurvivesLoadingLabelAndSourceInsertion() = withRecycler { _, recycler, _, layout ->
        val adapter = SourceAdapter({})
        recycler.adapter = adapter
        adapter.submitList(listOf(source("a")), "a", "加载其他源")
        layout()
        val action = recycler.findViewHolderForAdapterPosition(1)!!
        assertTrue(action.itemView.requestFocus())
        val id = action.itemId
        adapter.submitList(listOf(source("a"), source("b")), "b", "正在加载其他源...")
        layout()
        assertEquals(id, recycler.findContainingViewHolder((recycler.parent as View).findFocus()!!)!!.itemId)
        assertEquals(2, recycler.findContainingViewHolder((recycler.parent as View).findFocus()!!)!!.bindingAdapterPosition)
    }

    @Test fun longEpisodeListRefreshKeepsScrolledFocusInsteadOfReturningToFirstEpisode() = withRecycler { activity, recycler, _, layout ->
        val adapter = EpisodeAdapter({})
        val manager = GridLayoutManager(activity, 4)
        recycler.layoutManager = manager
        recycler.adapter = adapter
        adapter.submitList(episodes(120), 0, "a")
        layout()
        recycler.scrollToPosition(64)
        layout()
        val focused = recycler.findViewHolderForAdapterPosition(64)!!
        assertTrue(focused.itemView.requestFocus())
        val firstVisible = manager.findFirstVisibleItemPosition()
        adapter.submitList(episodes(121), 0, "a")
        layout()
        assertSame(focused.itemView, (recycler.parent as View).findFocus())
        assertEquals(firstVisible, manager.findFirstVisibleItemPosition())
    }

    @Test fun removingFocusedEpisodeFallsBackInsideEpisodeList() = withRecycler { activity, recycler, _, layout ->
        val adapter = EpisodeAdapter({})
        recycler.layoutManager = GridLayoutManager(activity, 4)
        recycler.adapter = adapter
        adapter.submitList(episodes(8), 0, "a")
        layout()
        assertTrue(recycler.findViewHolderForAdapterPosition(5)!!.itemView.requestFocus())
        adapter.submitList(episodes(8).filterNot { it.index == 5 }, 0, "a")
        layout()
        assertTrue(recycler.hasFocus())
        assertNotNull(recycler.findContainingViewHolder((recycler.parent as View).findFocus()!!))
    }

    @Test fun backgroundPosterRefreshDoesNotTakeFocusFromNavigation() = withRecycler { _, recycler, nav, layout ->
        val adapter = PosterCardAdapter({})
        recycler.adapter = adapter
        adapter.submitList(cards())
        layout()
        assertTrue(nav.requestFocus())
        adapter.submitList(listOf(AnimeCard(9, "新动画", "")) + cards())
        layout()
        assertSame(nav, (recycler.parent as View).findFocus())
    }

    @Test fun sectionCountUpdateKeepsFocusedPosterInSameSection() = withRecycler { _, recycler, _, layout ->
        val adapter = BrowseSectionAdapter({})
        recycler.adapter = adapter
        val section = BrowseSection("周日追番", "4 部作品", cards())
        adapter.submitList(listOf(section))
        layout()
        val sectionHolder = recycler.findViewHolderForAdapterPosition(0)!!
        val row = sectionHolder.itemView.findViewById<RecyclerView>(R.id.itemsRecycler)
        val poster = row.findViewHolderForAdapterPosition(1)!!
        assertTrue(poster.itemView.requestFocus())
        adapter.submitList(listOf(section.copy(subtitle = "5 部作品",
            items = listOf(AnimeCard(9, "新动画", "")) + cards())))
        layout()
        assertSame(sectionHolder, recycler.findViewHolderForAdapterPosition(0))
        assertEquals(2L, row.findContainingViewHolder((recycler.parent as View).findFocus()!!)!!.itemId)
    }
}
