package com.miniichat.companion

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityReducerTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val day: LocalDate = LocalDate.of(2026, 5, 4)
    private val dayKey = "2026-05-04"
    private val noon: Long = at(12, 0)

    private fun at(hour: Int, minute: Int): Long =
        ZonedDateTime.of(day, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()

    private fun types(events: List<Event>): List<String> = events.map { it.type }

    @Test
    fun baselineDeltaAndRebootNeverCountNegative() {
        val r = ActivityReducer()
        r.accept(Sample(time = noon, day = dayKey, steps = 1_000L, worn = true))
        assertEquals(0L, r.state.dailySteps)

        r.accept(Sample(time = noon + 60_000, day = dayKey, steps = 1_500L, worn = true))
        assertEquals(500L, r.state.dailySteps)

        // Counter reboot: decrease resets the baseline without subtracting steps.
        r.accept(Sample(time = noon + 120_000, day = dayKey, steps = 20L, worn = true))
        assertEquals(500L, r.state.dailySteps)
        assertEquals(20L, r.state.counter)

        r.accept(Sample(time = noon + 180_000, day = dayKey, steps = 120L, worn = true))
        assertEquals(600L, r.state.dailySteps)
    }

    @Test
    fun dayRolloverResetsDayCountAndBaseline() {
        val r = ActivityReducer()
        r.accept(Sample(time = noon, day = dayKey, steps = 5_000L, worn = true))
        r.accept(Sample(time = noon + 60_000, day = dayKey, steps = 7_000L, worn = true))
        assertEquals(2_000L, r.state.dailySteps)

        val nextDay = noon + 20 * 60 * 60_000L
        r.accept(Sample(time = nextDay, day = "2026-05-05", steps = 9_000L, worn = true))
        assertEquals(0L, r.state.dailySteps)

        r.accept(Sample(time = nextDay + 60_000, day = "2026-05-05", steps = 9_100L, worn = true))
        assertEquals(100L, r.state.dailySteps)
    }

    @Test
    fun duplicateAndOutOfOrderTimestampsAreRejected() {
        val r = ActivityReducer()
        r.accept(Sample(time = noon, day = dayKey, steps = 0L, worn = true))
        val moving = Sample(time = noon + 60_000, day = dayKey, steps = 60L, worn = true)
        assertFalse(r.accept(moving).isEmpty())

        assertTrue(r.accept(moving).isEmpty())
        assertTrue(r.accept(moving.copy(time = noon + 30_000)).isEmpty())
        assertEquals(noon + 60_000, r.state.lastTime)
        assertEquals(60L, r.state.dailySteps)
    }

    @Test
    fun lostSamplingBreaksContinuityForSedentaryAndSleep() {
        val r = ActivityReducer()
        r.accept(Sample(time = noon, day = dayKey, worn = true, motion = false))
        val gapEvents = r.accept(Sample(time = noon + 15 * 60_000L, day = dayKey, worn = true, motion = false))
        assertTrue(gapEvents.isEmpty())

        var sedentaryAt: Long? = null
        for (k in 1..10) {
            val t = noon + 15 * 60_000L + k * 5 * 60_000L
            val events = r.accept(Sample(time = t, day = dayKey, worn = true, motion = false))
            if (events.any { it.type == "sedentary" }) sedentaryAt = t
        }
        assertEquals(noon + 65 * 60_000L, sedentaryAt ?: 0L)

        // A night-long gap must not invent a sleep interval.
        val night = at(1, 0)
        val n = ActivityReducer()
        n.accept(Sample(time = night, day = dayKey, worn = true))
        val nightGap = n.accept(Sample(time = night + 2 * 60 * 60_000L, day = dayKey, worn = true))
        assertTrue(nightGap.isEmpty())
        assertFalse(n.state.sleepCandidate)
    }

    @Test
    fun nightQuietWithConfirmedWearInfersSleepAndWaking() {
        val night = at(1, 0)
        val r = ActivityReducer()
        r.accept(Sample(time = night, day = dayKey, worn = true, motion = false))

        var sleepAt: Long? = null
        for (k in 1..9) {
            val t = night + k * 10 * 60_000L
            val events = r.accept(Sample(time = t, day = dayKey, worn = true, motion = false))
            if (events.any { it.type == "possible_sleep" }) {
                sleepAt = t
                assertEquals("inferred", events.first { it.type == "possible_sleep" }.confidence)
            }
        }
        assertEquals(night + 90 * 60_000L, sleepAt)
        assertTrue(r.state.sleepCandidate)

        val woke = r.accept(Sample(time = night + 95 * 60_000L, day = dayKey, worn = true, motion = true))
        assertTrue(types(woke).contains("possible_woke"))
        assertFalse(r.state.sleepCandidate)
    }

    @Test
    fun screenOffAloneNeverImpliesSleep() {
        val night = at(1, 0)
        val unknownWear = ActivityReducer()
        unknownWear.accept(Sample(time = night, day = dayKey, screenInteractive = false))
        var sawSleep = false
        for (k in 1..12) {
            val events = unknownWear.accept(
                Sample(time = night + k * 10 * 60_000L, day = dayKey, screenInteractive = false)
            )
            sawSleep = sawSleep || events.any { it.type == "possible_sleep" }
        }
        assertFalse(sawSleep)

        val daytime = ActivityReducer()
        daytime.accept(Sample(time = noon, day = dayKey, worn = true, screenInteractive = false))
        var daySleep = false
        for (k in 1..12) {
            val events = daytime.accept(
                Sample(time = noon + k * 10 * 60_000L, day = dayKey, worn = true, screenInteractive = false)
            )
            daySleep = daySleep || events.any { it.type == "possible_sleep" }
        }
        assertFalse(daySleep)
    }

    @Test
    fun offBodyClearsHeartRateAndCancelsEstimates() {
        val night = at(1, 0)
        val r = ActivityReducer()
        r.accept(Sample(time = night, day = dayKey, worn = true, heartRate = 70f))
        assertEquals(70f, r.state.heartRate ?: 0f, 0.001f)

        val off = r.accept(
            Sample(time = night + 60 * 60_000L, day = dayKey, worn = false, motion = true, heartRate = 70f)
        )
        assertTrue(off.isEmpty())
        assertNull(r.state.heartRate)
        assertFalse(r.state.sleepCandidate)

        var sawSleep = false
        var sawSedentary = false
        for (k in 1..12) {
            val events = r.accept(
                Sample(time = night + 60 * 60_000L + k * 10 * 60_000L, day = dayKey, worn = false, motion = false)
            )
            sawSleep = sawSleep || events.any { it.type == "possible_sleep" }
            sawSedentary = sawSedentary || events.any { it.type == "sedentary" }
        }
        assertFalse(sawSleep)
        assertFalse(sawSedentary)
    }

    @Test
    fun heartRateExpiresAndInvalidValuesAreIgnored() {
        val r = ActivityReducer()
        r.accept(Sample(time = noon, day = dayKey, worn = true, heartRate = 80f))
        assertEquals(80f, r.state.heartRate ?: 0f, 0.001f)
        r.accept(Sample(time = noon + 5 * 60_000L, day = dayKey, worn = true))
        assertEquals(80f, r.state.heartRate ?: 0f, 0.001f)
        r.accept(Sample(time = noon + 15 * 60_000L, day = dayKey, worn = true))
        assertNull(r.state.heartRate)

        val invalid = ActivityReducer()
        invalid.accept(Sample(time = noon, day = dayKey, worn = true, heartRate = 250f))
        assertNull(invalid.state.heartRate)
        invalid.accept(Sample(time = noon + 60_000L, day = dayKey, worn = true, heartRate = 20f))
        assertNull(invalid.state.heartRate)
    }

    @Test
    fun WalkingAndRunningAreExplicitInferencesFromCadence() {
        val r = ActivityReducer()
        r.accept(Sample(time = noon, day = dayKey, steps = 0L, worn = true))

        val walkEvents = r.accept(Sample(time = noon + 10_000, day = dayKey, steps = 20L, worn = true))
        assertEquals(listOf("started_moving", "walking"), types(walkEvents))
        assertEquals("inferred", walkEvents[1].confidence)
        assertEquals("walking", r.state.activity)

        val runEvents = r.accept(Sample(time = noon + 40_000, day = dayKey, steps = 110L, worn = true))
        assertEquals(listOf("running"), types(runEvents))
        assertEquals("inferred", runEvents[0].confidence)
        assertEquals("可能在跑步", runEvents[0].summary)
        assertEquals("running", r.state.activity)
    }

    @Test
    fun stoppedMovingNeedsThreeMinutesAndSedentaryEmitsOnce() {
        val r = ActivityReducer()
        r.accept(Sample(time = noon, day = dayKey, worn = true))
        val started = r.accept(Sample(time = noon + 60_000, day = dayKey, worn = true, motion = true))
        assertTrue(types(started).contains("started_moving"))

        var sedentaryCount = 0
        var stoppedAt = 0L
        for (k in 1..12) {
            val t = noon + 60_000 + k * 5 * 60_000L
            val events = r.accept(Sample(time = t, day = dayKey, worn = true, motion = false))
            if (events.any { it.type == "stopped_moving" } && stoppedAt == 0L) stoppedAt = t
            sedentaryCount += events.count { it.type == "sedentary" }
        }
        assertEquals(noon + 60_000 + 5 * 60_000L, stoppedAt)
        assertEquals(1, sedentaryCount)
        assertEquals("still", r.state.activity)

        // Movement resets the still estimate; a new continuous quiet period can re-emit.
        r.accept(Sample(time = noon + 60_000 + 61 * 60_000L, day = dayKey, worn = true, motion = true))
        var second = 0
        for (k in 1..12) {
            val t = noon + 60_000 + 61 * 60_000L + k * 5 * 60_000L
            second += r.accept(Sample(time = t, day = dayKey, worn = true, motion = false))
                .count { it.type == "sedentary" }
        }
        assertEquals(1, second)
        assertTrue(r.state.sedentaryEmitted)
    }

    @Test
    fun firstSampleNeverFabricatesPastEvents() {
        val r = ActivityReducer()
        val events = r.accept(
            Sample(time = noon, day = dayKey, steps = 50_000L, worn = true, motion = true, heartRate = 70f)
        )
        assertTrue(events.isEmpty())
        assertEquals(0L, r.state.dailySteps)
        assertEquals(noon, r.state.quietSince)
        assertEquals("unknown", r.state.activity)
    }

    @Test
    fun stateRoundTripsThroughJson() {
        val r = ActivityReducer()
        r.accept(Sample(time = noon, day = dayKey, steps = 1_000L, worn = true, heartRate = 66f))
        r.accept(Sample(time = noon + 60_000, day = dayKey, steps = 1_120L, worn = true, heartRate = 70f))

        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(r.state)
        assertEquals(r.state, json.decodeFromString<State>(encoded))

        val jsonPlain = Json
        assertEquals(State(), jsonPlain.decodeFromString<State>("{}"))
        assertEquals(State(), jsonPlain.decodeFromString<State>(jsonPlain.encodeToString(State())))

        // A restored reducer keeps rejecting the last persisted sample (no duplicates).
        val restored = ActivityReducer(json.decodeFromString<State>(encoded))
        assertTrue(restored.accept(Sample(time = noon + 60_000, day = dayKey, steps = 1_120L, worn = true)).isEmpty())
        // Continued walking updates the count without emitting a second started_moving event.
        assertTrue(restored.accept(Sample(time = noon + 120_000, day = dayKey, steps = 1_180L, worn = true)).isEmpty())
        assertEquals(180L, restored.state.dailySteps)
    }

    @Test fun wearAloneCannotClaimInactivityWhenMotionSensorIsUnavailable() {
        val r=ActivityReducer()
        for(k in 0..120) assertTrue(r.accept(Sample(time=at(1,0)+k*60000L,day=dayKey,worn=true)).isEmpty())
        assertFalse(r.state.sleepCandidate)
    }
}
