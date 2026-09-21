package com.miniichat.tasks.agent

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow

/** System grant and live binding are different states; neither is self-granted. */
object AccessibilityConnection {
    val connected = MutableStateFlow(false)
    fun enabled(context: Context): Boolean {
        val expected = ComponentName(context, PhoneAccessibility::class.java)
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == expected }
    }
    fun description(context: Context): String = when {
        PhoneAccessibility.current != null -> "已连接 · 无需重复开启"
        enabled(context) -> "系统已允许，服务暂未连接；连接恢复后会继续原任务。若长期未恢复，请检查系统后台限制或重新连接服务。"
        else -> "尚未允许页面操作，请在系统无障碍中开启女仆管理器；只需授权一次，不是每项工作都开启。"
    }
}
