package io.agedm.tv.data

import org.junit.Assert.*
import org.junit.Test

class EpisodeListsTest {
    @Test fun missingStreamDoesNotShiftClicksToWrongEpisode() {
        val episodes = parsePlaylistEpisodes(listOf(
            listOf("第一集", "first"), listOf("暂未更新", " "), listOf("第三集", "third"),
        ))
        assertEquals(listOf(0, 1), episodes.map { it.index })
        val selected = episodes.last()
        assertEquals("third", episodes[selected.index].token)
        assertEquals("第三集", selected.label)
    }

    @Test fun malformedRowsAreIgnoredAndFallbackLabelsKeepOriginalNumbers() {
        val episodes = parsePlaylistEpisodes(listOf(emptyList(), listOf(""), listOf(" ", " last ")))
        assertEquals(EpisodeItem(0, "第3集", "last"), episodes.single())
        assertTrue(parsePlaylistEpisodes(emptyList()).isEmpty())
    }
}
