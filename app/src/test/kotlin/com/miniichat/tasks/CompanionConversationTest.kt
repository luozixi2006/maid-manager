package com.miniichat.tasks

import android.app.Application
import com.miniichat.api.LocalHttpServer
import com.miniichat.api.MockResponse
import com.miniichat.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CompanionConversationTest {
    @Test fun repliesToGreetingInSamePersistedConversationWithoutRunningTask() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val calls = CopyOnWriteArrayList<String>()
        val server = LocalHttpServer { request -> calls += request
            MockResponse(200, """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"我也想和你聊聊今天的事。"}}]}""") }
        try {
            ProviderStore(app).save(listOf(ProviderConfig("test", "local", "http://127.0.0.1:${server.port}", "", authMode = ProviderAuthMode.NONE)))
            SettingsRepository(app).update { it.copy(activeProviderId = "test", activeModel = "test-model", activeAssistantId = "different", memoryEnabled = false) }
            val persona = Assistant("summer", "小夏", systemPrompt = "用温柔自然的语气陪伴用户。")
            AssistantStore(app).save(listOf(persona, Assistant("different", "另一人设")))
            val notice = CompanionConversation.receive(app, persona, "今天想听你聊聊。", "test", "test-model")
            val reply = CompanionConversation.send(app, "我刚忙完", notice.id)
            assertEquals(notice.id, reply.id); assertEquals("summer", reply.assistantId)
            assertEquals(listOf("assistant", "user", "assistant"), reply.messages.map { it.role })
            assertTrue(calls.single().contains("今天想听你聊聊"))
            assertTrue(calls.single().contains("用温柔自然的语气"))
            assertEquals(3, CompanionConversation.current(app, "summer")!!.messages.size)
        } finally { server.close() }
    }
}
