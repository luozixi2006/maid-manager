package com.miniichat.tasks

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.miniichat.data.AssistantStore
import com.miniichat.data.SettingsRepository
import com.miniichat.ui.*
import kotlinx.coroutines.flow.first

@Composable
fun CompanionSettings(refresh: Int, openPermission: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val running by CompanionRuntime.running.collectAsState()
    var avatar by remember(refresh) { mutableStateOf(CompanionAppearance.avatar(context)) }
    var defaultAvatar by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("陪伴") }
    var popups by remember { mutableStateOf(CompanionAppearance.popups(context)) }
    LaunchedEffect(refresh) {
        val settings = SettingsRepository(context).settings.first()
        AssistantStore(context).snapshot().firstOrNull { it.id == settings.activeAssistantId }?.let {
            defaultAvatar = it.avatarPath.orEmpty(); name = it.displayName
        }
    }
    val photos = rememberPhotoActions("companion-avatar", 1, {
        it.firstOrNull()?.let { path -> CompanionAppearance.setAvatar(context, path); avatar = path }
    }, onError)
    SectionHeading("屏幕陪伴")
    AppGroup {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(name, avatar.ifBlank { defaultAvatar }, 64.dp)
            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge)
                Text(if (avatar.isBlank()) "跟随当前对话头像" else "独立陪伴头像", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(photos.choose, enabled = !photos.busy, contentPadding = PaddingValues(end = 14.dp)) { Text(if (photos.busy) "保存中…" else "更换头像") }
                    if (avatar.isNotBlank()) TextButton({ CompanionAppearance.setAvatar(context, ""); avatar = "" }, contentPadding = PaddingValues(0.dp)) { Text("还原") }
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        PreferenceRow("显示悬浮头像", if (running) "已开启" else "已关闭") {
            AppSwitch(running, { enabled ->
                if (!enabled) context.stopService(Intent(context, PetOverlayService::class.java))
                else if (!Settings.canDrawOverlays(context)) openPermission()
                else runCatching { ContextCompat.startForegroundService(context, Intent(context, PetOverlayService::class.java)) }
                    .onFailure { onError("请保持应用在前台后重试") }
            })
        }
        PreferenceRow("主动展开提醒", "关闭后仅显示状态与系统通知") {
            AppSwitch(popups, { popups = it; TaskActions.preferences(context).edit().putBoolean(CompanionAppearance.POPUPS, it).apply() })
        }
    }
    Text("拖动靠边，点按聊天。展开后可直接收起或关闭。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Disclosure("关闭与隐私说明") {
        Text("关闭头像不会取消任务，也不会自动重新打开。系统任务通知和已启用的事件规则继续有效，可到“自动”页单独关闭。快速聊天关闭后不保留；头像保存在本机，不因任务切换而覆盖。", style = MaterialTheme.typography.bodySmall)
    }
}
