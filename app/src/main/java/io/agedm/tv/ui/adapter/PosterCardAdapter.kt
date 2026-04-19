package io.agedm.tv.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.agedm.tv.data.AnimeCard
import io.agedm.tv.databinding.ItemPosterCardBinding
import io.agedm.tv.ui.loadPosterImage

class PosterCardAdapter(
    private val onSelected: (AnimeCard) -> Unit,
) : RecyclerView.Adapter<PosterCardAdapter.PosterViewHolder>() {

    var onLongClick: ((AnimeCard) -> Unit)? = null

    private var items: List<AnimeCard> = emptyList()

    fun submitList(cards: List<AnimeCard>) {
        items = cards
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
                if (binding.subtitleText.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
            binding.badgeText.text = item.badge
            binding.badgeText.visibility =
                if (item.badge.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
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
    }
}
