package com.miniichat.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveCooldownTest {
    private val now = 1_760_000_000_000L

    private fun minutes(value: Long) = value * 60L * 1000L
    private fun hours(value: Long) = value * 60L * 60L * 1000L

    @Test
    fun emptyChatReturnsNowAndNeverWaits() {
        assertEquals(now, ProactivePolicy.quietUntil(null, 0L, false, now))
        assertEquals(now, ProactivePolicy.quietUntil(null, now - hours(999), true, now))
        assertFalse(ProactivePolicy.shouldWait(null, 0L, false, now))
    }

    @Test
    fun recentUserActivityUsesThreeMinuteQuietWindow() {
        assertEquals(minutes(3), ProactivePolicy.RECENT_USER_ACTIVITY_MILLIS)
        assertTrue(ProactivePolicy.shouldWait("user", now, false, now))
        assertFalse(ProactivePolicy.shouldWait("user", now - minutes(5), false, now))
        assertTrue(ProactivePolicy.shouldWait("user", now - ProactivePolicy.RECENT_USER_ACTIVITY_MILLIS + 1, false, now))
        assertFalse(ProactivePolicy.shouldWait("user", now - ProactivePolicy.RECENT_USER_ACTIVITY_MILLIS, false, now))
        assertFalse(ProactivePolicy.shouldWait("user", now - hours(2), false, now))
        assertEquals(now, ProactivePolicy.quietUntil("user", now - minutes(5), false, now))
    }

    @Test
    fun unansweredProactiveUsesSixtyMinuteDefaultNotTwentyFourHours() {
        assertEquals(minutes(60), ProactivePolicy.UNANSWERED_COOLDOWN_MILLIS)
        // A 90 minute old unanswered proactive message is no longer locked by a 24h cooldown.
        assertFalse(ProactivePolicy.shouldWait("assistant", now - minutes(90), true, now))
        assertEquals(now, ProactivePolicy.quietUntil("assistant", now - minutes(90), true, now))
        // exact 60 minute boundary is eligible; one millisecond younger still waits
        assertFalse(ProactivePolicy.shouldWait("assistant", now - minutes(60), true, now))
        assertTrue(ProactivePolicy.shouldWait("assistant", now - minutes(60) + 1, true, now))
        assertEquals(now + minutes(1), ProactivePolicy.quietUntil("assistant", now - minutes(59), true, now))
    }

    @Test
    fun answeredAssistantMessageIsNotCooldownGated() {
        assertFalse(ProactivePolicy.shouldWait("assistant", now - hours(6), false, now))
        assertFalse(ProactivePolicy.shouldWait("assistant", now - hours(25), false, now))
        assertTrue(ProactivePolicy.shouldWait("assistant", now, false, now))
        assertEquals(now, ProactivePolicy.quietUntil("assistant", now - hours(25), false, now))
    }

    @Test
    fun futureTimestampIsClampedToNowAndCannotLockIndefinitely() {
        assertTrue(ProactivePolicy.shouldWait("user", now + minutes(1), false, now))
        assertTrue(ProactivePolicy.shouldWait("assistant", now + minutes(1), true, now))
        assertEquals(now, ProactivePolicy.quietUntil("user", now + hours(999), false, now))
        assertEquals(now, ProactivePolicy.quietUntil("assistant", now + hours(999), true, now))
    }

    @Test
    fun customUnansweredCooldownReturnsExactDeadlineNotFreshRandomInterval() {
        val cooldown = minutes(30)
        val lastAt = now - minutes(10)
        assertEquals(lastAt + cooldown, ProactivePolicy.quietUntil("assistant", lastAt, true, now, cooldown))
        assertTrue(ProactivePolicy.quietUntil("assistant", lastAt, true, now, cooldown)>now)
        // The deadline is the cooldown end, not a newly rolled random persona interval (>= 60 minutes).
        assertTrue(ProactivePolicy.quietUntil("assistant", lastAt, true, now, cooldown) < lastAt + hours(1))
    }
}
