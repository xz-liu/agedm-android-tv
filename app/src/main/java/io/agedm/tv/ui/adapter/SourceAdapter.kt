package io.agedm.tv.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.agedm.tv.data.EpisodeSource
import io.agedm.tv.databinding.ItemSourceBinding

class SourceAdapter(
    private val onSelected: (EpisodeSource) -> Unit,
) : RecyclerView.Adapter<SourceAdapter.SourceViewHolder>() {

    private var items: List<EpisodeSource> = emptyList()
    private var selectedKey: String? = null

    fun submitList(sources: List<EpisodeSource>, currentKey: String?) {
        items = sources
        selectedKey = currentKey
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
        val binding = ItemSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SourceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
        holder.bind(items[position], items[position].key == selectedKey)
    }

    override fun getItemCount(): Int = items.size

    inner class SourceViewHolder(
        private val binding: ItemSourceBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: EpisodeSource, selected: Boolean) {
            binding.sourceText.text = if (item.isVipLike) "${item.label} · 解析" else item.label
            binding.sourceText.isSelected = selected
            binding.sourceText.setTextColor(if (selected) Color.parseColor("#052016") else Color.WHITE)
            binding.sourceText.setOnClickListener { onSelected(item) }
        }
    }
}

