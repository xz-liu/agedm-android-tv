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
    val totalQueries: Int,
    val searchedQueries: Int,
    val skippedQueries: Int,
    val indexedSubjects: Int,
    val updatedMatches: Int,
    val unchangedMatches: Int,
    val missingEntries: Int,
    val stoppedEarly: Boolean = false,
    val failureMessage: String = "",
)

data class BangumiIndexStats(
    val ageEntries: Int,
    val indexedQueries: Int,
    val indexedSubjects: Int,
)

enum class BangumiRematchStage {
    PREPARING,
    INDEXING,
    MATCHING,
    FINISHED,
}

data class BangumiRematchProgress(
    val stage: BangumiRematchStage,
    val current: Int,
    val total: Int,
    val stageLabel: String,
    val detail: String,
    val searchedQueries: Int = 0,
    val skippedQueries: Int = 0,
    val indexedSubjects: Int = 0,
    val updatedMatches: Int = 0,
    val unchangedMatches: Int = 0,
    val missingEntries: Int = 0,
)
