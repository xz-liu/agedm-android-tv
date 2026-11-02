package io.agedm.tv.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun detailStatusText(status: String, updateLabel: String): String {
    // "23:00 第05集" combines a scheduled broadcast time and an episode label.
    val episode = updateLabel.trim().replace(Regex("^\\d{1,2}:\\d{2}\\s+"), "")
    return listOf(status.trim(), episode).filter { it.isNotBlank() }.distinct().joinToString(" · ")
}

internal fun animeUpdateText(updatedAtMs: Long?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
    if (updatedAtMs == null || updatedAtMs <= 0) return null
    val updated = Instant.ofEpochMilli(updatedAtMs).atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val time = updated.format(DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA))
    val label = when (updated.toLocalDate()) {
        today -> "今天 $time"
        today.minusDays(1) -> "昨天 $time"
        else -> updated.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA))
    }
    return "AGE 最近更新：$label（本地时间）"
}
