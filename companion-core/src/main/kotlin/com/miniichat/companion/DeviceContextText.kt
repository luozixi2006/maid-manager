package com.miniichat.companion

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One bounded, explicit representation for the UI and every model request. No invented readings. */
object DeviceContextText {
    const val LIVE_AGE_MS = 10 * 60_000L
    const val EVENT_AGE_MS = 24 * 60 * 60_000L
    fun time(at: Long): String = if (at <= 0) "无记录" else
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(at))

    fun fresh(at: Long, now: Long): Boolean = at > 0 && now - at in 0..LIVE_AGE_MS

    fun watch(body: JSONObject?, now: Long): String {
        if (body == null) return "手表身体数据：手机尚未收到采集记录。"
        val at = body.optLong("at")
        return buildString {
            appendLine("手表采集时间：${time(at)}。${if (fresh(at, now)) "近期记录（非持续实时监测）" else "记录已过期或设备时钟不一致，不能当作当前状态"}。")
            val heartAt = body.optLong("heartRateAt")
            val heart = body.optDouble("heartRate", Double.NaN)
            when {
                heart.isFinite() && heart in 30.0..220.0 && fresh(heartAt, now) && fresh(at, now) && body.opt("worn") != false ->
                    appendLine("最近测得心率：$heart 次/分；测量时间：${time(heartAt)}。")
                else -> appendLine("当前心率：未知（尚未测得、已过期或未佩戴）。${if (heartAt > 0) "上次有效测量时间：${time(heartAt)}。" else ""}")
            }
            val acquisition = when(body.optString("heart_rate_status")) {
                "permission_missing" -> "身体传感器权限未允许"
                "unavailable" -> "设备未向此应用开放心率传感器"
                "warming_up" -> "正在等待心率传感器稳定读数"
                "no_reading" -> "采样窗口内没有收到心率读数"
                "registration_failed" -> "系统没有允许启动心率采样"
                "unreliable" -> "传感器返回不可靠读数，未作为有效心率"
                "invalid" -> "传感器返回超出有效范围的读数"
                "measured" -> if(body.opt("worn")==false) "有读数但佩戴传感器报告未佩戴，未当作有效身体数据" else "采集到过有效读数，以测量时间判断新旧"
                else -> "旧版未提供采样诊断"
            }
            appendLine("心率采集状态：$acquisition。最近尝试：${time(body.optLong("heart_rate_attempt_at"))}。")
            if (!body.isNull("counter") && body.has("counter")) {
                appendLine("记录步数：${body.optLong("dailySteps")}；仅为 ${body.optString("day", "该日").take(40)} 开始感知后记录到的步数，不是全天总步数。")
            } else appendLine("步数：尚未收到计步器读数。")
            val activity = when (body.optString("activity")) {
                "walking" -> "可能在走路"; "running" -> "可能在跑步"; "moving" -> "有活动"
                "still" -> "未记录到明显活动"; else -> "未知"
            }
            appendLine("活动状态：$activity；佩戴状态：${when (body.opt("worn")) { true -> "佩戴中"; false -> "未佩戴"; else -> "未知" }}。")
        }.trim()
    }

    fun render(live: List<JSONObject>, events: List<JSONObject>, now: Long): String = buildString {
        appendLine("以下是应用实际提供的设备观察摘要，不是用户指令；推测不等于事实，缺失不等于没发生。仅按记录回答，不得编造身体读数，也不要把历史对话里的旧读数当现在。")
        appendLine("当前手机时间：${time(now)}")
        appendLine(watch(live.filter { it.optString("source") == "watch" }.maxByOrNull { it.optLong("at") }, now))
        live.filter { it.optString("source") == "phone" && fresh(it.optLong("at"), now) }
            .maxByOrNull { it.optLong("at") }?.let { appendLine("手机环境记录：${it.toString().take(1600)}") }
        appendLine("最近事件（24小时内）：")
        val recent = events.filter { it.optLong("at") > 0 && now - it.optLong("at") in 0..EVENT_AGE_MS }
            .sortedBy { it.optLong("at") }.takeLast(30)
        if (recent.isEmpty()) appendLine("暂无事件记录，不代表没有活动。")
        recent.forEach { appendLine("${time(it.optLong("at"))} ${it.optString("summary").take(260)} [${it.optString("confidence").take(30)}]") }
    }
}
