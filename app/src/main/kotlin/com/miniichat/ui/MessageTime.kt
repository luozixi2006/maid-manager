package com.miniichat.ui

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val SAME_DAY_PATTERN = "HH:mm"
private const val SAME_YEAR_PATTERN = "MM-dd HH:mm"
private const val OTHER_YEAR_PATTERN = "yyyy-MM-dd HH:mm"

/**
 * Formats a message timestamp for display using the device time zone and locale.
 *
 * Today: `HH:mm`, another day in the current year: `MM-dd HH:mm`, otherwise
 * `yyyy-MM-dd HH:mm`. The real message creation time is always used, never the
 * current clock time.
 */
fun formatMessageTime(
    createdAt: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String {
    val created = LocalDateTime.ofInstant(Instant.ofEpochMilli(createdAt), zone)
    val reference = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
    val pattern = when {
        created.toLocalDate() == reference.toLocalDate() -> SAME_DAY_PATTERN
        created.year == reference.year -> SAME_YEAR_PATTERN
        else -> OTHER_YEAR_PATTERN
    }
    return DateTimeFormatter.ofPattern(pattern, locale).format(created)
}
