package com.miniichat.proactive.remote

import android.content.Context
import com.miniichat.data.AssistantStore
import com.miniichat.data.ConversationStore
import com.miniichat.data.SettingsRepository
import com.miniichat.memory.MemoryWork
import com.miniichat.proactive.ProactiveDestination
import com.miniichat.proactive.ProactiveDiagnostics
import com.miniichat.proactive.ProactiveNotifications
import com.miniichat.proactive.ProactivePolicy
import com.miniichat.watchlink.PhoneLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** No network or model call before notification delivery. Database commit happens first. */
object PushDelivery {
    private val mutex = Mutex()
    suspend fun drain(context: Context) = mutex.withLock {
        val config = RemotePushConfig(context)
        if (!config.enabled || !config.paired) return@withLock
        val deviceId = config.deviceId
        PushInbox(context).use { inbox ->
            while (true) {
              val batch = inbox.pending(deviceId)
              if (batch.isEmpty()) break
              for (envelope in batch) {
                if (!config.enabled || config.deviceId != deviceId) return@withLock
                val settings = SettingsRepository(context).settings.first()
                val persona = AssistantStore(context).snapshot().firstOrNull { it.id == envelope.personaId }
                if (persona == null || !persona.canContact(settings.proactiveMessagesEnabled)) {
                    inbox.finish(deviceId, envelope.messageId, "discarded")
                    PushTrace.record(context, "discarded", envelope.messageId, "persona_unavailable")
                    continue
                }
                var result: PushMergeResult? = null
                val saved = ConversationStore(context).updateConversation(envelope.conversationId) { current ->
                    if (!config.enabled || config.deviceId != deviceId) return@updateConversation current
                    val merged = PushMerge.merge(current, envelope).also { result = it }
                    if (merged is PushMergeResult.Added) merged.conversation else current
                }
                if (result is PushMergeResult.Rejected || saved == null) {
                    // Never recreate a deleted chat/persona, nor route another persona's message into it.
                    inbox.finish(deviceId, envelope.messageId, "discarded")
                    PushTrace.record(context, "discarded", envelope.messageId, "conversation_unavailable")
                    continue
                }
                if (result == null) return@withLock
                val now = System.currentTimeMillis()
                val quiet = ProactivePolicy.isInDoNotDisturb(now, settings.proactiveDndStartMinutes, settings.proactiveDndEndMinutes)
                val tooOldToAlert = now - envelope.createdAt > 24L * 60L * 60L * 1000L
                // An unfinished inbox row may have committed history before a process death.
                // Retry the same tagged notification; finished rows never reach this loop.
                val alert = inbox.allowAlert(deviceId, envelope.messageId)
                val notified = if (!alert || quiet || tooOldToAlert) false else ProactiveNotifications.publish(
                    context, persona.displayName, envelope.content, persona.avatarPath,
                    ProactiveDestination("normal", envelope.conversationId), deliveryId = envelope.messageId
                )
                val outcome = if (notified) "notified" else if (alert && !quiet && !tooOldToAlert) "blocked" else "saved_silent"
                inbox.finish(deviceId, envelope.messageId, outcome)
                PushTrace.record(context, "history_committed", envelope.messageId, outcome)
                ProactiveDiagnostics.record(context, persona.id, when {
                    notified -> "电脑发来的主动消息已保存并提交通知"
                    quiet -> "主动消息已保存；当前处于安静时段"
                    tooOldToAlert -> "离线期间的消息已补回聊天，不再弹出旧提醒"
                    !alert -> "主动消息已补回原聊天，不重复弹出通知"
                    else -> "主动消息已保存；${ProactiveNotifications.blockedReason(context) ?: "通知暂未显示"}"
                })
                try {
                    AssistantStore(context).update(persona.id) { it.copy(lastProactiveMessageAt = maxOf(it.lastProactiveMessageAt, envelope.createdAt)) }
                    SettingsRepository(context).update { it.copy(lastProactiveMessageAt = maxOf(it.lastProactiveMessageAt, envelope.createdAt)) }
                    PhoneLink.mirror(context, saved)
                    MemoryWork.enqueueConversation(context, saved.id)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { config.status("消息已保存，手表或记忆同步稍后重试") }
              }
            }
        }
    }
}
