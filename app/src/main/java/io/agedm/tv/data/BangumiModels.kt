package io.agedm.tv.data

import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class BangumiMetadata(
    val subjectId: Long,
    val matchedTitle: String = "",
    val title: String = "",
    val titleCn: String = "",
    val score: Double? = null,
    val voteCount: Int = 0,
    val rank: Int? = null,
    val ratingCounts: Map<Int, Int> = emptyMap(),
    val tags: List<BangumiTag> = emptyList(),
    val staff: List<BangumiStaffRole> = emptyList(),
    val comments: List<BangumiComment> = emptyList(),
    val similar: List<AgeRelatedItem> = emptyList(),
    val fetchedAtMs: Long = 0L,
    val isComplete: Boolean = true,
) {
    val scoreLabel: String?
        get() = score?.let { String.format(Locale.US, "%.1f", it) }
}

@Serializable
data class BangumiTag(
    val name: String,
    val count: Int = 0,
)

@Serializable
data class BangumiStaffRole(
    val role: String,
    val names: List<String> = emptyList(),
)

@Serializable
data class BangumiComment(
    val user: String,
    val state: String = "",
    val time: String = "",
    val content: String = "",
    val score: Int? = null,
)

data class BangumiMatchIssue(
    val animeId: Long,
    val ageTitle: String,
    val bangumiTitle: String,
    val subjectId: Long,
    val reason: String,
)

data class BangumiMatchCandidate(
    val subjectId: Long,
    val title: String,
    val subtitle: String = "",
)

data class BangumiRematchSummary(
    val ageEntries: Int,
    val indexedSubjects: Int,
    val matchedEntries: Int,
    val missingEntries: Int,
)
