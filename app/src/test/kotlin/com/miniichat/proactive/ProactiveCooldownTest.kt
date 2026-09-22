package com.miniichat.proactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveCooldownTest {
    private val now = 1_760_000_000_000L

    private fun minutes(value: Long) = value * 60L * 1000L
    private fun hours(value: Long) = value * 60L * 60L * 1000L

    @Test
    fun emptyChatNeverWaits() {
        assertFalse(ProactivePolicy.shouldWait(null, 0L, false, now))
        assertFalse(ProactivePolicy.shouldWait(null, now - hours(999), true, now))
    }

    @Test
    fun recentUserActivityWaits() {
        assertTrue(ProactivePolicy.shouldWait("user", now, false, now))
        assertTrue(ProactivePolicy.shouldWait("user", now - minutes(5), false, now))
        assertFalse(ProactivePolicy.shouldWait("user", now - hours(2), false, now))
    }

    @Test
    fun unansweredProactiveWaitsOnlyInsideCooldown() {
        assertTrue(ProactivePolicy.shouldWait("assistant", now - hours(23), true, now))
        assertFalse(ProactivePolicy.shouldWait("assistant", now - hours(25), true, now))
    }

    @Test
    fun answeredAssistantMessageIsNotCooldownGated() {
        assertFalse(ProactivePolicy.shouldWait("assistant", now - hours(6), false, now))
        assertFalse(ProactivePolicy.shouldWait("assistant", now - hours(25), false, now))
    }

    @Test
    fun futureTimestampIsClampedToActiveNow() {
        assertTrue(ProactivePolicy.shouldWait("user", now + minutes(1), false, now))
        assertTrue(ProactivePolicy.shouldWait("assistant", now + minutes(1), true, now))
    }

    @Test
    fun exactCooldownBoundariesStopWaiting() {
        assertFalse(ProactivePolicy.shouldWait("user", now - ProactivePolicy.RECENT_USER_ACTIVITY_MILLIS, false, now))
        assertTrue(ProactivePolicy.shouldWait("user", now - ProactivePolicy.RECENT_USER_ACTIVITY_MILLIS + 1, false, now))
        assertFalse(ProactivePolicy.shouldWait("assistant", now - ProactivePolicy.UNANSWERED_COOLDOWN_MILLIS, true, now))
        assertTrue(ProactivePolicy.shouldWait("assistant", now - ProactivePolicy.UNANSWERED_COOLDOWN_MILLIS + 1, true, now))
    }
}
