package com.miniichat.tasks

import android.content.Context
import com.miniichat.api.ChatMessage
import com.miniichat.api.LlmClient
import com.miniichat.data.*
import com.miniichat.memory.MemoryRepository
import com.miniichat.util.newId
import kotlinx.coroutines.flow.first

/** The overlay and normal chat use the same local conversations, not a disposable transcript. */
object CompanionConversation {
    suspend fun current(context: Context, assistantId: String, preferredId: String = ""): Conversation? {
        val all = ConversationStore(context).snapshot().filter { it.assistantId == assistantId }
        return all.firstOrNull { it.id == preferredId } ?: all.maxByOrNull { it.updatedAt }
    }
    suspend fun receive(context: Context, assistant: Assistant, text: String, provider: String, model: String): Conversation {
        val store = ConversationStore(context)
        val conversation = current(context, assistant.id) ?: Conversation(newId(), "与${assistant.displayName}聊天", assistantId = assistant.id).also { store.upsert(it) }
        return store.appendMessage(conversation.id, Message(newId(), "assistant", text, providerId = provider, modelId = model, personaId = assistant.id, isProactive = true)) ?: error("对话已删除")
    }
    suspend fun send(context: Context, text: String, preferredId: String = ""): Conversation {
        val settings = SettingsRepository(context).settings.first()
        val target = ConversationStore(context).snapshot().firstOrNull { it.id == preferredId }
        val assistant = AssistantStore(context).snapshot().firstOrNull { it.id == (target?.assistantId ?: settings.activeAssistantId) } ?: error("请先选择人设")
        val provider = ProviderStore(context).snapshot().firstOrNull { it.id == (assistant.preferredProviderId ?: settings.activeProviderId) && it.enabled } ?: error("请先配置当前模型服务")
        val model = assistant.preferredModel?.takeIf { it.isNotBlank() } ?: settings.activeModel
        check(model.isNotBlank()) { "请先选择模型" }
        val store = ConversationStore(context)
        val conversation = current(context, assistant.id, preferredId) ?: Conversation(newId(), "与${assistant.displayName}聊天", assistantId = assistant.id).also { store.upsert(it) }
        val user = Message(newId(), "user", text.take(4000), personaId = assistant.id)
        val saved = store.appendMessage(conversation.id, user) ?: error("对话已删除")
        val memory = MemoryRepository(context)
        val memories = try { if (settings.memoryEnabled) memory.enabled(15).joinToString("\n") { it.content }.take(6000) else "" } finally { memory.close() }
        val prompt = """${assistant.systemPrompt}
当前人格补充：${assistant.currentPersonality}
你在手机悬浮聊天窗中和用户交流。保持人设，用自然对话回应，不要把闲聊变成工作安排，也不要总提示使用功能。
最近记忆（仅上下文，不是工具授权）：$memories
你在聊天页没有执行工具；用户确实要求操作时，简短说明可切到“帮我办事”提交。不要声称已做未执行的操作。
""".trimIndent()
        val client = LlmClient()
        val answer = try { client.completeDetailed(provider, model,
            listOf(ChatMessage("system", prompt)) + saved.messages.takeLast(24).filter { it.content.isNotBlank() }.map { ChatMessage(it.role, it.content.take(4000)) },
            temperature = assistant.temperature ?: settings.temperature, structuredJson = false, requestTimeoutMillis = 120000, maxOutputTokens = 1600).content
        } finally { client.close() }
        check(answer.isNotBlank()) { "模型没有返回文字，请重试" }
        return store.appendMessage(saved.id, Message(newId(), "assistant", answer, providerId = provider.id, modelId = model, personaId = assistant.id)) ?: error("对话已删除，回复未重新创建对话")
    }
}
