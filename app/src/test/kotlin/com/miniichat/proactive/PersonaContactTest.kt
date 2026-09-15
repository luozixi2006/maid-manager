package com.miniichat.proactive

import android.app.Application
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.miniichat.api.LocalHttpServer
import com.miniichat.api.MockResponse
import com.miniichat.data.*
import com.miniichat.tasks.PetMessages
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PersonaContactTest {
    @Test fun personaCanGreetWithoutTaskRuleOrLegacyMasterSwitch() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val decision = """{"action":"SEND","message":"午后好，想陪你聊会儿。","topic_summary":"午后问候","next_contact_tendency":0.8}"""
        val server = LocalHttpServer { _ -> MockResponse(200, buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
            put("finish_reason", "stop"); put("message", buildJsonObject { put("role", "assistant"); put("content", decision) })
        }) }) }.toString()) }
        try {
            ProviderStore(app).save(listOf(ProviderConfig("test", "local", "http://127.0.0.1:${server.port}", "", authMode = ProviderAuthMode.NONE)))
            SettingsRepository(app).update { it.copy(activeProviderId = "test", activeModel = "test-model", proactiveMessagesEnabled = false,
                proactiveDndStartMinutes = 0, proactiveDndEndMinutes = 0, lastProactiveMessageAt = 0, memoryEnabled = false) }
            AssistantStore(app).save(listOf(Assistant("companion", "小夏", proactiveEnabled = true, proactiveConsentVersion = 1, nextProactiveCheckAt = 1)))
            ConversationStore(app).save(emptyList())
            TestListenableWorkerBuilder<ProactiveMessageWorker>(app).build().doWork()
            val message = ConversationStore(app).snapshot().single().messages.single()
            assertTrue(message.isProactive); assertEquals("午后好，想陪你聊会儿。", message.content)
            assertTrue(PetMessages.notice.value.contains("午后好"))
            assertTrue(PetMessages.conversationId.isNotBlank())
        } finally { server.close(); PetMessages.notice.value = ""; PetMessages.conversationId = ""; WorkManagerTestInitHelper.closeWorkDatabase() }
    }
}
