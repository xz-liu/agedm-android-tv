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
import io.agedm.tv.R
import io.agedm.tv.data.AnimeCard
import io.agedm.tv.data.BangumiCollectionStatus
import io.agedm.tv.databinding.ItemPosterCardBinding
import io.agedm.tv.ui.MainActivity
import io.agedm.tv.ui.loadPosterImage
import kotlinx.coroutines.launch

class PosterCardAdapter(
    private val onSelected: (AnimeCard) -> Unit,
) : RecyclerView.Adapter<PosterCardAdapter.PosterViewHolder>() {

    private companion object {
        const val PAYLOAD_SELECTION = "payload_selection"
        const val PAYLOAD_SCORE = "payload_score"
        const val PAYLOAD_COLLECTION = "payload_collection"
    }

    var onLongClick: ((AnimeCard) -> Unit)? = null
    var onSelectionToggle: ((AnimeCard) -> Unit)? = null
    var selectionMode: Boolean = false
        private set
    var selectedIds: Set<Long> = emptySet()
        private set

    private var items: List<AnimeCard> = emptyList()
    private val scoreCache = mutableMapOf<Long, String>()
    private val inFlightScoreIds = mutableSetOf<Long>()
    private val indexByAnimeId = mutableMapOf<Long, Int>()

    init {
        setHasStableIds(true)
    }

    fun submitList(cards: List<AnimeCard>) {
        val deduplicated = cards.distinctBy { it.animeId }
        items = deduplicated
        deduplicated.forEach { card ->
            if (card.bgmScore.isNotBlank()) {
                scoreCache[card.animeId] = card.bgmScore
            }
        }
        rebuildIndex()
        notifyDataSetChanged()
    }

    fun appendList(cards: List<AnimeCard>) {
        if (cards.isEmpty()) return
        val appended = cards.filter { it.animeId !in indexByAnimeId }
        if (appended.isEmpty()) return
        val start = items.size
        items = items + appended
        appended.forEach { card ->
            if (card.bgmScore.isNotBlank()) {
                scoreCache[card.animeId] = card.bgmScore
            }
        }
        rebuildIndex(start)
        notifyItemRangeInserted(start, appended.size)
    }

    fun updateSelectionState(
        selectionMode: Boolean,
        selectedIds: Set<Long>,
        changedAnimeIds: Collection<Long> = emptyList(),
    ) {
        this.selectionMode = selectionMode
        this.selectedIds = selectedIds
        if (changedAnimeIds.isNotEmpty()) {
            notifyAnimeIdsChanged(changedAnimeIds, PAYLOAD_SELECTION)
        }
    }

    fun refreshSelection(animeIds: Collection<Long>) {
        notifyAnimeIdsChanged(animeIds, PAYLOAD_SELECTION)
    }

    fun refreshCollectionStatuses(animeIds: Collection<Long>) {
        notifyAnimeIdsChanged(animeIds, PAYLOAD_COLLECTION)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PosterViewHolder {
        val binding = ItemPosterCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PosterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PosterViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onBindViewHolder(holder: PosterViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            holder.bind(items[position])
            return
        }
        val item = items[position]
        val payloadSet = payloads.filterIsInstance<String>().toSet()
        var handled = false
        if (PAYLOAD_SELECTION in payloadSet) {
            holder.bindSelectionState(item)
            handled = true
        }
        if (PAYLOAD_SCORE in payloadSet) {
            holder.bindScore(item)
            handled = true
        }
        if (PAYLOAD_COLLECTION in payloadSet) {
            holder.bindCollectionStatus(item)
            handled = true
        }
        if (!handled) {
            holder.bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].animeId

    inner class PosterViewHolder(
        private val binding: ItemPosterCardBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundItem: AnimeCard? = null

        init {
            binding.cardRoot.setOnClickListener {
                val item = boundItem ?: return@setOnClickListener
                if (selectionMode && onSelectionToggle != null) {
                    onSelectionToggle?.invoke(item)
                } else {
                    onSelected(item)
                }
            }
            binding.cardRoot.setOnLongClickListener {
                val item = boundItem ?: return@setOnLongClickListener false
                onLongClick?.let { handler ->
                    handler(item)
                    true
                } ?: false
            }
            binding.cardRoot.setOnFocusChangeListener { _, hasFocus ->
                binding.titleText.setTextColor(if (hasFocus) Color.parseColor("#E8FFF7") else Color.WHITE)
            }
        }

        fun bind(item: AnimeCard) {
            boundItem = item
            binding.posterImage.loadPosterImage(item.cover)
            binding.titleText.text = item.title
            binding.subtitleText.text = item.subtitle.ifBlank { item.description }
            binding.subtitleText.visibility =
                if (binding.subtitleText.text.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.badgeText.text = item.badge
            binding.badgeGradient.visibility = if (item.badge.isBlank()) View.GONE else View.VISIBLE
            binding.badgeText.visibility =
                if (item.badge.isBlank()) View.GONE else View.VISIBLE
            bindScore(item)
            bindCollectionStatus(item)
            binding.cardRoot.tag = MainActivity.animeFocusTag(item.animeId)
            bindSelectionState(item)
        }

        fun bindSelectionState(item: AnimeCard) {
            val selected = item.animeId in selectedIds
            binding.selectionScrim.visibility = if (selectionMode && selected) View.VISIBLE else View.GONE
            binding.selectionText.visibility = if (selectionMode && selected) View.VISIBLE else View.GONE
        }

        fun bindScore(item: AnimeCard) {
            val cachedScore = item.bgmScore.ifBlank { scoreCache[item.animeId].orEmpty() }
            if (cachedScore.isNotBlank()) {
                scoreCache[item.animeId] = cachedScore
                binding.scoreText.text = cachedScore
                binding.scoreText.visibility = View.VISIBLE
                binding.scoreGradient.visibility = View.VISIBLE
                return
            }

            binding.scoreText.visibility = View.GONE
            binding.scoreGradient.visibility = View.GONE
            val lifecycleOwner = binding.root.context.findLifecycleOwner() ?: return

            val app = binding.root.context.applicationContext as? AgeTvApplication ?: return
            if (!inFlightScoreIds.add(item.animeId)) return
            lifecycleOwner.lifecycleScope.launch {
                try {
                    val score = app.ageRepository.ensureBangumiScore(item.animeId, item.title).orEmpty()
                    if (score.isBlank()) return@launch
                    scoreCache[item.animeId] = score
                    notifyAnimeIdsChanged(listOf(item.animeId), PAYLOAD_SCORE)
                } finally {
                    inFlightScoreIds.remove(item.animeId)
                }
            }
        }

        fun bindCollectionStatus(item: AnimeCard) {
            val app = binding.root.context.applicationContext as? AgeTvApplication
            val status = if (app?.bangumiAccountService?.isLoggedIn() == true) {
                app.bangumiAccountService.cachedCollectionStatus(item.animeId)
            } else {
                null
            }
            if (status == null) {
                binding.collectionBadge.visibility = View.GONE
                return
            }
            binding.collectionBadge.text = status.label
            binding.collectionBadge.setBackgroundResource(R.drawable.bg_badge)
            binding.collectionBadge.setTextColor(when (status) {
                BangumiCollectionStatus.WISH -> Color.parseColor("#D4A843")
                BangumiCollectionStatus.DO -> Color.parseColor("#4A90D9")
                BangumiCollectionStatus.COLLECT -> Color.parseColor("#52B76A")
                BangumiCollectionStatus.ON_HOLD -> Color.parseColor("#D05050")
                BangumiCollectionStatus.DROPPED -> Color.parseColor("#8090A0")
            })
            binding.collectionBadge.visibility = View.VISIBLE
        }
    }

    private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? {
        return when (this) {
            is LifecycleOwner -> this
            is ContextWrapper -> baseContext.findLifecycleOwner()
            else -> null
        }
    }

    private fun rebuildIndex(startIndex: Int = 0) {
        if (startIndex <= 0) {
            indexByAnimeId.clear()
        }
        for (index in startIndex until items.size) {
            indexByAnimeId[items[index].animeId] = index
        }
    }

    private fun notifyAnimeIdsChanged(animeIds: Collection<Long>, payload: String) {
        animeIds.asSequence()
            .mapNotNull(indexByAnimeId::get)
            .distinct()
            .forEach { index -> notifyItemChanged(index, payload) }
    }
}
