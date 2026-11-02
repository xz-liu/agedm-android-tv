package io.agedm.tv.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** AGE's `time` is Unix seconds; its matching formatted date is in China Standard Time.
 * Never substitute the cache fetch time or the weekly broadcast schedule. */
internal fun AgeVideo.lastUpdatedEpochMillis(): Long? {
    if (updatedAtSeconds in 1..253402300799L) return updatedAtSeconds * 1000
    return runCatching {
        LocalDateTime.parse(updatedAtText.orEmpty().trim(),
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT))
            .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli().takeIf { it > 0 }
    }.getOrNull()
}
