package com.miniichat.proactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ProactivePolicyTest {
    @Test
    fun frequencyRangesAreDistinctAndRandomized() {
        val frequent = ProactivePolicy.nextDelayMillis("frequent", 0.5)
        val occasional = ProactivePolicy.nextDelayMillis("occasional", 0.5)
        val rare = ProactivePolicy.nextDelayMillis("rare", 0.5)
        assertTrue(frequent < occasional)
        assertTrue(occasional < rare)
        assertTrue(ProactivePolicy.nextDelayMillis("frequent", 0.1) != frequent)
    }

    @Test
    fun personaTendencyAndFailuresAdjustFutureDelay() {
        val proactive = ProactivePolicy.nextDelayMillis("occasional", 0.5, contactTendency = 1.0)
        val reserved = ProactivePolicy.nextDelayMillis("occasional", 0.5, contactTendency = 0.0)
        val failed = ProactivePolicy.nextDelayMillis("occasional", 0.5, failureCount = 3)
        assertTrue(proactive < reserved)
        assertTrue(failed > reserved)
    }

    @Test
    fun overnightDoNotDisturbIsHandled() {
        val zone = TimeZone.getTimeZone("UTC")
        fun at(hour: Int): Long = Calendar.getInstance(zone).apply {
            set(2026, Calendar.JANUARY, 2, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertTrue(ProactivePolicy.isInDoNotDisturb(at(23), 22 * 60, 8 * 60, zone))
        assertTrue(ProactivePolicy.isInDoNotDisturb(at(7), 22 * 60, 8 * 60, zone))
        assertFalse(ProactivePolicy.isInDoNotDisturb(at(12), 22 * 60, 8 * 60, zone))
    }
}
