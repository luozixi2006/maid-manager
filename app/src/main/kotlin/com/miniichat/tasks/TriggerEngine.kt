package com.miniichat.tasks

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.*
import com.miniichat.api.ChatMessage
import com.miniichat.api.LlmClient
import com.miniichat.data.ProviderStore
import com.miniichat.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
enum class TriggerKind(val label: String) {
    FILES("文件夹出现新文件"), NOTIFICATION("选定应用发来通知"), COMPLETED("后台任务完成"),
    WIFI("连接 Wi-Fi"), CHARGING("接上电源"), TIME("每天某个时间"), APP("打开选定应用")
}

@Serializable
data class TriggerRule(val id: String = UUID.randomUUID().toString(), val kind: TriggerKind,
    val match: String = "", val goal: String, val scope: String = "", val enabled: Boolean = true,
    val signature: String = "", val lastFired: Long = 0, val status: String = "等待首次检测",
    val rootDirectory: String = "Download", val fileWatchVersion: Int = 0)

object TriggerPolicy {
    fun changed(kind: TriggerKind, previous: String, next: String): Boolean {
        if (!previous.startsWith("ready:") || previous.removePrefix("ready:") == next) return false
        return when (kind) {
            TriggerKind.FILES -> next.lineSequence().filter { it.isNotBlank() }.any { it !in previous.removePrefix("ready:").lines() }
            TriggerKind.TIME -> next != "before"
            else -> next == "on"
        }
    }
    fun cooledDown(last: Long, now: Long) = now - last >= TimeUnit.HOURS.toMillis(1)
}

