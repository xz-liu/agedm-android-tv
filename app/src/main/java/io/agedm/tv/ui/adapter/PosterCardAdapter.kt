package io.agedm.tv.ui.adapter

import android.graphics.Color
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.data.AnimeCard
import io.agedm.tv.databinding.ItemPosterCardBinding
import io.agedm.tv.ui.loadPosterImage
import kotlinx.coroutines.launch

class PosterCardAdapter(
    private val onSelected: (AnimeCard) -> Unit,
) : RecyclerView.Adapter<PosterCardAdapter.PosterViewHolder>() {

    var onLongClick: ((AnimeCard) -> Unit)? = null

    private var items: List<AnimeCard> = emptyList()
    private val scoreCache = mutableMapOf<Long, String>()
    private val requestedScoreIds = mutableSetOf<Long>()

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
            binding.cardRoot.setOnClickListener { onSelected(item) }
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
            val lifecycleOwner = binding.root.findViewTreeLifecycleOwner() ?: return
            val app = binding.root.context.applicationContext as? AgeTvApplication ?: return
            if (!requestedScoreIds.add(item.animeId)) return
            lifecycleOwner.lifecycleScope.launch {
                val score = app.ageRepository.ensureBangumiScore(item.animeId, item.title).orEmpty()
                if (score.isBlank()) return@launch
                scoreCache[item.animeId] = score
                val position = items.indexOfFirst { it.animeId == item.animeId }
                if (position >= 0) {
                    notifyItemChanged(position)
                } else {
                    notifyDataSetChanged()
                }
            }
        }
    }
}
