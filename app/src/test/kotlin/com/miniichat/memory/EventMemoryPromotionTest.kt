package com.miniichat.memory

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

/** Raw sensor samples never become memories; only repeated multi-day observations become hypotheses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EventMemoryPromotionTest {
    private val zone = ZoneId.systemDefault()

    private fun at(daysAgo: Long, hour: Int): Long =
        ZonedDateTime.now(zone).minusDays(daysAgo).withHour(hour).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()

    private fun event(id: String, type: String, at: Long, summary: String) =
        JSONObject().put("id", id).put("type", type).put("at", at).put("summary", summary)

    @Test fun threeDistinctDaysOfAnAllowedTypeBecomeOneHypothesis() {
        val now = System.currentTimeMillis()
        val events = listOf(
            event("e1", "walking", at(1, 9), "散步"),
            event("e2", "walking", at(2, 9), "散步"),
            event("e3", "walking", at(3, 9), "散步"))
        val patterns = EventMemoryPromotion.patterns(events, now)
        assertEquals(1, patterns.size)
        val pattern = patterns.single()
        assertTrue(pattern.id.startsWith("pattern:walking:上午"))
        assertEquals("event_pattern", pattern.kind)
        assertEquals(at(1, 9), pattern.at)
        assertTrue(pattern.text.contains("3 个不同日期"))
        assertTrue(pattern.text.contains("上午"))
        assertTrue(pattern.text.contains("可能习惯"))
    }

    @Test fun sameDayDuplicatesAreNotAHabit() {
        val now = System.currentTimeMillis()
        val sameDay = (0 until 6).map { event("e$it", "walking", at(1, 9 + it % 3), "散步") }
        assertTrue(EventMemoryPromotion.patterns(sameDay, now).isEmpty())
        val twoDays = listOf(event("a", "walking", at(1, 9), "散步"), event("b", "walking", at(2, 9), "散步"))
        assertTrue(EventMemoryPromotion.patterns(twoDays, now).isEmpty())
    }

    @Test fun individualHeartAndStepSamplesAreNeverPromoted() {
        val now = System.currentTimeMillis()
        val events = listOf(
            event("h1", "heart", at(1, 9), "静息心率 62"),
            event("h2", "heart", at(2, 9), "静息心率 63"),
            event("h3", "heart", at(3, 9), "静息心率 64"),
            event("h4", "heart_rate", at(4, 9), "心率 70"),
            event("s1", "steps", at(1, 20), "今日 8000 步"),
            event("s2", "steps", at(2, 20), "今日 8200 步"),
            event("s3", "steps", at(3, 20), "今日 7900 步"),
            event("s4", "daily_steps", at(4, 20), "步数汇总"))
        assertTrue(EventMemoryPromotion.patterns(events, now).isEmpty())
    }

    @Test fun occurrencesInDifferentTimeBucketsStaySeparate() {
        val now = System.currentTimeMillis()
        val events = listOf(
            event("a", "walking", at(1, 9), "散步"),
            event("b", "walking", at(2, 14), "散步"),
            event("c", "walking", at(3, 20), "散步"))
        assertTrue(EventMemoryPromotion.patterns(events, now).isEmpty())
    }

    @Test fun eventsOlderThanThirtyDaysAreIgnored() {
        val now = System.currentTimeMillis()
        val events = listOf(
            event("a", "walking", at(31, 9), "散步"),
            event("b", "walking", at(32, 9), "散步"),
            event("c", "walking", at(33, 9), "散步"))
        assertTrue(EventMemoryPromotion.patterns(events, now).isEmpty())
    }
}
