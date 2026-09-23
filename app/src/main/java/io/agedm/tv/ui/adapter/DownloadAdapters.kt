package io.agedm.tv.ui.adapter

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.agedm.tv.data.DownloadEntry
import io.agedm.tv.data.DownloadShow
import io.agedm.tv.data.DownloadStatus
import io.agedm.tv.databinding.ItemDownloadEpisodeBinding
import io.agedm.tv.databinding.ItemDownloadPosterBinding
import io.agedm.tv.ui.loadPosterImage

fun DownloadEntry.statusLabel(): String = when (status) {
    DownloadStatus.COMPLETED -> "已完成 · OK 播放"
    DownloadStatus.DOWNLOADING -> "下载中 ${if (percent >= 0) "${percent.toInt()}%" else ""}"
    DownloadStatus.PREPARING -> "正在准备"
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.FAILED -> "失败 · 可重试"
    DownloadStatus.REMOVING -> "正在删除"
    DownloadStatus.WAITING -> message.ifBlank { "排队中" }
}

class DownloadPosterAdapter(private val onClick: (DownloadShow) -> Unit) :
    ListAdapter<DownloadShow, DownloadPosterAdapter.Holder>(object : DiffUtil.ItemCallback<DownloadShow>() {
        override fun areItemsTheSame(a: DownloadShow, b: DownloadShow) = a.animeId == b.animeId
        override fun areContentsTheSame(a: DownloadShow, b: DownloadShow) = a == b
        override fun getChangePayload(a: DownloadShow, b: DownloadShow): Any = "progress"
    }) {
    init { setHasStableIds(true) }
    override fun getItemId(position: Int) = getItem(position).animeId
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemDownloadPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) = holder.bind(getItem(position))
    inner class Holder(private val binding: ItemDownloadPosterBinding) : RecyclerView.ViewHolder(binding.root) {
        private var cover: String? = null
        fun bind(show: DownloadShow) {
            val context = binding.root.context
            if (cover != show.cover) {
                cover = show.cover
                binding.posterImage.loadPosterImage(show.cover)
            }
            binding.titleText.text = show.title
            binding.countText.text = "已完成 ${show.complete} / ${show.entries.size} 集"
            binding.downloadProgress.progress = show.progress
            binding.stateText.text = when {
                show.complete == show.entries.size -> "已下载"
                show.entries.any { it.status == DownloadStatus.DOWNLOADING } -> "正在下载"
                show.entries.any { it.status == DownloadStatus.PREPARING || it.status == DownloadStatus.WAITING } -> "下载队列"
                show.entries.any { it.status == DownloadStatus.FAILED } -> "有失败任务"
                else -> "已暂停"
            }
            binding.speedText.text = if (show.entries.any { it.status == DownloadStatus.DOWNLOADING }) {
                "${Formatter.formatFileSize(context, show.speed)}/s"
            } else Formatter.formatFileSize(context, show.bytes)
            binding.root.contentDescription = "${show.title}，${binding.countText.text}，${binding.stateText.text}"
            binding.root.tag = "download_show_${show.animeId}"
            binding.root.setOnClickListener { onClick(show) }
        }
    }
}

class DownloadEpisodeAdapter(
    private val onClick: (DownloadEntry) -> Unit,
    private val onLongClick: (DownloadEntry) -> Unit,
) : ListAdapter<DownloadEntry, DownloadEpisodeAdapter.Holder>(object : DiffUtil.ItemCallback<DownloadEntry>() {
    override fun areItemsTheSame(a: DownloadEntry, b: DownloadEntry) = a.id == b.id
    override fun areContentsTheSame(a: DownloadEntry, b: DownloadEntry) = a == b
    override fun getChangePayload(a: DownloadEntry, b: DownloadEntry): Any = "progress"
}) {
    private val stableIds = mutableMapOf<String, Long>()
    init { setHasStableIds(true) }
    override fun getItemId(position: Int) = stableIds.getOrPut(getItem(position).id) { stableIds.size.toLong() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemDownloadEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) = holder.bind(getItem(position))
    inner class Holder(private val binding: ItemDownloadEpisodeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: DownloadEntry) {
            binding.titleText.text = entry.episodeLabel
            binding.stateText.text = entry.statusLabel()
            binding.downloadProgress.isIndeterminate = entry.status == DownloadStatus.PREPARING
            binding.downloadProgress.progress = if (entry.status == DownloadStatus.COMPLETED) 100 else entry.percent.toInt().coerceAtLeast(0)
            val size = Formatter.formatFileSize(binding.root.context, entry.bytes)
            val speed = Formatter.formatFileSize(binding.root.context, entry.bytesPerSecond)
            binding.speedText.text = if (entry.status == DownloadStatus.DOWNLOADING) "$speed/s · $size" else "${entry.sourceLabel} · $size"
            binding.root.tag = "download_episode_${entry.id}"
            binding.root.contentDescription = "${entry.episodeLabel}，${binding.stateText.text}，${binding.speedText.text}"
            binding.root.setOnClickListener { onClick(entry) }
            binding.root.setOnLongClickListener { onLongClick(entry); true }
        }
    }
}
