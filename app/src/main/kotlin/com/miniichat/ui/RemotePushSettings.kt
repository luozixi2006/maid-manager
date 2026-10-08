package com.miniichat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.miniichat.proactive.ProactiveNotifications
import com.miniichat.proactive.remote.RemotePushConfig
import com.miniichat.proactive.remote.RemotePushRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/** Lets the user point proactive delivery at their own computer over Tailscale. */
@Composable
fun RemotePushSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var base by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var savedEnabled by remember { mutableStateOf(false) }
    var savedPaired by remember { mutableStateOf(false) }
    var pausePending by remember { mutableStateOf(false) }
    var savedStatus by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { allowed -> message = if (allowed) "通知权限已开启" else "通知权限未开启，锁屏提醒无法显示；可在系统通知设置中开启" }

    fun readConfig() {
        val config = RemotePushConfig(context.applicationContext)
        base = config.base
        savedEnabled = config.enabled
        savedPaired = config.paired
        pausePending = config.pausePending
        savedStatus = config.status
        consent = config.enabled && config.paired
    }

    fun operate(action: suspend () -> Unit, done: String) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                action()
                message = done
            } catch (timeout: TimeoutCancellationException) {
                message = "Google 推送注册或网络超时，请稍后重试"
            } catch (timeout: TimeoutException) {
                message = "Google 推送注册或网络超时，请稍后重试"
            } catch (timeout: SocketTimeoutException) {
                message = "Google 推送注册或网络超时，请稍后重试"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                message = "电脑地址或配对码不正确，请检查后重试"
            } catch (_: Exception) {
                message = "连接未成功，请检查 Tailscale、Google Play 服务或电脑端服务"
            } finally {
                readConfig()
                busy = false
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(revision) { readConfig() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("电脑推送", style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                savedEnabled -> "已开启：角色主动消息由你的电脑生成并推送"
                pausePending -> "手机已暂停；正在等待连上电脑，以移除人设和模型配置"
                savedPaired -> "已暂停：电脑端已移除人设与模型配置"
                else -> "未开启：当前由手机本地生成主动消息"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "开启后，已启用主动联系的人设、近期聊天和所选模型的 API Key 会经 Tailscale 保存在你自己的电脑上，" +
                "仅用于生成主动消息；不会上传身体或屏幕数据。正文经加密传送，系统兜底通知只显示通用提醒。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppTextField(
            value = base,
            onValueChange = { base = it.trim() },
            label = { Text("电脑推送地址（Tailscale IPv4 或 HTTPS 域名）") },
            placeholder = { Text("http://电脑地址:端口") },
            singleLine = true,
            enabled = !busy
        )
        AppTextField(
            value = code,
            onValueChange = { value -> code = value.filter { it.isDigit() }.take(8) },
            label = { Text("8 位配对码（已配对可留空）") },
            singleLine = true,
            enabled = !busy
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = !busy)
            Text("我已阅读并同意以上说明", style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && consent,
            onClick = {
                if (!consent || busy) return@Button
                operate({ RemotePushRuntime.connect(context.applicationContext, base.trim(), code.trim()); code = "" },
                    done = "电脑推送已连接")
            }
        ) { Text(if (savedEnabled) "重新连接电脑" else "连接电脑") }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = !busy && savedPaired,
                onClick = { operate({ RemotePushRuntime.sync(context.applicationContext) }, done = "已刷新电脑推送状态") }
            ) { Text("刷新状态") }
            if (savedPaired) OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onClick = { operate({ RemotePushRuntime.disable(context.applicationContext) }, done = "电脑推送已暂停") }
            ) { Text("暂停电脑推送") }
        }
        TextButton(onClick = {
            if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else runCatching { context.startActivity(ProactiveNotifications.settingsIntent(context)) }
                    .onFailure { message = "请在系统应用设置中开启通知" }
        }) { Text("系统通知设置") }
        ProactiveNotifications.blockedReason(context)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        savedStatus.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        TextButton(onClick = {
            val report = com.miniichat.proactive.remote.PushTrace.report(context)
            runCatching { context.startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, report), "导出推送诊断")) }
                .onFailure { message = "没有可接收诊断报告的应用" }
        }) { Text("导出推送诊断（不含消息内容或密钥）") }
    }
}
