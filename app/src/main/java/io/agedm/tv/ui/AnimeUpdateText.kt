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
    val weekday = weekdayText(updated.dayOfWeek.value)
    val label = when (updated.toLocalDate()) {
        today -> "今天（$weekday）$time"
        today.minusDays(1) -> "昨天（$weekday）$time"
        else -> updated.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)) + "（$weekday）$time"
    }
    return "AGE 最近更新：$label（本地时间）"
}

internal fun animeWeeklyScheduleText(status: String, weekdays: List<Int>): String? {
    if (status.trim() != "连载") return null
    val days = weekdays.filter { it in 1..7 }.distinct().sorted()
    if (days.isEmpty()) return null
    return "每" + days.joinToString("、") { weekdayText(it) } + "（AGE 周播表）"
}

private fun weekdayText(isoDay: Int): String = "周" + "一二三四五六日"[isoDay - 1]
