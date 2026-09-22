package com.miniichat.chatdata

import com.miniichat.api.ChatMessage
import com.miniichat.api.LlmClient
import com.miniichat.data.Assistant
import com.miniichat.data.Conversation
import com.miniichat.data.Message
import com.miniichat.data.ProviderConfig
import com.miniichat.memory.LongTermMemory
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

data class HandoffPolicy(
    val inputChunkChars: Int,
    val recentContextChars: Int,
    val maxOutputTokens: Int
)

fun HandoffMode.policy(): HandoffPolicy = when (this) {
    HandoffMode.COMPACT -> HandoffPolicy(14_000, 2_500, 1_200)
    HandoffMode.STANDARD -> HandoffPolicy(18_000, 6_000, 2_200)
    HandoffMode.FULL -> HandoffPolicy(22_000, 14_000, 3_600)
}

object HandoffTextChunker {
    fun chunk(messages: List<Message>, charBudget: Int): List<String> {
        require(charBudget > 200)
        val entries = messages.asSequence()
            .filter { it.role in setOf("user", "assistant") && it.content.isNotBlank() }
            .flatMap { message ->
                val prefix = if (message.role == "user") "用户" else "助手"
                message.content.chunked((charBudget - prefix.length - 16).coerceAtLeast(200))
                    .asSequence().map { "$prefix：$it" }
            }.toList()
        if (entries.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        entries.forEach { entry ->
            if (current.isNotEmpty() && current.length + entry.length + 1 > charBudget) {
                chunks += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.appendLine()
            current.append(entry)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    fun recent(messages: List<Message>, charBudget: Int): List<HandoffRecentMessage> {
        var remaining = charBudget.coerceAtLeast(0)
        val selected = mutableListOf<HandoffRecentMessage>()
        messages.asReversed().forEach { message ->
            if (remaining <= 0 || message.role !in setOf("user", "assistant") || message.content.isBlank()) {
                return@forEach
            }
            val content = if (message.content.length <= remaining) message.content
            else message.content.takeLast(remaining)
            selected += HandoffRecentMessage(message.role, content, message.createdAt)
            remaining -= content.length
        }
        return selected.asReversed()
    }
}

class ConversationHandoffGenerator(private val client: LlmClient) {
    suspend fun generate(
        conversation: Conversation,
        persona: Assistant?,
        memories: List<LongTermMemory>,
        provider: ProviderConfig,
        modelId: String,
        mode: HandoffMode,
        onProgress: (String) -> Unit = {}
    ): MaidManagerHandoff {
        val policy = mode.policy()
        val chunks = HandoffTextChunker.chunk(conversation.messages, policy.inputChunkChars)
        require(chunks.isNotEmpty()) { "当前聊天没有可生成交接包的内容" }

        val extractions = chunks.mapIndexed { index, chunk ->
            onProgress("正在整理第 ${index + 1}/${chunks.size} 段")
            extract(chunk, provider, modelId, policy.maxOutputTokens)
        }
        onProgress("正在合并交接信息")
        val merged = mergeHierarchically(extractions, provider, modelId, policy)
        val selectedMemories = selectHandoffMemories(memories, conversation.assistantId)
        val localPreferences = selectedMemories.filter {
            it.enabled && it.category in setOf("偏好", "聊天偏好", "用户信息")
        }.map { it.content }
        val localMemories = selectedMemories.map {
            HandoffMemory(it.content, importance = 0.9, category = it.category)
        }
        val importantMemories = (localMemories + merged.importantMemories)
            .distinctBy { it.fact.trim().lowercase() }

        return MaidManagerHandoff(
            createdAt = System.currentTimeMillis(),
            mode = mode,
            sourceType = "conversation",
            conversation = HandoffConversation(
                id = conversation.id,
                title = conversation.title,
                lastTopic = merged.currentTopic,
                summary = merged.summary,
                sourceModel = modelId,
                messageCount = conversation.messages.count { it.content.isNotBlank() },
                startedAt = conversation.createdAt,
                updatedAt = conversation.updatedAt
            ),
            character = persona?.let {
                HandoffCharacter(
                    name = it.name,
                    personaId = it.id,
                    systemPrompt = it.systemPrompt,
                    corePersonality = listOf(it.originalPersonality).filter(String::isNotBlank) + merged.corePersonality,
                    currentPersonality = listOf(it.currentPersonality).filter(String::isNotBlank) + merged.currentPersonality,
                    currentState = merged.characterState
                )
            },
            userPreferences = (localPreferences + merged.userPreferences).cleanDistinct(),
            importantMemories = importantMemories,
            currentProjects = merged.currentProjects,
            importantDecisions = merged.importantDecisions.cleanDistinct(),
            unresolvedQuestions = merged.unresolvedQuestions.cleanDistinct(),
            openTasks = merged.openTasks.cleanDistinct(),
            recentContext = HandoffTextChunker.recent(conversation.messages, policy.recentContextChars),
            lastImportantEvents = merged.lastImportantEvents.cleanDistinct(),
            personality = HandoffPersonalityCompatibility(
                originalPersonality = listOfNotNull(persona?.originalPersonality?.takeIf(String::isNotBlank)) + merged.corePersonality,
                currentPersonality = listOfNotNull(persona?.currentPersonality?.takeIf(String::isNotBlank)) + merged.currentPersonality
            )
        )
    }

    private suspend fun extract(
        transcript: String,
        provider: ProviderConfig,
        modelId: String,
        maxOutputTokens: Int
    ): HandoffExtraction {
        val prompt = """
            你是会话交接信息提取器。把下面的聊天片段整理为结构化信息，不能编造。
            用户明确要求、已经决定的规则、未完成事项、人设变化必须分开保留。
            只输出 JSON 对象，不要 Markdown。字段：
            summary, current_topic, core_personality[], current_personality[], character_state[],
            user_preferences[], important_memories[{"fact":"","importance":0.0,"category":""}],
            current_projects[{"name":"","status":"","next_steps":[]}], important_decisions[],
            unresolved_questions[], open_tasks[], last_important_events[]。

            聊天片段：
            $transcript
        """.trimIndent()
        val response = client.completeDetailed(
            provider = provider,
            modelId = modelId,
            messages = listOf(ChatMessage("user", prompt)),
            temperature = 0.2f,
            structuredJson = true,
            requestTimeoutMillis = 120_000,
            maxOutputTokens = maxOutputTokens
        ).content
        return decodeExtraction(response)
    }

    private suspend fun mergeHierarchically(
        initial: List<HandoffExtraction>,
        provider: ProviderConfig,
        modelId: String,
        policy: HandoffPolicy
    ): HandoffExtraction {
        var level = initial
        while (level.size > 1) {
            val groups = mutableListOf<List<HandoffExtraction>>()
            var group = mutableListOf<HandoffExtraction>()
            var size = 0
            level.forEach { extraction ->
                val encoded = ChatDataCodec.json.encodeToString(extraction)
                if (group.isNotEmpty() && size + encoded.length > policy.inputChunkChars) {
                    groups += group
                    group = mutableListOf()
                    size = 0
                }
                group += extraction
                size += encoded.length
            }
            if (group.isNotEmpty()) groups += group
            level = groups.map { mergeGroup(it, provider, modelId, policy.maxOutputTokens) }
        }
        return level.first()
    }

    private suspend fun mergeGroup(
        group: List<HandoffExtraction>,
        provider: ProviderConfig,
        modelId: String,
        maxOutputTokens: Int
    ): HandoffExtraction {
        if (group.size == 1) return group.first()
        val prompt = """
            合并下面多段会话提取结果。去重但不要丢失用户要求、重要决定、未完成事项和人格变化。
            只输出与输入相同字段的 JSON 对象，不要 Markdown。

            ${ChatDataCodec.json.encodeToString(group)}
        """.trimIndent()
        val response = client.completeDetailed(
            provider = provider,
            modelId = modelId,
            messages = listOf(ChatMessage("user", prompt)),
            temperature = 0.1f,
            structuredJson = true,
            requestTimeoutMillis = 120_000,
            maxOutputTokens = maxOutputTokens
        ).content
        return decodeExtraction(response)
    }

    private fun decodeExtraction(raw: String): HandoffExtraction = runCatching {
        ChatDataCodec.json.decodeFromString<HandoffExtraction>(
            with(ChatDataCodec) { raw.extractJsonObject() }
        )
    }.getOrElse { throw IllegalStateException("模型返回的交接信息格式错误", it) }
}

private fun List<String>.cleanDistinct(): List<String> = asSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinctBy(String::lowercase)
    .toList()

/**
 * Handoff context may only carry memories owned by the conversation persona.
 * Legacy unassigned (blank personaId) and other personas' memories stay private.
 */
fun selectHandoffMemories(memories: List<LongTermMemory>, assistantId: String): List<LongTermMemory> {
    if (assistantId.isBlank()) return emptyList()
    return memories.filter { it.enabled && it.status == "active" && it.personaId == assistantId }
}
