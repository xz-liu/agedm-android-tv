package io.agedm.tv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AgeDetailResponse(
    val video: AgeVideo,
    val series: List<AgeRelatedItem> = emptyList(),
    val similar: List<AgeRelatedItem> = emptyList(),
    @SerialName("player_label_arr")
    val playerLabelMap: Map<String, String> = emptyMap(),
    @SerialName("player_vip")
    val playerVipRaw: String = "",
    @SerialName("player_jx")
    val playerJx: PlayerJx = PlayerJx(),
)

@Serializable
data class AgeVideo(
    val id: Long = 0,
    val name: String = "",
    val cover: String = "",
    @SerialName("intro_html")
    val introHtml: String = "",
    val status: String = "",
    val tags: String = "",
    val playlists: Map<String, List<List<String>>> = emptyMap(),
)

@Serializable
data class AgeRelatedItem(
    @SerialName("AID")
    val animeId: Long = 0,
    @SerialName("Title")
    val title: String = "",
    @SerialName("PicSmall")
    val cover: String = "",
    @SerialName("NewTitle")
    val updateLabel: String = "",
)

@Serializable
data class PlayerJx(
    val vip: String = "",
    @SerialName("zj")
    val direct: String = "",
)

data class AnimeDetail(
    val animeId: Long,
    val title: String,
    val cover: String,
    val introHtml: String,
    val status: String,
    val tags: String,
    val sources: List<EpisodeSource>,
    val related: List<AgeRelatedItem>,
    val similar: List<AgeRelatedItem>,
    val vipSourceKeys: Set<String>,
    val playerJx: PlayerJx,
)

data class EpisodeSource(
    val key: String,
    val label: String,
    val isVipLike: Boolean,
    val episodes: List<EpisodeItem>,
)

data class EpisodeItem(
    val index: Int,
    val label: String,
    val token: String,
)

data class ResolvedStream(
    val streamUrl: String,
    val parserUrl: String,
    val sourceKey: String,
    val sourceLabel: String,
    val episode: EpisodeItem,
    val isM3u8: Boolean,
)

@Serializable
data class PlaybackRecord(
    val animeId: Long,
    val animeTitle: String,
    val detailUrl: String,
    val sourceKey: String,
    val sourceLabel: String,
    val episodeIndex: Int,
    val episodeLabel: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastUpdatedEpochMs: Long,
    val completed: Boolean = false,
)

