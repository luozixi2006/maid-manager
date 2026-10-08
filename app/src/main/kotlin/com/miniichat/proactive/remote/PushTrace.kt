package com.miniichat.proactive.remote

import android.content.Context
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.miniichat.BuildConfig
import com.miniichat.proactive.ProactiveNotifications
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Local-only bounded metadata. Never accept text, tokens, URLs, keys or exception messages. */
object PushTrace {
    fun id(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun notificationTag(messageId: String) = "maid-remote:${id(messageId)}"

    @Synchronized fun record(context: Context, stage: String, messageId: String = "", detail: String = "") {
        if (!stage.matches(Regex("[a-z_]{1,40}")) || !detail.matches(Regex("[a-zA-Z0-9_=,: -]{0,100}"))) return
        try {
            val prefs = context.getSharedPreferences("push_diagnostics", Context.MODE_PRIVATE)
            val old = JSONArray(prefs.getString("events", "[]"))
            val rows = JSONArray()
            for (i in maxOf(0, old.length() - 119) until old.length()) rows.put(old.getJSONObject(i))
            val row = JSONObject().put("time", System.currentTimeMillis()).put("stage", stage)
                .put("id", if (messageId.isBlank()) "" else id(messageId).take(12)).put("detail", detail)
            rows.put(row)
            prefs.edit().putString("events", rows.toString()).commit()
            Log.i("MaidPush", row.toString())
        } catch (_: Exception) { Log.w("MaidPush", "diagnostic_store_unavailable") }
    }

    fun report(context: Context): String {
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel("character_messages")
        val config = RemotePushConfig(context)
        val rows = runCatching { JSONArray(context.getSharedPreferences("push_diagnostics", Context.MODE_PRIVATE).getString("events", "[]")) }
            .getOrDefault(JSONArray())
        return buildString {
            appendLine("女仆管理器 ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.SDK_INT}")
            appendLine("电脑推送=${config.enabled}；Google Play 状态=${GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context)}")
            appendLine("通知=${ProactiveNotifications.blockedReason(context) ?: "允许"}；渠道级别=${channel?.importance}；振动=${channel?.shouldVibrate()}")
            if (Build.VERSION.SDK_INT >= 29) appendLine("系统通知代理=${context.getSystemService(NotificationManager::class.java).notificationDelegate ?: "未设置"}")
            appendLine("FCM 后台系统通知显示时可能没有客户端回调；点开或补收后才有保存确认。")
            for (i in 0 until rows.length()) appendLine(rows.getJSONObject(i).toString())
        }
    }
}
