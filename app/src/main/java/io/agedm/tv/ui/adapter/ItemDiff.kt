package io.agedm.tv.ui.adapter

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/** Identity must not change when labels, episode counts or selection change. */
internal class StableItemIds {
    private val ids = mutableMapOf<Any, Long>()
    fun id(key: Any): Long = ids.getOrPut(key) { ids.size.toLong() }
}

/** Hand focus to a surviving visible neighbor before removing its view. No delayed focus request:
 * a later remote press always wins. If the list is emptied, normal parent navigation takes over. */
internal fun RecyclerView.retainFocusBeforeRemoval(retainedIds: Set<Long>) {
    val focused = findFocus()?.let(::findContainingViewHolder) ?: return
    if (focused.itemId in retainedIds) return
    val neighbor = (0 until childCount).map { getChildViewHolder(getChildAt(it)) }
        .filter { it.itemId in retainedIds && it.bindingAdapterPosition != RecyclerView.NO_POSITION }
        .minByOrNull { abs(it.bindingAdapterPosition - focused.bindingAdapterPosition) }
    neighbor?.itemView?.requestFocus()
}

internal fun <T> itemDiff(
    old: List<T>,
    new: List<T>,
    sameItem: (T, T) -> Boolean,
    sameContent: (T, T) -> Boolean = { a, b -> a == b },
): DiffUtil.DiffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
    override fun getOldListSize() = old.size
    override fun getNewListSize() = new.size
    override fun areItemsTheSame(oldPosition: Int, newPosition: Int) = sameItem(old[oldPosition], new[newPosition])
    override fun areContentsTheSame(oldPosition: Int, newPosition: Int) = sameContent(old[oldPosition], new[newPosition])
    override fun getChangePayload(oldPosition: Int, newPosition: Int): Any = Unit
})
