package io.agedm.tv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder

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
    val bangumi: BangumiMetadata? = null,
    val sources: List<EpisodeSource>,
    val related: List<AgeRelatedItem>,
    val similar: List<AgeRelatedItem>,
    val vipSourceKeys: Set<String>,
    val playerJx: PlayerJx,
)

enum class SourceResolver {
    AGE_PARSER,
    WEB_PAGE,
}

data class EpisodeSource(
    val key: String,
    val label: String,
    val isVipLike: Boolean,
    val episodes: List<EpisodeItem>,
    val providerName: String = "AGE",
    val resolver: SourceResolver = SourceResolver.AGE_PARSER,
    val pageHeaders: Map<String, String> = emptyMap(),
    val matchTitle: String? = null,
)

data class EpisodeItem(
    val index: Int,
    val label: String,
    val token: String,
)

data class SupplementalCandidate(
    val providerId: String,
    val providerDisplayName: String,
    val title: String,
    val url: String,
)

@Serializable
data class AgeHomeResponse(
    val latest: List<AgeRelatedItem> = emptyList(),
    val recommend: List<AgeRelatedItem> = emptyList(),
    @SerialName("week_list")
    val weekList: Map<String, List<AgeScheduleItem>> = emptyMap(),
)

@Serializable
data class AgeScheduleItem(
    val id: Long = 0,
    val wd: Int = 0,
    val name: String = "",
    val mtime: String = "",
    @SerialName("namefornew")
    val nameForNew: String = "",
    @SerialName("isnew")
    val isNew: Int = 0,
)

@Serializable
data class AgePosterListResponse(
    @Serializable(with = FlexibleIntSerializer::class)
    val total: Int = 0,
    val videos: List<AgeRelatedItem> = emptyList(),
)

@Serializable
data class AgeCatalogResponse(
    @Serializable(with = FlexibleIntSerializer::class)
    val total: Int = 0,
    val videos: List<AgeCatalogVideo> = emptyList(),
)

@Serializable
data class AgeCatalogVideo(
    val id: Long = 0,
    val name: String? = null,
    @SerialName("uptodate")
    val updateLabel: String? = null,
    val status: String? = null,
    @SerialName("play_time")
    val playTime: String? = null,
    @SerialName("type")
    val genreType: String? = null,
    @SerialName("name_original")
    val originalName: String? = null,
    @SerialName("name_other")
    val otherName: String? = null,
    val premiere: String? = null,
    val writer: String? = null,
    val tags: String? = null,
    val company: String? = null,
    val intro: String? = null,
    val cover: String? = null,
)

@Serializable
data class AgeSearchResponse(
    val code: Int = 0,
    val message: String = "",
    val data: AgeSearchData = AgeSearchData(),
)

@Serializable
data class AgeSearchData(
    val videos: List<AgeCatalogVideo> = emptyList(),
    @Serializable(with = FlexibleIntSerializer::class)
    val total: Int = 0,
    @SerialName("totalPage")
    @Serializable(with = FlexibleIntSerializer::class)
    val totalPage: Int = 0,
)

@Serializable
data class AgeRankResponse(
    @Serializable(with = FlexibleIntSerializer::class)
    val total: Int = 0,
    val year: String = "",
    val rank: List<List<AgeRankItem>> = emptyList(),
)

@Serializable
data class AgeRankItem(
    @SerialName("NO")
    @Serializable(with = FlexibleIntSerializer::class)
    val order: Int = 0,
    @SerialName("AID")
    val animeId: Long = 0,
    @SerialName("CCnt")
    val countLabel: String = "",
    @SerialName("Title")
    val title: String = "",
)

data class BrowseSection(
    val title: String,
    val subtitle: String = "",
    val items: List<AnimeCard>,
)

data class AnimeCard(
    val animeId: Long,
    val title: String,
    val cover: String,
    val badge: String = "",
    val subtitle: String = "",
    val description: String = "",
    val bgmScore: String = "",
)

data class PagedCards(
    val items: List<AnimeCard>,
    val total: Int,
    val page: Int,
    val size: Int,
)

data class HomeFeed(
    val latest: List<AnimeCard>,
    val recommend: List<AnimeCard>,
    val dailySections: List<BrowseSection>,
)

data class CatalogQuery(
    val page: Int = 1,
    val size: Int = 30,
    val region: String = "all",
    val genre: String = "all",
    val label: String = "all",
    val year: String = "all",
    val season: String = "all",
    val status: String = "all",
    val resource: String = "all",
    val letter: String = "all",
    val order: String = "time",
)

object FlexibleIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Int {
        val jsonDecoder = decoder as? JsonDecoder ?: return 0
        val primitive = jsonDecoder.decodeJsonElement().toString().trim('"')
        return primitive.toIntOrNull() ?: 0
    }

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }
}

data class ResolvedStream(
    val streamUrl: String,
    val parserUrl: String,
    val sourceKey: String,
    val sourceLabel: String,
    val episode: EpisodeItem,
    val isM3u8: Boolean,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
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
