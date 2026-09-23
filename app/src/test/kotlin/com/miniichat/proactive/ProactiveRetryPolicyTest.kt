package com.miniichat.proactive

import com.miniichat.data.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveRetryPolicyTest {
    private val now = 1_760_000_000_000L

    private fun minutes(value: Long) = value * 60L * 1000L

    private fun assistant(
        timing: String,
        minMinutes: Int = 60,
        maxMinutes: Int = 240
    ) = Assistant(
        id = "a",
        name = "A",
        proactiveTiming = timing,
        proactiveMinMinutes = minMinutes,
        proactiveMaxMinutes = maxMinutes
    )

    @Test
    fun retryDelayRampStaysInMinutesEvenAfterManyFailures() {
        assertEquals(minutes(2), ProactivePolicy.retryDelayMillis(1))
        assertEquals(minutes(5), ProactivePolicy.retryDelayMillis(2))
        assertEquals(minutes(15), ProactivePolicy.retryDelayMillis(3))
        assertEquals(minutes(30), ProactivePolicy.retryDelayMillis(4))
        assertEquals(minutes(2), ProactivePolicy.retryDelayMillis(0))
        for (failures in listOf(5, 12, 100)) {
            assertEquals(minutes(30), ProactivePolicy.retryDelayMillis(failures))
        }
        for (failures in 0..200) {
            assertTrue(ProactivePolicy.retryDelayMillis(failures) <= minutes(30))
        }
    }

    @Test
    fun personaModeUsesSixtyMinuteUnansweredCooldown() {
        assertEquals(minutes(60), ProactivePolicy.unansweredCooldown(assistant("persona", 5, 10)))
        assertEquals(
            ProactivePolicy.UNANSWERED_COOLDOWN_MILLIS,
            ProactivePolicy.unansweredCooldown(assistant("persona", 200, 400))
        )
    }

    @Test
    fun explicitIntervalUsesMinMinutesAsUnansweredCooldown() {
        assertEquals(minutes(15), ProactivePolicy.unansweredCooldown(assistant("fixed", 15)))
        assertEquals(minutes(30), ProactivePolicy.unansweredCooldown(assistant("fixed", 30)))
        assertEquals(minutes(30), ProactivePolicy.unansweredCooldown(assistant("random", 30, 120)))
    }

    @Test
    fun explicitIntervalCooldownIsClampedToFifteenTo1440Minutes() {
        assertEquals(minutes(15), ProactivePolicy.unansweredCooldown(assistant("fixed", 5)))
        assertEquals(minutes(1440), ProactivePolicy.unansweredCooldown(assistant("fixed", 10_000)))
        assertEquals(minutes(15), ProactivePolicy.unansweredCooldown(assistant("random", -20, 30)))
    }

    @Test
    fun gateUsesEarliestEligibleDeadlineForExplicitThirtyMinuteInterval() {
        val cooldown = ProactivePolicy.unansweredCooldown(assistant("fixed", 30))
        val lastAt = now - minutes(29)
        val deadline = ProactivePolicy.quietUntil("assistant", lastAt, true, now, cooldown)
        assertEquals(lastAt + cooldown, deadline)
        assertTrue(ProactivePolicy.quietUntil("assistant", lastAt, true, now, cooldown)>now)
        assertEquals(now,ProactivePolicy.quietUntil("assistant", now - minutes(30), true, now, cooldown))
        // A freshly rolled persona interval (>= 60 minutes) would have locked considerably longer.
        assertTrue(deadline < now + minutes(60))
    }
}
