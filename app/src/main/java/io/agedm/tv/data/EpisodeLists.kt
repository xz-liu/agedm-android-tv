package io.agedm.tv.data

/** UI, history and player selections all use positions in the playable list. */
internal fun parsePlaylistEpisodes(rows: List<List<String>>): List<EpisodeItem> =
    rows.mapIndexedNotNull { originalIndex, row ->
        val token = row.getOrNull(1)?.trim().orEmpty()
        if (token.isEmpty()) null else EpisodeItem(
            index = originalIndex,
            label = row.getOrNull(0)?.trim().orEmpty().ifBlank { "第${originalIndex + 1}集" },
            token = token,
        )
    }.mapIndexed { position, episode -> episode.copy(index = position) }
