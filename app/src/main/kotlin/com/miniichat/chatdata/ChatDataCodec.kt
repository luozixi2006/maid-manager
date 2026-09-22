package com.miniichat.chatdata

import com.miniichat.data.Assistant
import com.miniichat.data.Conversation
import com.miniichat.data.Message
import com.miniichat.memory.LongTermMemory
import com.miniichat.util.newId
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ArchiveMergeResult(
    val conversations: List<Conversation>,
    val personas: List<Assistant>,
    val memories: List<LongTermMemory>,
    val importedConversations: Int,
    val duplicatedConversations: Int,
    val importedPersonas: Int,
    val importedMemories: Int
)

object ChatDataCodec {
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encodeArchive(
        conversations: List<Conversation>,
        personas: List<Assistant>,
        memories: List<LongTermMemory>,
        exportedAt: Long = System.currentTimeMillis(),
        appVersion: String = ""
    ): String = json.encodeToString(
        MaidManagerChatArchive(
            exportedAt = exportedAt,
            appVersion = appVersion,
            conversations = conversations.map { conversation ->
                conversation.copy(
                    messages = conversation.messages.map { it.copy(ttsCachePath = null) }
                )
            },
            personas = personas,
            memories = memories.map {
                ArchiveMemory(
                    id = it.id,
                    content = it.content,
                    category = it.category,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    enabled = it.enabled,
                    personaId = it.personaId,
                    importance = it.importance,
                    confidence = it.confidence,
                    lastConfirmedAt = it.lastConfirmedAt,
                    lastUsedAt = it.lastUsedAt,
                    sources = it.sources,
                    stable = it.stable,
                    embedding = it.embedding,
                    embeddingModel = it.embeddingModel,
                    status = it.status,
                    supersedes = it.supersedes,
                    revision = it.revision,
                    origin = it.origin
                )
            }
        )
    )

    fun decodeArchive(raw: String): MaidManagerChatArchive {
        val archive = runCatching { json.decodeFromString<MaidManagerChatArchive>(raw) }
            .getOrElse { throw IllegalArgumentException("聊天存档格式错误", it) }
        require(archive.format == CHAT_ARCHIVE_FORMAT) { "不是女仆管理器聊天存档" }
        require(archive.version == CHAT_ARCHIVE_VERSION) { "暂不支持聊天存档版本 ${archive.version}" }
        return archive
    }

