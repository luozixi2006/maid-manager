package com.miniichat.proactive.remote

import android.content.Context
import android.content.Intent
import com.miniichat.data.ConversationStore
import com.miniichat.proactive.ProactiveDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PushIngress {
    private val fields = listOf("schema_version", "type", "device_id", "ciphertext")

    /** Launcher extras from notification+data must be authenticated, never trusted as a navigation command. */
    fun takeIntentData(intent: Intent?): Map<String, String>? {
        if (intent == null || !intent.hasExtra("ciphertext")) return null
        return try { fields.associateWith { intent.getStringExtra(it).orEmpty() } }
        catch (_: RuntimeException) { null }
        finally { fields.forEach { intent.removeExtra(it) } }
    }

    suspend fun receive(context: Context, data: Map<String, String>, source: String): PushEnvelope? = withContext(Dispatchers.IO) {
        val config = RemotePushConfig(context)
        if (!config.enabled || !config.paired) return@withContext null
        try {
            val plain = PushCipher.decrypt(data, config.payloadKey(), config.deviceId)
            val result = PushEnvelopeParser.parse(plain, System.currentTimeMillis(), allowArchived = source == "notification_tap")
            if (result !is PushParseResult.Accepted) {
                PushTrace.record(context, "envelope_rejected", detail = source)
                return@withContext null
            }
            PushInbox(context).use {
                val added = it.offer(config.deviceId, result.envelope, System.currentTimeMillis(), source, allowAlert = source == "fcm")
                PushTrace.record(context, if (added) "inbox_saved" else "duplicate_received", result.envelope.messageId, source)
            }
            PushInboxWorker.enqueue(context)
            result.envelope
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PushTrace.record(context, "ingress_failed", detail = source)
            null
        }
    }

    suspend fun opened(context: Context, data: Map<String, String>): ProactiveDestination? = withContext(Dispatchers.IO) {
        val envelope = receive(context, data, "notification_tap") ?: return@withContext null
        PushDelivery.drain(context)
        RemotePushRuntime.enqueue(context)
        val chat = ConversationStore(context).snapshot().firstOrNull { it.id == envelope.conversationId && it.assistantId == envelope.personaId }
        if (chat == null || chat.messages.none { it.id == envelope.messageId }) null
        else ProactiveDestination("normal", chat.id)
    }
}
