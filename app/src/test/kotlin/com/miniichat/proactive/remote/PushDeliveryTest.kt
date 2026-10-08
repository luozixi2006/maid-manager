package com.miniichat.proactive.remote

import android.app.Application
import android.app.NotificationManager
import com.miniichat.data.Assistant
import com.miniichat.data.AssistantStore
import com.miniichat.data.Conversation
import com.miniichat.data.ConversationStore
import com.miniichat.data.Message
import com.miniichat.data.MessageDeliveryStatus
import com.miniichat.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PushDeliveryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val now get() = System.currentTimeMillis()
    private val device = "test-device"
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private fun envelope() = PushEnvelope("remote-message", "chat", "persona", "测试问候", now, "model", "provider")
    private suspend fun prepare(allow: Boolean = true) {
        app.deleteDatabase("remote_push_inbox.db")
        // No real credentials: drain only reads paired state, not the encryption vault.
        app.getSharedPreferences("remote_push", 0).edit().clear().putBoolean("enabled", true)
            .putString("base", "http://127.0.0.1:8787").putString("device_id", device)
            .putString("credentials", "test-pairing-marker").commit()
        SettingsRepository(app).update { it.copy(proactiveDndStartMinutes = 0, proactiveDndEndMinutes = 0) }
        AssistantStore(app).save(listOf(Assistant("persona", "备注", conversationName = "大肥鲸",
            proactiveEnabled = allow, proactiveConsentVersion = 1)))
        ConversationStore(app).save(listOf(Conversation("chat", "原会话", assistantId = "persona", messages = listOf(
            Message("streaming", "assistant", "正在生成", deliveryStatus = MessageDeliveryStatus.STREAMING)
        ))))
        manager.cancelAll()
    }
    @Test fun savesToExistingHistoryAndNotifiesExactlyOnceForNormalRetries() = runBlocking {
        prepare()
        val message = envelope()
        PushInbox(app).use { it.offer(device, message, now) }
        PushDelivery.drain(app)
        val chat = ConversationStore(app).snapshot().single()
        assertEquals(listOf("streaming", "remote-message"), chat.messages.map { it.id })
        assertEquals(MessageDeliveryStatus.STREAMING, chat.messages.first().deliveryStatus)
        val notification = shadowOf(manager).allNotifications.single()
        val style = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
        assertEquals("大肥鲸", style.messages.single().person!!.name.toString())
        assertEquals("chat", shadowOf(notification.contentIntent).savedIntent.getStringExtra("proactive_conversation_id"))
        manager.cancelAll()
        PushInbox(app).use { assertFalse(it.offer(device, message, now)) }
        PushDelivery.drain(app)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals(2, ConversationStore(app).snapshot().single().messages.size)
    }
    @Test fun deletedChatIsNotRecreated() = runBlocking {
        prepare()
        ConversationStore(app).delete("chat")
        PushInbox(app).use { it.offer(device, envelope(), now) }
        PushDelivery.drain(app)
        assertTrue(ConversationStore(app).snapshot().isEmpty())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        PushInbox(app).use { assertTrue(it.finished(device, "remote-message")) }
    }
    @Test fun personaOptOutPreventsInsertionAndNotification() = runBlocking {
        prepare(false)
        PushInbox(app).use { it.offer(device, envelope(), now) }
        PushDelivery.drain(app)
        assertEquals(1, ConversationStore(app).snapshot().single().messages.size)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
    @Test fun disabledRemoteDeliveryKeepsPendingWithoutNotifying() = runBlocking {
        prepare()
        RemotePushConfig(app).enabled(false)
        PushInbox(app).use { it.offer(device, envelope(), now) }
        PushDelivery.drain(app)
        assertEquals(1, ConversationStore(app).snapshot().single().messages.size)
        PushInbox(app).use { assertFalse(it.finished(device, "remote-message")) }
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