object TriggerEngine {
    fun schedule(context: Context) {
        if (TaskStore.of(context).rules().none { it.enabled }) {
            WorkManager.getInstance(context).cancelUniqueWork("phone-events"); return
        }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("phone-events", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<EventPollWorker>(15, TimeUnit.MINUTES).build())
    }
    @Synchronized fun poll(context: Context) {
        val store = TaskStore.of(context)
        store.rules().filter { it.enabled }.forEach { rule ->
            try {
                val signature = when (rule.kind) {
                    TriggerKind.FILES -> {
                        check(DownloadsTools(context, "").permitted()) { "需要文件权限；开启后会恢复检测" }
                        val root = FolderSelection.safeDirectory(FolderSelection.storage(), rule.rootDirectory + if (rule.scope.isBlank()) "" else "/${rule.scope}")
                        fileSignature(root)
                    }
                    TriggerKind.CHARGING -> {
                        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        if ((battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0) "on" else "off"
                    }
                    TriggerKind.WIFI -> {
                        val cm = context.getSystemService(ConnectivityManager::class.java)
                        if (cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) "off"
                        else if (rule.match.isBlank()) "on" else {
                            @Suppress("DEPRECATION") val ssid = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo.ssid.trim('"')
                            check(ssid != "<unknown ssid>" && ssid.isNotBlank()) { "匹配 Wi-Fi 名称需要精确位置和后台位置权限，并开启系统定位；可留空匹配任意 Wi-Fi" }
                            if (ssid == rule.match) "on" else "off"
                        }
                    }
                    TriggerKind.TIME -> {
                        val time = java.time.LocalTime.parse(rule.match)
                        val now = java.time.LocalDateTime.now()
                        if (!now.toLocalTime().isBefore(time)) now.toLocalDate().toString() else "before"
                    }
                    TriggerKind.APP -> {
                        val ops = context.getSystemService(AppOpsManager::class.java)
                        @Suppress("DEPRECATION") val allowed = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
                        check(allowed) { "需要使用情况访问权限；开启后继续检测" }
                        val now = System.currentTimeMillis()
                        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(now - 60_000, now)
                        var latest = ""
                        val event = UsageEvents.Event()
                        while (events.hasNextEvent()) { events.getNextEvent(event)
                            @Suppress("DEPRECATION") if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) latest = event.packageName
                        }
                        if (latest == rule.match) "on" else if (latest.isNotBlank()) "off" else rule.signature.removePrefix("ready:")
                    }
                    else -> return@forEach
                }
                val fire = (rule.kind != TriggerKind.FILES || rule.fileWatchVersion >= 1) && TriggerPolicy.changed(rule.kind, rule.signature, signature)
                store.rule(rule.copy(signature = "ready:$signature", status = "检测中", fileWatchVersion = 1))
                if (fire) event(context, rule.id, rule.kind.label)
            } catch (e: Exception) {
                store.rule(rule.copy(status = if (e is IllegalStateException) e.message.orEmpty().take(200) else "暂时无法检测，请检查该触发器的系统权限"))
            }
        }
    }
    internal fun fileSignature(root: java.io.File): String {
        val files = root.walkTopDown().maxDepth(4).onEnter { !it.name.startsWith(".maid-") && it.name != "Android" && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !it.name.startsWith('.') && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.take(2001).toList()
        check(files.size <= 2000) { "观察目录超过2000个文件，请选择更小目录" }
        return files.map { "${it.relativeTo(root).invariantSeparatorsPath}:${it.length()}:${it.lastModified()}" }.sorted().joinToString("\n")
    }
    @Synchronized fun event(context: Context, id: String, summary: String) {
        val store = TaskStore.of(context)
        val rule = store.rules().firstOrNull { it.id == id && it.enabled } ?: return
        val now = System.currentTimeMillis()
        if (!TriggerPolicy.cooledDown(rule.lastFired, now)) return
        // No repeated suggestions while a rule's previous task is still pending.
        if (store.all().any { it.sourceRule == id && it.state !in setOf(TaskState.DONE, TaskState.CANCELLED) }) return
        store.rule(rule.copy(lastFired = now, status = "正在判断是否需要联系你"))
        WorkManager.getInstance(context).enqueueUniqueWork("phone-event-$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<EventDecisionWorker>().setInputData(workDataOf("rule" to id, "event" to summary.take(120)))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun taskCompleted(context: Context, task: PhoneTask) {
        TaskStore.of(context).rules().filter { it.enabled && it.kind == TriggerKind.COMPLETED && it.id != task.sourceRule }
            .forEach { event(context, it.id, "一个后台任务已完成；处理结果仍保存在本机") }
    }
}

class EventPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { TriggerEngine.poll(applicationContext); Result.success() }
}

@Serializable internal data class EventDecision(val contact: Boolean = false, val reason: String = "", val offerFileTask: Boolean = false)
class EventDecisionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = TaskStore.of(applicationContext)
        val rule = store.rules().firstOrNull { it.id == inputData.getString("rule") && it.enabled } ?: return@withContext Result.success()
        try {
            val settings = SettingsRepository(applicationContext).settings.first()
            val provider = ProviderStore(applicationContext).snapshot().firstOrNull { it.id == settings.activeProviderId && it.enabled }
                ?: error("请先配置当前 AI 服务")
            val character = com.miniichat.data.AssistantStore(applicationContext).snapshot().firstOrNull { it.id == settings.activeAssistantId }
            val client = LlmClient()
            val result = try { client.completeDetailed(provider, settings.activeModel, listOf(
                ChatMessage("system", "根据用户规则和事件类型判断是否值得联系用户，用当前人设语气说话，不用工作汇报口吻。只返回 JSON {\"contact\":true或false,\"reason\":\"直接对用户说的话\",\"offerFileTask\":false}。只有规则明确要求文件操作才允许offerFileTask=true，不限格式；闲聊问候必须false。没有授权执行任何工具或读取文件。事件不含通知正文，不得虚构已完成工作。人设：${character?.systemPrompt.orEmpty()}"),
                ChatMessage("user", taskJson.encodeToString(mapOf("用户规则" to rule.goal, "事件" to inputData.getString("event").orEmpty())))
            ), temperature = 0.2f, structuredJson = true, requestTimeoutMillis = 60000, maxOutputTokens = 500) } finally { client.close() }
            val decision = taskJson.decodeFromString<EventDecision>(result.content.trim().removePrefix("```json").removeSuffix("```").trim())
            val stillEnabled = store.rules().firstOrNull { it.id == rule.id && it.enabled } ?: return@withContext Result.success()
            if (decision.contact && decision.offerFileTask) {
                TaskActions.create(applicationContext, rule.goal, rule.scope, suggestion = true, ruleId = rule.id, rootDirectory = rule.rootDirectory)
            } else if (decision.contact) {
                check(decision.reason.isNotBlank()) { "提醒为空" }
                check(character != null) { "请先选择人设" }
                val saved = CompanionConversation.receive(applicationContext, character, decision.reason.take(800), provider.id, settings.activeModel)
                com.miniichat.proactive.ProactiveNotifications.publish(applicationContext, character.displayName, decision.reason.take(800), character.avatarPath,
                    com.miniichat.proactive.ProactiveDestination("normal", saved.id))
            }
            store.rule(stillEnabled.copy(status = when {
                decision.contact && decision.offerFileTask -> "已发送整理建议，等待你确认"
                decision.contact -> "已发送提醒，没有读取文件"
                else -> "本次无需打扰"
            }))
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (_: Exception) {
            store.rules().firstOrNull { it.id == rule.id }?.let { store.rule(it.copy(status = "事件判断失败，请检查当前 AI 服务；未执行文件操作")) }
            Result.success()
        }
    }
}

/** System-granted listener. Only explicitly selected packages' event types are used, NEVER their text. */
class TaskNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { com.miniichat.tasks.agent.NotificationAccess.listener = this }
    override fun onListenerDisconnected() { com.miniichat.tasks.agent.NotificationAccess.listener = null }
    private val events = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName || sbn.isOngoing) return
        val sourcePackage = sbn.packageName
        events.launch {
            TaskStore.of(this@TaskNotificationListener).rules().filter { it.enabled && it.kind == TriggerKind.NOTIFICATION && it.match == sourcePackage }
                .forEach { TriggerEngine.event(this@TaskNotificationListener, it.id, "用户选定的应用发来一条通知；不读取通知正文") }
        }
    }
    override fun onDestroy() { com.miniichat.tasks.agent.NotificationAccess.listener = null; events.cancel(); super.onDestroy() }
}
