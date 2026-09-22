package com.miniichat.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class MessageTimeTest {
    private val us = Locale.US
    private val utc = ZoneId.of("UTC")
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun todayShowsHoursAndMinutes() {
        val now = Instant.parse("2026-05-20T10:00:00Z").toEpochMilli()
        val created = Instant.parse("2026-05-20T03:07:00Z").toEpochMilli()
        assertEquals("03:07", formatMessageTime(created, now, utc, us))
    }

    @Test
    fun anotherDayInSameYearShowsMonthDayAndTime() {
        val now = Instant.parse("2026-05-20T10:00:00Z").toEpochMilli()
        val created = Instant.parse("2026-05-19T22:00:00Z").toEpochMilli()
        assertEquals("05-19 22:00", formatMessageTime(created, now, utc, us))
    }

    @Test
    fun earlierYearShowsFullDateAndTime() {
        val now = Instant.parse("2026-05-20T10:00:00Z").toEpochMilli()
        val created = Instant.parse("2024-12-31T23:59:00Z").toEpochMilli()
        assertEquals("2024-12-31 23:59", formatMessageTime(created, now, utc, us))
        // The year shown is the zone-local year of the message.
        assertEquals("2025-01-01 07:59", formatMessageTime(created, now, shanghai, us))
    }

    @Test
    fun timeZoneBoundaryChangesDayClassification() {
        val created = Instant.parse("2026-03-01T23:30:00Z").toEpochMilli()
        val now = Instant.parse("2026-03-02T00:10:00Z").toEpochMilli()
        // UTC: the two instants fall on different days.
        assertEquals("03-01 23:30", formatMessageTime(created, now, utc, us))
        // Asia/Shanghai (+08:00): both instants fall on 2026-03-02.
        assertEquals("07:30", formatMessageTime(created, now, shanghai, us))
    }

    @Test
    fun yearBoundaryInEarlierYearKeepsFullDate() {
        val now = Instant.parse("2026-01-01T00:05:00Z").toEpochMilli()
        val created = Instant.parse("2025-12-31T23:50:00Z").toEpochMilli()
        assertEquals("2025-12-31 23:50", formatMessageTime(created, now, utc, us))
    }
}
