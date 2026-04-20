package io.agedm.tv.ui.adapter

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.data.AnimeCard
import io.agedm.tv.databinding.ItemPosterCardBinding
import io.agedm.tv.ui.MainActivity
import io.agedm.tv.ui.loadPosterImage
import kotlinx.coroutines.launch

class PosterCardAdapter(
    private val onSelected: (AnimeCard) -> Unit,
) : RecyclerView.Adapter<PosterCardAdapter.PosterViewHolder>() {

    var onLongClick: ((AnimeCard) -> Unit)? = null
    var onSelectionToggle: ((AnimeCard) -> Unit)? = null
    var selectionMode: Boolean = false
    var selectedIds: Set<Long> = emptySet()

    private var items: List<AnimeCard> = emptyList()
    private val scoreCache = mutableMapOf<Long, String>()
    private val inFlightScoreIds = mutableSetOf<Long>()

    init {
        setHasStableIds(true)
    }

    fun submitList(cards: List<AnimeCard>) {
        items = cards
        cards.forEach { card ->
            if (card.bgmScore.isNotBlank()) {
                scoreCache[card.animeId] = card.bgmScore
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PosterViewHolder {
        val binding = ItemPosterCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PosterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PosterViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].animeId

    inner class PosterViewHolder(
        private val binding: ItemPosterCardBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AnimeCard) {
            binding.posterImage.loadPosterImage(item.cover)
            binding.titleText.text = item.title
            binding.subtitleText.text = item.subtitle.ifBlank { item.description }
            binding.subtitleText.visibility =
                if (binding.subtitleText.text.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.badgeText.text = item.badge
            binding.badgeText.visibility =
                if (item.badge.isBlank()) View.GONE else View.VISIBLE
            bindScore(item)
            binding.cardRoot.tag = MainActivity.animeFocusTag(item.animeId)
            val selected = item.animeId in selectedIds
            binding.selectionScrim.visibility = if (selectionMode && selected) View.VISIBLE else View.GONE
            binding.selectionText.visibility = if (selectionMode && selected) View.VISIBLE else View.GONE
            binding.cardRoot.setOnClickListener {
                if (selectionMode && onSelectionToggle != null) {
                    onSelectionToggle?.invoke(item)
                } else {
                    onSelected(item)
                }
            }
            val longClickHandler = onLongClick
            if (longClickHandler != null) {
                binding.cardRoot.setOnLongClickListener { longClickHandler(item); true }
            } else {
                binding.cardRoot.setOnLongClickListener(null)
            }
            binding.cardRoot.setOnFocusChangeListener { _, hasFocus ->
                binding.titleText.setTextColor(if (hasFocus) Color.parseColor("#E8FFF7") else Color.WHITE)
            }
        }

        private fun bindScore(item: AnimeCard) {
            val cachedScore = item.bgmScore.ifBlank { scoreCache[item.animeId].orEmpty() }
            if (cachedScore.isNotBlank()) {
                scoreCache[item.animeId] = cachedScore
                binding.scoreText.text = cachedScore
                binding.scoreText.visibility = View.VISIBLE
                return
            }

            binding.scoreText.visibility = View.GONE
            val lifecycleOwner = binding.root.context.findLifecycleOwner() ?: return
            val app = binding.root.context.applicationContext as? AgeTvApplication ?: return
            if (!inFlightScoreIds.add(item.animeId)) return
            lifecycleOwner.lifecycleScope.launch {
                try {
                    val score = app.ageRepository.ensureBangumiScore(item.animeId, item.title).orEmpty()
                    if (score.isBlank()) return@launch
                    scoreCache[item.animeId] = score
                    val position = items.indexOfFirst { it.animeId == item.animeId }
                    if (position >= 0) {
                        notifyItemChanged(position)
                    } else {
                        notifyDataSetChanged()
                    }
                } finally {
                    inFlightScoreIds.remove(item.animeId)
                }
            }
        }
    }

    private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? {
        return when (this) {
            is LifecycleOwner -> this
            is ContextWrapper -> baseContext.findLifecycleOwner()
            else -> null
        }
    }
}
