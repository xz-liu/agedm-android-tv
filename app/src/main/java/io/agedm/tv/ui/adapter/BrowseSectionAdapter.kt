package io.agedm.tv.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.agedm.tv.data.AnimeCard
import io.agedm.tv.data.BrowseSection
import io.agedm.tv.databinding.ItemBrowseSectionBinding

class BrowseSectionAdapter(
    private val onSelected: (AnimeCard) -> Unit,
) : RecyclerView.Adapter<BrowseSectionAdapter.SectionViewHolder>() {

    var onLongClick: ((AnimeCard) -> Unit)? = null
    var onSelectionToggle: ((AnimeCard) -> Unit)? = null
    var selectionMode: Boolean = false
    var selectedIds: Set<Long> = emptySet()

    private var items: List<BrowseSection> = emptyList()

    fun submitList(sections: List<BrowseSection>) {
        items = sections
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionViewHolder {
        val binding = ItemBrowseSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SectionViewHolder(binding, onSelected)
    }

    override fun onBindViewHolder(holder: SectionViewHolder, position: Int) {
        holder.bind(
            item = items[position],
            onLongClick = onLongClick,
            onSelectionToggle = onSelectionToggle,
            selectionMode = selectionMode,
            selectedIds = selectedIds,
        )
    }

    override fun getItemCount(): Int = items.size

    class SectionViewHolder(
        private val binding: ItemBrowseSectionBinding,
        onSelected: (AnimeCard) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        private val posterAdapter = PosterCardAdapter(onSelected)

        init {
            binding.itemsRecycler.layoutManager =
                LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
            binding.itemsRecycler.adapter = posterAdapter
            binding.itemsRecycler.itemAnimator = null
        }

        fun bind(
            item: BrowseSection,
            onLongClick: ((AnimeCard) -> Unit)?,
            onSelectionToggle: ((AnimeCard) -> Unit)?,
            selectionMode: Boolean,
            selectedIds: Set<Long>,
        ) {
            binding.sectionTitle.text = item.title
            binding.sectionSubtitle.text = item.subtitle
            binding.sectionSubtitle.visibility =
                if (item.subtitle.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
            posterAdapter.onLongClick = onLongClick
            posterAdapter.onSelectionToggle = onSelectionToggle
            posterAdapter.selectionMode = selectionMode
            posterAdapter.selectedIds = selectedIds
            posterAdapter.submitList(item.items)
        }
    }
}
