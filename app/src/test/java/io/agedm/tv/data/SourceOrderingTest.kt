package io.agedm.tv.data

import org.junit.Assert.*
import org.junit.Test

class SourceOrderingTest {
    private fun source(key: String, count: Int, provider: String = "age") = EpisodeSource(
        key, key, false, (0 until count).map { EpisodeItem(it, "第${it + 1}集", "$key/$it") },
        provider, if (provider == "age") SourceResolver.AGE_PARSER else SourceResolver.WEB_PAGE,
    )

    @Test fun weeklyUpdateReplacesEpisodesForExistingSource() {
        val old = listOf(source("age1", 11), source("external1", 11, "aafun"))
        val fresh = source("external1", 12, "aafun")
        val merged = old.mergeDistinctSources(listOf(fresh, source("external2", 12, "dm84")))
        assertEquals(listOf("age1", "external1", "external2"), merged.map { it.key })
        assertEquals(12, merged[1].episodes.size)
        assertEquals("第12集", merged[1].episodes.last().label)
        assertEquals(11, old[1].episodes.size)
    }

    @Test fun failedSupplementalRefreshKeepsExistingSources() {
        val old = listOf(source("age1", 12), source("external", 11, "aafun"))
        assertEquals(old, old.mergeDistinctSources(emptyList()))
    }

    @Test fun freshSourcesRemainUniqueAndRespectPriority() {
        val merged = listOf(source("age1", 10), source("dm", 10, "dm84"))
            .mergeDistinctSources(listOf(source("aa", 11, "aafun"), source("aa", 12, "aafun")))
            .orderedByPriority(listOf("aafun", "dm84"))
        assertEquals(listOf("age1", "aa", "dm"), merged.map { it.key })
        assertEquals(12, merged[1].episodes.size)
    }
}
