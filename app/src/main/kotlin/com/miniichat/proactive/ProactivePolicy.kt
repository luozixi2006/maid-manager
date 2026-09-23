package com.miniichat.proactive

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToLong

object ProactivePolicy {
    const val GLOBAL_THROTTLE_MILLIS = 15L * 60L * 1000L
    const val RECENT_USER_ACTIVITY_MILLIS = 3L * 60L * 1000L
    const val UNANSWERED_COOLDOWN_MILLIS = 60L * 60L * 1000L
    fun quietUntil(lastRole:String?,lastAt:Long,lastProactive:Boolean,now:Long,
        unansweredCooldown:Long=UNANSWERED_COOLDOWN_MILLIS):Long {
        if(lastRole==null)return now
        // Corrupt / far-future imported timestamps must not renew the quiet window forever.
        if(lastAt>now+5*60_000L)return now
        val window=if(lastRole=="assistant" && lastProactive) unansweredCooldown.coerceAtLeast(RECENT_USER_ACTIVITY_MILLIS) else RECENT_USER_ACTIVITY_MILLIS
        return (lastAt.coerceAtMost(now)+window).coerceAtLeast(now)
    }
    fun unansweredCooldown(assistant:com.miniichat.data.Assistant):Long =
        if(assistant.proactiveTiming=="persona") UNANSWERED_COOLDOWN_MILLIS else assistant.proactiveMinMinutes.coerceIn(15,1440)*60_000L
    fun retryDelayMillis(failureCount:Int):Long = listOf(2L,5L,15L,30L)[(failureCount-1).coerceIn(0,3)]*60_000L
    fun shouldWait(lastRole:String?,lastAt:Long,lastProactive:Boolean,now:Long):Boolean {
        return quietUntil(lastRole,lastAt,lastProactive,now)>now
    }
    fun initialDelayMillis(randomUnit: Double) = ((5 + 10 * randomUnit.coerceIn(0.0, 1.0)) * 60_000).roundToLong()
    fun personaDelay(assistant: com.miniichat.data.Assistant, random: Double, tendency: Double? = null, failure: Int = 0): Long {
        if (assistant.proactiveTiming == "persona") return nextDelayMillis("persona", random, tendency, failure)
        val minimum = assistant.proactiveMinMinutes.coerceIn(15, 1440)
        val maximum = if (assistant.proactiveTiming == "fixed") minimum else assistant.proactiveMaxMinutes.coerceIn(minimum, 1440)
        return ((minimum + (maximum - minimum) * random.coerceIn(0.0, 1.0)) * 60_000L * (1 + failure.coerceIn(0, 4))).roundToLong()
    }

    fun nextDelayMillis(
        frequency: String,
        randomUnit: Double,
        contactTendency: Double? = null,
        failureCount: Int = 0
    ): Long {
        val (minimumHours, maximumHours) = when (frequency) {
            "persona" -> 1.0 to 4.0
            "rare" -> 48.0 to 120.0
            "frequent" -> 6.0 to 18.0
            else -> 18.0 to 48.0
        }
        val randomHours = minimumHours +
            (maximumHours - minimumHours) * randomUnit.coerceIn(0.0, 1.0)
        // The model's persona/relationship judgment changes the next range without converting
        // personality keywords into a fixed percentage.
        val tendencyFactor = contactTendency?.coerceIn(0.0, 1.0)?.let { 1.45 - (it * 0.75) } ?: 1.0
        val failureFactor = 1.0 + failureCount.coerceIn(0, 4) * 0.35
        return (randomHours * tendencyFactor * failureFactor * 60.0 * 60.0 * 1000.0)
            .roundToLong()
            .coerceAtLeast(60L * 60L * 1000L)
    }

    fun isInDoNotDisturb(
        nowMillis: Long,
        startMinutes: Int,
        endMinutes: Int,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Boolean {
        val start = startMinutes.coerceIn(0, 1439)
        val end = endMinutes.coerceIn(0, 1439)
        if (start == end) return false
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
        val current = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return if (start < end) current in start until end else current >= start || current < end
    }

    fun endOfDoNotDisturbMillis(
        nowMillis: Long,
        endMinutes: Int,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        val end = endMinutes.coerceIn(0, 1439)
        val target = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, end / 60)
            set(Calendar.MINUTE, end % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMillis) add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis
    }
}
