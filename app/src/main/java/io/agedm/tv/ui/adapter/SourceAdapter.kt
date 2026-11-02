package io.agedm.tv.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.agedm.tv.data.EpisodeSource
import io.agedm.tv.databinding.ItemSourceBinding

class SourceAdapter(
    private val onSelected: (EpisodeSource) -> Unit,
    private val onFocused: (EpisodeSource) -> Unit = {},
    private val onAction: (() -> Unit)? = null,
) : RecyclerView.Adapter<SourceAdapter.SourceViewHolder>() {

    sealed interface RowItem {
        data class Source(val source: EpisodeSource) : RowItem
        data class Action(val label: String) : RowItem
    }

    private var items: List<RowItem> = emptyList()
    private var selectedKey: String? = null
    private val ids = StableItemIds()

    init { setHasStableIds(true) }

    private fun RowItem.identity(): Any = when (this) {
        is RowItem.Source -> source.key
        is RowItem.Action -> Unit
    }

    override fun getItemId(position: Int): Long = ids.id(items[position].identity())

    fun submitList(
        sources: List<EpisodeSource>,
        currentKey: String?,
        actionLabel: String? = null,
    ) {
        val updated = buildList {
            addAll(sources.map(RowItem::Source))
            actionLabel?.takeIf { it.isNotBlank() }?.let { add(RowItem.Action(it)) }
        }
        val oldKey = selectedKey
        val diff = itemDiff(items, updated, { a, b -> a.identity() == b.identity() }) { a, b ->
            a == b && ((a as? RowItem.Source)?.source?.key == oldKey) ==
                ((b as? RowItem.Source)?.source?.key == currentKey)
        }
        items = updated
        selectedKey = currentKey
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
        val binding = ItemSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SourceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
        holder.bind(items[position], (items[position] as? RowItem.Source)?.source?.key == selectedKey)
    }

    override fun getItemCount(): Int = items.size

    inner class SourceViewHolder(
        private val binding: ItemSourceBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RowItem, selected: Boolean) {
            when (item) {
                is RowItem.Source -> {
                    binding.sourceText.text = if (item.source.isVipLike) {
                        "${item.source.label} · 解析"
                    } else {
                        item.source.label
                    }
                    binding.sourceText.isSelected = selected
                    binding.sourceText.setTextColor(if (selected) Color.parseColor("#052016") else Color.WHITE)
                    binding.sourceText.setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) {
                            onFocused(item.source)
                        }
                    }
                    binding.sourceText.setOnClickListener { onSelected(item.source) }
                }

                is RowItem.Action -> {
                    binding.sourceText.text = item.label
                    binding.sourceText.isSelected = false
                    binding.sourceText.setTextColor(Color.parseColor("#6ED9B8"))
                    binding.sourceText.setOnFocusChangeListener(null)
                    binding.sourceText.setOnClickListener { onAction?.invoke() }
                }
            }
        }
    }
}
