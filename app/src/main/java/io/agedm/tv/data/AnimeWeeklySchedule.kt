package io.agedm.tv.data

/** ISO weekdays (Monday = 1, Sunday = 7), taken from AGE's calendar, not update dates.
 * Keep the site's calendar days: the feed doesn't declare the timezone of its airtimes. */
internal fun AgeHomeResponse.airingWeekdays(animeId: Long): List<Int> = weekList.entries
    .flatMap { (key, items) ->
        val siteDay = key.toIntOrNull()?.takeIf { it in 0..6 } ?: return@flatMap emptyList()
        items.filter { it.id == animeId && it.wd == siteDay && !it.nameForNew.contains("完结") }
            .map { if (siteDay == 0) 7 else siteDay }
    }
    .distinct()
    .sorted()
