package com.miniichat.companion

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

private const val MAX_GAP_MS = 10L * 60_000L
private const val HR_EXPIRY_MS = 10L * 60_000L
private const val STOPPED_AFTER_MS = 3L * 60_000L
private const val SEDENTARY_AFTER_MS = 50L * 60_000L
private const val SLEEP_QUIET_MS = 90L * 60_000L
private const val RUN_OBSERVATION_MS = 30_000L
private const val RUN_CADENCE = 140.0
private const val WALK_CADENCE = 40.0
private const val HR_MIN = 30f
private const val HR_MAX = 220f

private const val ACTIVITY_UNKNOWN = "unknown"
private const val ACTIVITY_MOVING = "moving"
private const val ACTIVITY_WALKING = "walking"
private const val ACTIVITY_RUNNING = "running"
private const val ACTIVITY_STILL = "still"

private const val TYPE_STARTED_MOVING = "started_moving"
private const val TYPE_WALKING = "walking"
private const val TYPE_RUNNING = "running"
private const val TYPE_STOPPED_MOVING = "stopped_moving"
private const val TYPE_SEDENTARY = "sedentary"
private const val TYPE_POSSIBLE_SLEEP = "possible_sleep"
private const val TYPE_POSSIBLE_WOKE = "possible_woke"

private const val OBSERVED = "observed"
private const val INFERRED = "inferred"

private fun eventId(type: String, at: Long): String = "$type@$at"

private fun isNightTime(time: Long): Boolean {
    val hour = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).hour
    return hour >= 22 || hour < 7
}

@Serializable
data class Sample(
    val time: Long,
    val day: String = "",
    val steps: Long? = null,
    val heartRate: Float? = null,
    val worn: Boolean? = null,
    val motion: Boolean? = null,
    val screenInteractive: Boolean? = null,
)

@Serializable
data class State(
    val lastTime: Long = 0,
    val day: String = "",
    val counter: Long? = null,
    val dailySteps: Long = 0,
    val lastMovement: Long = 0,
    val activity: String = "unknown",
    val activitySince: Long = 0,
    val worn: Boolean? = null,
    val heartRate: Float? = null,
    val heartRateAt: Long = 0,
    val sedentaryEmitted: Boolean = false,
    val lastCounterAt: Long = 0,
    val quietSince: Long = 0,
    val sleepCandidate: Boolean = false,
)

@Serializable
data class Event(
    val id: String,
    val type: String,
    val at: Long,
    val summary: String,
    val confidence: String = "observed",
)

/**
 * Pure, deterministic reducer over low-power activity samples.
 *
 * It only reports observed facts or events that are explicitly labelled as inferred
 * ("可能在跑步", "可能在睡觉", "可能已醒来"). Steps, motion and screen state alone never
 * invent outings, training sessions or sitting claims.
 */
class ActivityReducer(initial: State = State()) {

    var state: State = initial
        private set