    fun mergeArchive(
        archive: MaidManagerChatArchive,
        existingConversations: List<Conversation>,
        existingPersonas: List<Assistant>,
        existingMemories: List<LongTermMemory>,
        idFactory: () -> String = ::newId
    ): ArchiveMergeResult {
        val personaIds = existingPersonas.mapTo(mutableSetOf()) { it.id }
        val mergedPersonas = existingPersonas.toMutableList()
        val personaMap = mutableMapOf<String, String>()
        var importedPersonas = 0
        archive.personas.forEach { incoming ->
            val existing = mergedPersonas.firstOrNull { it.id == incoming.id }
            when {
                existing == null -> {
                    mergedPersonas += incoming
                    personaIds += incoming.id
                    personaMap[incoming.id] = incoming.id
                    importedPersonas++
                }
                existing == incoming -> personaMap[incoming.id] = incoming.id
                else -> {
                    val replacement = uniqueId(personaIds, idFactory)
                    mergedPersonas += incoming.copy(id = replacement)
                    personaMap[incoming.id] = replacement
                    importedPersonas++
                }
            }
        }

        val memoryIds = existingMemories.mapTo(mutableSetOf()) { it.id }
        val mergedMemories = existingMemories.toMutableList()
        var importedMemories = 0
        archive.memories.forEach { incoming ->
            val memory = LongTermMemory(
                id = incoming.id,
                content = incoming.content,
                category = incoming.category,
                createdAt = incoming.createdAt,
                updatedAt = incoming.updatedAt,
                enabled = incoming.enabled,
                // Blank stays unassigned; never fan legacy memories out to every persona.
                personaId = if (incoming.personaId.isBlank()) ""
                else personaMap[incoming.personaId] ?: incoming.personaId,
                importance = incoming.importance,
                confidence = incoming.confidence,
                lastConfirmedAt = incoming.lastConfirmedAt,
                lastUsedAt = incoming.lastUsedAt,
                sources = incoming.sources,
                stable = incoming.stable,
                embedding = incoming.embedding,
                embeddingModel = incoming.embeddingModel,
                status = incoming.status,
                supersedes = incoming.supersedes,
                revision = incoming.revision,
                origin = incoming.origin
            )
            val existing = mergedMemories.firstOrNull { it.id == memory.id }
            when {
                existing == null -> {
                    mergedMemories += memory
                    memoryIds += memory.id
                    importedMemories++
                }
                existing == memory -> Unit
                else -> {
                    mergedMemories += memory.copy(id = uniqueId(memoryIds, idFactory))
                    importedMemories++
                }
            }
        }

        val conversationIds = existingConversations.mapTo(mutableSetOf()) { it.id }
        val messageIds = existingConversations.flatMapTo(mutableSetOf()) { conversation ->
            conversation.messages.map { it.id }
        }
        val mergedConversations = existingConversations.toMutableList()
        var duplicated = 0
        archive.conversations.forEach { incoming ->
            val collision = incoming.id in conversationIds
            val targetId = if (collision) uniqueId(conversationIds, idFactory) else incoming.id.also {
                conversationIds += it
            }
            if (collision) duplicated++
            val messages = incoming.messages.map { message ->
                val messageId = if (message.id in messageIds) uniqueId(messageIds, idFactory)
                else message.id.also { messageIds += it }
                message.copy(id = messageId, ttsCachePath = null)
            }
            mergedConversations += incoming.copy(
                id = targetId,
                title = if (collision) "${incoming.title}（导入）" else incoming.title,
                assistantId = personaMap[incoming.assistantId] ?: incoming.assistantId,
                messages = messages
            )
        }

        return ArchiveMergeResult(
            conversations = mergedConversations.sortedByDescending { it.updatedAt },
            personas = mergedPersonas,
            memories = mergedMemories.sortedByDescending { it.updatedAt },
            importedConversations = archive.conversations.size,
            duplicatedConversations = duplicated,
            importedPersonas = importedPersonas,
            importedMemories = importedMemories
        )
    }

    fun encodeHandoff(handoff: MaidManagerHandoff): String = json.encodeToString(handoff)

    fun decodeHandoff(raw: String): MaidManagerHandoff {
        val handoff = runCatching { json.decodeFromString<MaidManagerHandoff>(raw.extractJsonObject()) }
            .getOrElse { throw IllegalArgumentException("AI 交接包格式错误", it) }
        require(handoff.format == HANDOFF_FORMAT) { "不是女仆管理器 AI 交接包" }
        require(handoff.version == HANDOFF_VERSION) { "暂不支持 AI 交接包版本 ${handoff.version}" }
        return handoff
    }

    fun handoffPrompt(handoff: MaidManagerHandoff): String = buildString {
        appendLine("以下是上一段长期会话的结构化交接信息，请读取后继续当前对话。已有信息无需重新询问。")
        appendLine()
        append(encodeHandoff(handoff))
    }

    fun handoffPrompt(raw: String): String = handoffPrompt(decodeHandoff(raw))

    fun conversationFromHandoff(
        handoff: MaidManagerHandoff,
        assistantId: String,
        idFactory: () -> String = ::newId,
        now: Long = System.currentTimeMillis()
    ): Conversation = Conversation(
        id = idFactory(),
        title = "${handoff.conversation.title.ifBlank { "导入的交接会话" }}（接续）",
        assistantId = assistantId,
        handoffContext = handoffPrompt(handoff),
        createdAt = now,
        updatedAt = now
    )

    fun safeFilePart(value: String): String = value.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .take(60)
        .ifBlank { "聊天" }

    internal fun String.extractJsonObject(): String {
        val clean = trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        require(start >= 0 && end > start) { "没有找到 JSON 对象" }
        return clean.substring(start, end + 1)
    }

    private fun uniqueId(used: MutableSet<String>, idFactory: () -> String): String {
        var candidate: String
        do candidate = idFactory() while (!used.add(candidate))
        return candidate
    }
}
