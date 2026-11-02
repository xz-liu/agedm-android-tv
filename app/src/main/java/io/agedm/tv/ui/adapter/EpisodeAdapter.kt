package io.agedm.tv.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.agedm.tv.data.EpisodeItem
import io.agedm.tv.databinding.ItemEpisodeBinding

class EpisodeAdapter(
    private val onSelected: (EpisodeItem) -> Unit,
    private val onLongSelected: ((EpisodeItem) -> Unit)? = null,
) : RecyclerView.Adapter<EpisodeAdapter.EpisodeViewHolder>() {

    private var items: List<EpisodeItem> = emptyList()
    private var multiSelection: Set<Int>? = null
    private var selectedIndex: Int = RecyclerView.NO_POSITION
    private var sourceKey: String? = null
    private val ids = StableItemIds()
    private var recycler: RecyclerView? = null

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = ids.id(sourceKey to items[position].index)

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recycler = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun submitList(episodes: List<EpisodeItem>, currentIndex: Int, sourceKey: String? = null) {
        recycler?.retainFocusBeforeRemoval(episodes.mapTo(mutableSetOf()) { ids.id(sourceKey to it.index) })
        val sameSource = this.sourceKey == sourceKey
        val oldSelected = items.getOrNull(selectedIndex)?.index
        val diff = itemDiff(items, episodes, { a, b -> sameSource && a.index == b.index }) { a, b ->
            a == b && (a.index == oldSelected) == (b.index == currentIndex)
        }
        items = episodes
        this.sourceKey = sourceKey
        selectedIndex = episodes.indexOfFirst { it.index == currentIndex }
        diff.dispatchUpdatesTo(this)
    }

    fun setMultiSelection(indices: Set<Int>?) {
        if (multiSelection == indices) return
        multiSelection = indices?.toSet()
        notifyItemRangeChanged(0, items.size, "selection")
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int, payloads: MutableList<Any>) {
        onBindViewHolder(holder, position)
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
            val checked = multiSelection?.contains(item.index)
            binding.episodeText.text = when (checked) {
                true -> "✓ ${item.label}"
                false -> "□ ${item.label}"
                null -> item.label
            }
            binding.episodeText.isSelected = checked ?: selected
            binding.episodeText.setTextColor(Color.WHITE)
            binding.episodeText.contentDescription = binding.episodeText.text
            binding.episodeText.setOnClickListener { onSelected(item) }
            if (onLongSelected != null) {
                binding.episodeText.setOnLongClickListener { onLongSelected.invoke(item); true }
            }
        }
    }
}

