package io.agedm.tv.data

import kotlinx.serialization.Serializable

@Serializable
data class BangumiAccountSession(
    val username: String,
    val displayName: String = "",
    val avatarUrl: String = "",
    val cookies: Map<String, String> = emptyMap(),
    val lastValidatedMs: Long = 0L,
)

enum class BangumiCollectionStatus(
    val wireName: String,
    val interestValue: String,
    val label: String,
) {
    WISH("wish", "1", "想看"),
    DO("do", "3", "在看"),
    COLLECT("collect", "2", "看过"),
    ON_HOLD("on_hold", "4", "搁置"),
    DROPPED("dropped", "5", "抛弃"),
    ;

    companion object {
        fun fromWireName(raw: String?): BangumiCollectionStatus? {
            return entries.firstOrNull { it.wireName == raw }
        }

        fun fromInterestValue(raw: String?): BangumiCollectionStatus? {
            return entries.firstOrNull { it.interestValue == raw }
        }
    }
}

@Serializable
data class BangumiCollectionCacheEntry(
    val subjectId: Long = 0L,
    val status: String = "",
    val updatedAtMs: Long = 0L,
)

data class BangumiMyPageData(
    val username: String,
    val displayName: String,
    val sections: List<BrowseSection>,
)

data class BangumiLoginPage(
    val sessionId: String,
)

data class BangumiLoginResult(
    val success: Boolean,
    val message: String,
    val account: BangumiAccountSession? = null,
)
