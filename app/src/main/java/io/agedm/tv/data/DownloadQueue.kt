package io.agedm.tv.data

import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class PreparationState { WAITING, PREPARING, PAUSED, FAILED, TRANSFERRED }

@Serializable
data class QueuedEpisode(
    val id: String,
    val animeId: Long,
    val title: String,
    val cover: String,
    val sourceKey: String,
    val sourceLabel: String,
    val providerName: String,
    val resolver: SourceResolver,
    val isVipLike: Boolean,
    val pageHeaders: Map<String, String>,
    val playerJx: PlayerJx,
    val episodeIndex: Int,
    val episodeLabel: String,
    val token: String,
    val state: PreparationState = PreparationState.WAITING,
    val error: String = "",
) {
    fun episode() = EpisodeItem(episodeIndex, episodeLabel, token)
    fun source() = EpisodeSource(sourceKey, sourceLabel, isVipLike, listOf(episode()), providerName, resolver, pageHeaders)
    fun detail() = AnimeDetail(animeId, title, cover, "", "", "", sources = listOf(source()),
        related = emptyList(), similar = emptyList(), vipSourceKeys = emptySet(), playerJx = playerJx)

    companion object {
        fun from(detail: AnimeDetail, source: EpisodeSource, episode: EpisodeItem) = QueuedEpisode(
            downloadId(detail.animeId, source.key, episode.index), detail.animeId, detail.title, detail.cover,
            source.key, source.label, source.providerName, source.resolver, source.isVipLike,
            source.pageHeaders, detail.playerJx, episode.index, episode.label, episode.token,
        )
    }
}

/** Atomic snapshots retain unresolved episodes as well as source information for retries. */
class DownloadQueue internal constructor(initial: List<QueuedEpisode>, private val persist: (List<QueuedEpisode>) -> Unit) {
    constructor(file: File) : this(read(file), { writeFile(file, it) })

    private val items = MutableStateFlow(initial.map {
        if (it.state == PreparationState.PREPARING) it.copy(state = PreparationState.WAITING) else it
    })
    val updates = items.asStateFlow()

    @Synchronized fun all(): List<QueuedEpisode> = items.value

    @Synchronized fun add(tasks: List<QueuedEpisode>, existingIds: Set<String>): Int {
        val seen = (items.value.map { it.id } + existingIds).toMutableSet()
        val fresh = tasks.filter { seen.add(it.id) }
        if (fresh.isNotEmpty()) write(items.value + fresh)
        return fresh.size
    }

    @Synchronized fun update(ids: Set<String>, state: PreparationState, error: String = "") {
        write(items.value.map { if (it.id in ids) it.copy(state = state, error = error) else it })
    }

    @Synchronized fun remove(ids: Set<String>) { write(items.value.filterNot { it.id in ids }) }

    private fun write(value: List<QueuedEpisode>) {
        persist(value)
        items.value = value
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private fun read(file: File): List<QueuedEpisode> = try {
            AtomicFile(file).openRead().use { json.decodeFromString(it.readBytes().toString(Charsets.UTF_8)) }
        } catch (_: java.io.FileNotFoundException) { emptyList() }

        private fun writeFile(file: File, value: List<QueuedEpisode>) {
            val storage = AtomicFile(file)
            val stream = storage.startWrite()
            try {
                stream.write(json.encodeToString(value).toByteArray(Charsets.UTF_8))
                storage.finishWrite(stream)
            } catch (error: Exception) {
                storage.failWrite(stream)
                throw error
            }
        }
    }

}

fun downloadId(animeId: Long, sourceKey: String, episodeIndex: Int): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest("$animeId:$sourceKey:$episodeIndex".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
