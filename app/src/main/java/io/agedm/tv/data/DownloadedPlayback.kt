package io.agedm.tv.data

/** Keep original episode identities when only a few old downloads remain after metadata eviction. */
fun EpisodeSource.episodeIndexOrFirst(requested: Int): Int =
    episodes.firstOrNull { it.index == requested }?.index ?: episodes.firstOrNull()?.index ?: 0

fun mergeDownloadedDetail(
    base: AnimeDetail?,
    downloads: List<OfflineEpisode>,
    tasks: List<QueuedEpisode>,
): AnimeDetail? {
    if (downloads.isEmpty()) return base
    val first = downloads.first()
    val sourceList = base?.sources.orEmpty().toMutableList()
    downloads.groupBy { it.sourceKey }.forEach { (key, episodes) ->
        val position = sourceList.indexOfFirst { it.key == key }
        val queued = tasks.firstOrNull { it.sourceKey == key }
        val source = sourceList.getOrNull(position) ?: queued?.source()
            ?: EpisodeSource(key, episodes.first().sourceLabel, false, emptyList())
        val merged = (source.episodes + episodes.map { metadata ->
            tasks.firstOrNull { it.sourceKey == key && it.episodeIndex == metadata.episodeIndex }?.episode()
                ?: EpisodeItem(metadata.episodeIndex, metadata.episodeLabel, "")
        }).distinctBy { it.index }.sortedBy { it.index }
        if (position >= 0) sourceList[position] = source.copy(episodes = merged)
        else sourceList.add(source.copy(episodes = merged))
    }
    return base?.copy(sources = sourceList) ?: AnimeDetail(first.animeId, first.title, first.cover, "", "", "",
        sources = sourceList, related = emptyList(), similar = emptyList(), vipSourceKeys = emptySet(),
        playerJx = tasks.firstOrNull()?.playerJx ?: PlayerJx())
}
