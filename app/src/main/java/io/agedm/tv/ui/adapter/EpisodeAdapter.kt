package io.agedm.tv.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.agedm.tv.data.EpisodeItem
import io.agedm.tv.databinding.ItemEpisodeBinding

class EpisodeAdapter(
    private val onSelected: (EpisodeItem) -> Unit,
) : RecyclerView.Adapter<EpisodeAdapter.EpisodeViewHolder>() {

    private var items: List<EpisodeItem> = emptyList()
    private var selectedIndex: Int = RecyclerView.NO_POSITION

    fun submitList(episodes: List<EpisodeItem>, currentIndex: Int) {
        items = episodes
        selectedIndex = currentIndex
        notifyDataSetChanged()
    }

    fun selectedPosition(): Int = selectedIndex

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val binding = ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return EpisodeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        holder.bind(items[position], position == selectedIndex)
    }

    override fun getItemCount(): Int = items.size

    inner class EpisodeViewHolder(
        private val binding: ItemEpisodeBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: EpisodeItem, selected: Boolean) {
            binding.episodeText.text = item.label
            binding.episodeText.isSelected = selected
            binding.episodeText.setTextColor(if (selected) Color.parseColor("#052016") else Color.WHITE)
            binding.episodeText.setOnClickListener { onSelected(item) }
        }
    }
}

