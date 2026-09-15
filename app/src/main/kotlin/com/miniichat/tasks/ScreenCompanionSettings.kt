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
    var consent by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(emptyMap<String, String>()) }
    var allowed by remember { mutableStateOf(TaskActions.preferences(context).getStringSet(ScreenCompanion.APPS, emptySet()).orEmpty().toSet()) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { NativePhoneTools.apps(context).filterKeys(AgentPolicy::validPackage) } }
    AppGroup {
        PreferenceRow("结合当前页面陪伴", if (enabled) "本次悬浮窗会话已开启" else "默认关闭 · 只读，不替你操作") {
            AppSwitch(enabled, {
                if (!it) ScreenCompanion.enabled.value = false
                else when {
                    !CompanionRuntime.running.value -> onError("请先开启悬浮头像")
                    PhoneAccessibility.current == null -> onError("请先开启下方页面读取权限，再回来开启")
                    allowed.isEmpty() -> onError("请先选择允许观察的应用")
                    else -> consent = true
                }
            })
        }
        Disclosure("页面读取范围 · ${allowed.size} 个应用") {
            Text("和工作操作授权分开。只在主动问候检查或点击“看看当前页面”时，读取已选应用的可见文字并发送给当前人设模型。不截屏、不录屏、不保存页面原文；密码框、验证码页面和锁屏不读取。图片/视频内容无法仅靠文字识别。", style = MaterialTheme.typography.bodySmall)
            TextButton({ context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("系统页面读取权限") }
            TextButton({ allowed = emptySet(); ScreenCompanion.enabled.value = false; TaskActions.preferences(context).edit().remove(ScreenCompanion.APPS).apply() }) { Text("撤销全部观察应用") }
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
        text = { Text("允许将所选应用当前可见文字发送给当前人设使用的模型，帮助她结合你正在看的内容问候。不是持续录屏。悬浮窗显示开启状态，可随时暂停；关闭悬浮窗或重启后不会自动恢复。") },
        confirmButton = { TextButton({ ScreenCompanion.enabled.value = true; consent = false }) { Text("允许本次会话") } },
        dismissButton = { TextButton({ consent = false }) { Text("取消") } })
}
