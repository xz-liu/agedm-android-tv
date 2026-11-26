package io.agedm.tv.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.ui.adapter.PosterCardAdapter

class HiddenLibraryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val library = (application as AgeTvApplication).hiddenLibrary
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (16 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
        }
        val title = TextView(this).apply { textSize = 22f }
        root.addView(title)
        val posters = PosterCardAdapter { card ->
            startActivity(DetailActivity.createIntent(this, card.animeId)
                .putExtra(DetailActivity.EXTRA_FROM_LIBRARY, true))
        }
        posters.prefetchScores = false
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@HiddenLibraryActivity, 6)
            adapter = posters
            itemAnimator = null
        }
        root.addView(grid)
        fun render() {
            val cards = library.cards()
            title.text = if (cards.isEmpty()) "隐藏列表 · 暂无作品" else "隐藏列表"
            posters.submitList(cards)
        }
        posters.onLongClick = { card ->
            MaterialAlertDialogBuilder(this).setTitle(card.title).setItems(arrayOf("取消隐藏")) { _, _ ->
                library.restore(card.animeId)
                render()
            }.show()
        }
        setContentView(root)
        render()
        grid.post { grid.getChildAt(0)?.requestFocus() }
    }
}