    fun accept(sample: Sample): List<Event> {
        // Out-of-order or repeated timestamps are rejected without mutating state.
        if (state.lastTime != 0L && sample.time <= state.lastTime) return emptyList()

        val first = state.lastTime == 0L
        val gap = if (first) 0L else sample.time - state.lastTime
        val discontinuous = !first && gap > MAX_GAP_MS
        val restarted = first || discontinuous || state.lastMovement == 0L || state.quietSince == 0L

        var counter = state.counter
        var dailySteps = state.dailySteps
        var lastCounterAt = state.lastCounterAt
        var lastMovement = if (restarted) sample.time else state.lastMovement
        var quietSince = if (restarted) sample.time else state.quietSince
        var activity = if (first || discontinuous) ACTIVITY_UNKNOWN else state.activity
        var activitySince = if (first || discontinuous) sample.time else state.activitySince
        var sedentaryEmitted = if (first || discontinuous) false else state.sedentaryEmitted
        var sleepCandidate = if (first || discontinuous) false else state.sleepCandidate
        var worn = state.worn
        var heartRate = state.heartRate
        var heartRateAt = state.heartRateAt

        val events = mutableListOf<Event>()
        val seenIds = mutableSetOf<String>()

        fun emit(type: String, summary: String, confidence: String) {
            val id = eventId(type, sample.time)
            if (seenIds.add(id)) {
                events += Event(id = id, type = type, at = sample.time, summary = summary, confidence = confidence)
            }
        }

        if (sample.worn != null) worn = sample.worn

        // Day rollover: the accumulated day count restarts and a fresh step baseline is
        // established, so the unknown gap between two days is never counted.
        if (sample.day.isNotEmpty() && state.day.isNotEmpty() && sample.day != state.day) {
            dailySteps = 0L
            counter = null
            activity = ACTIVITY_UNKNOWN
            activitySince = sample.time
            lastMovement = sample.time
            quietSince = sample.time
            sedentaryEmitted = false
            sleepCandidate = false
        }

        // Steps are counted only as deltas observed since the first baseline.
        var delta = 0L
        val previousCounterAt = lastCounterAt
        val steps = sample.steps
        if (steps != null) {
            val baseline = counter
            when {
                baseline == null -> {
                    counter = steps
                    lastCounterAt = sample.time
                }
                steps - baseline < 0L -> {
                    // Counter reboot: re-baseline, never go negative.
                    counter = steps
                    lastCounterAt = sample.time
                }
                else -> {
                    delta = steps - baseline
                    counter = steps
                    if (delta > 0L) lastCounterAt = sample.time
                }
            }
        }
        if (delta > 0L) dailySteps += delta

        val cadence = if (delta > 0L && previousCounterAt > 0L && sample.time > previousCounterAt) {
            delta * 60_000.0 / (sample.time - previousCounterAt).toDouble()
        } else {
            null
        }

        val movement = delta > 0L || sample.motion == true
        if (sample.steps == null && sample.motion == null) {
            quietSince = sample.time
            activity = ACTIVITY_UNKNOWN
            sleepCandidate = false
        }

        val measuredHr = sample.heartRate
        if (measuredHr != null && worn != false && measuredHr >= HR_MIN && measuredHr <= HR_MAX) {
            heartRate = measuredHr
            heartRateAt = sample.time
        }
        if (worn == false) {
            // Off-body cancels heart rate, sleep and still estimates.
            heartRate = null
            sleepCandidate = false
            sedentaryEmitted = false
            quietSince = sample.time
            lastMovement = sample.time
            activity = ACTIVITY_UNKNOWN
            activitySince = sample.time
        } else if (heartRate != null && sample.time - heartRateAt > HR_EXPIRY_MS) {
            heartRate = null
        }

        if (!first) {
            if (movement && worn != false) {
                if (activity != ACTIVITY_MOVING && activity != ACTIVITY_WALKING && activity != ACTIVITY_RUNNING) {
                    emit(TYPE_STARTED_MOVING, "开始活动", OBSERVED)
                    activity = ACTIVITY_MOVING
                    activitySince = sample.time
                }
                if (cadence != null) {
                    if (cadence >= RUN_CADENCE && sample.time - activitySince >= RUN_OBSERVATION_MS) {
                        if (activity != ACTIVITY_RUNNING) {
                            emit(TYPE_RUNNING, "可能在跑步", INFERRED)
                            activity = ACTIVITY_RUNNING
                        }
                    } else if (cadence >= WALK_CADENCE && activity == ACTIVITY_MOVING) {
                        emit(TYPE_WALKING, "可能在走路", INFERRED)
                        activity = ACTIVITY_WALKING
                    }
                }
                lastMovement = sample.time
                quietSince = sample.time
                sedentaryEmitted = false
                if (sleepCandidate) {
                    emit(TYPE_POSSIBLE_WOKE, "可能已醒来", INFERRED)
                    sleepCandidate = false
                }
            } else if (worn != false) {
                if (activity != ACTIVITY_STILL && activity != ACTIVITY_UNKNOWN &&
                    sample.time - lastMovement >= STOPPED_AFTER_MS
                ) {
                    emit(TYPE_STOPPED_MOVING, "停止活动", OBSERVED)
                    activity = ACTIVITY_STILL
                    activitySince = sample.time
                }
                val quietFor = sample.time - quietSince
                if (worn == true && !sedentaryEmitted && quietFor >= SEDENTARY_AFTER_MS &&
                    activity != ACTIVITY_MOVING && activity != ACTIVITY_WALKING && activity != ACTIVITY_RUNNING
                ) {
                    emit(TYPE_SEDENTARY, "连续50分钟未记录到明显活动", OBSERVED)
                    sedentaryEmitted = true
                }
                if (worn == true && !sleepCandidate && isNightTime(sample.time) && quietFor >= SLEEP_QUIET_MS) {
                    emit(TYPE_POSSIBLE_SLEEP, "可能在睡觉", INFERRED)
                    sleepCandidate = true
                }
            }
        }

        state = State(
            lastTime = sample.time,
            day = if (sample.day.isNotEmpty()) sample.day else state.day,
            counter = counter,
            dailySteps = dailySteps,
            lastMovement = lastMovement,
            activity = activity,
            activitySince = activitySince,
            worn = worn,
            heartRate = heartRate,
            heartRateAt = heartRateAt,
            sedentaryEmitted = sedentaryEmitted,
            lastCounterAt = lastCounterAt,
            quietSince = quietSince,
            sleepCandidate = sleepCandidate,
        )
        return events
    }
}
