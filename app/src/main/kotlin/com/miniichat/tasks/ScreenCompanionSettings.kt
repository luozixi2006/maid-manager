package com.miniichat.tasks

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.miniichat.tasks.agent.AgentPolicy
import com.miniichat.tasks.agent.NativePhoneTools
import com.miniichat.tasks.agent.PhoneAccessibility
import com.miniichat.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ScreenCompanionSettings(onError: (String) -> Unit) {
    val context = LocalContext.current
    val enabled by ScreenCompanion.enabled.collectAsState()
    val sharing by ScreenShare.running.collectAsState()
    val shareStatus by ScreenShare.status.collectAsState()
    val textStatus by ScreenCompanion.status.collectAsState()
    var rememberText by remember { mutableStateOf(TaskActions.preferences(context).getBoolean(ScreenCompanion.REMEMBER, false)) }
    var consent by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(emptyMap<String, String>()) }
    var allowed by remember { mutableStateOf(TaskActions.preferences(context).getStringSet(ScreenCompanion.APPS, emptySet()).orEmpty().toSet()) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { NativePhoneTools.apps(context).filterKeys(AgentPolicy::validPackage) } }
    AppGroup {
        PreferenceRow("画面陪伴", shareStatus) {
            TextButton({ if (sharing) ScreenShare.stop(context) else if (CompanionRuntime.running.value) ScreenShare.start(context) else onError("请先开启悬浮头像") }) {
                Text(if (sharing) "停止共享" else "共享画面")
            }
        }
        Text("能看图片和页面布局，不需要无障碍。请选支持图片的模型；共享范围由系统确认。", style = MaterialTheme.typography.bodySmall)
        PreferenceRow("文字感知", if (enabled) textStatus else "可选 · 只读取已选应用的文字") {
            AppSwitch(enabled, {
                if (!it) { ScreenCompanion.enabled.value = false; rememberText = false; TaskActions.preferences(context).edit().putBoolean(ScreenCompanion.REMEMBER, false).apply() }
                else when {
                    !CompanionRuntime.running.value -> onError("请先开启悬浮头像")
                    PhoneAccessibility.current == null && !com.miniichat.tasks.agent.AccessibilityConnection.enabled(context) -> onError(com.miniichat.tasks.agent.AccessibilityConnection.description(context))
                    allowed.isEmpty() -> onError("请先选择允许观察的应用")
                    else -> consent = true
                }
            })
        }
        if (enabled) PreferenceRow("下次开启悬浮陪伴时恢复文字感知", "只恢复已选应用的文字授权，不自动共享画面") {
            AppSwitch(rememberText, { rememberText = it; TaskActions.preferences(context).edit().putBoolean(ScreenCompanion.REMEMBER, it).apply() })
        }
        Disclosure("页面读取范围 · ${allowed.size} 个应用") {
            Text("以下范围只用于文字感知，与画面共享的系统选区、工作操作授权分开。查看或人设问候检查时读取；密码、验证码和锁屏不读取。图片内容请用上方画面共享。", style = MaterialTheme.typography.bodySmall)
            TextButton({ context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("系统页面读取权限") }
            TextButton({ allowed = emptySet(); ScreenCompanion.enabled.value = false; rememberText = false; TaskActions.preferences(context).edit().remove(ScreenCompanion.APPS).putBoolean(ScreenCompanion.REMEMBER, false).apply() }) { Text("撤销全部观察应用") }
            apps.toList().sortedBy { it.second }.forEach { (pkg, label) -> PreferenceRow(label) {
                Checkbox(pkg in allowed, { yes ->
                    allowed = if (yes) allowed + pkg else allowed - pkg
                    TaskActions.preferences(context).edit().putStringSet(ScreenCompanion.APPS, allowed).apply()
                    if (allowed.isEmpty()) ScreenCompanion.enabled.value = false
                })
            } }
        }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("允许这次页面陪伴？") },
        text = { Text("允许将所选应用当前可见文字发送给当前人设使用的模型，用于查看或主动问候检查。不是录屏，可随时暂停。默认只在本次悬浮会话生效；开启后可另行选择是否记住文字授权。") },
        confirmButton = { TextButton({ ScreenCompanion.enabled.value = true; consent = false }) { Text("允许本次会话") } },
        dismissButton = { TextButton({ consent = false }) { Text("取消") } })
}
