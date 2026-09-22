package com.miniichat.chatdata

import com.miniichat.data.Assistant
import com.miniichat.data.Conversation
import com.miniichat.memory.MemorySource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val CHAT_ARCHIVE_FORMAT = "MaidManagerChatArchive"
const val CHAT_ARCHIVE_VERSION = 1
const val HANDOFF_FORMAT = "MaidManager-Handoff"
const val HANDOFF_VERSION = 1

@Serializable
data class ArchiveMemory(
    val id: String,
    val content: String,
    val category: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    val enabled: Boolean,
    // Empty means a legacy unassigned memory, NEVER a shared/default persona.
    @SerialName("persona_id") val personaId: String = "",
    val importance: Double = 0.6,
    val confidence: Double = 0.8,
    @SerialName("last_confirmed_at") val lastConfirmedAt: Long = 0,
    @SerialName("last_used_at") val lastUsedAt: Long = 0,
    val sources: List<MemorySource> = emptyList(),
    val stable: Boolean = false,
    val embedding: List<Float> = emptyList(),
    @SerialName("embedding_model") val embeddingModel: String = "",
    val status: String = "active",
    val supersedes: List<String> = emptyList(),
    val revision: Int = 1,
    val origin: String = "manual"
)

@Serializable
data class MaidManagerChatArchive(
    val format: String = CHAT_ARCHIVE_FORMAT,
    val version: Int = CHAT_ARCHIVE_VERSION,
    @SerialName("exported_at") val exportedAt: Long,
    @SerialName("app_version") val appVersion: String = "",
    val scope: String = "all_conversations",
    val conversations: List<Conversation>,
    val personas: List<Assistant> = emptyList(),
    val memories: List<ArchiveMemory> = emptyList()
)

@Serializable
enum class HandoffMode { COMPACT, STANDARD, FULL }

@Serializable
data class HandoffConversation(
    val id: String = "",
    val title: String,
    @SerialName("last_topic") val lastTopic: String = "",
    val summary: String = "",
    @SerialName("source_model") val sourceModel: String = "",
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("started_at") val startedAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0
)

@Serializable
data class HandoffCharacter(
    val name: String,
    @SerialName("persona_id") val personaId: String = "",
    @SerialName("system_prompt") val systemPrompt: String = "",
    @SerialName("core_personality") val corePersonality: List<String> = emptyList(),
    @SerialName("current_personality") val currentPersonality: List<String> = emptyList(),
    @SerialName("current_state") val currentState: List<String> = emptyList()
)

@Serializable
data class HandoffMemory(
    val fact: String,
    val importance: Double = 0.7,
    val category: String = ""
)

@Serializable
data class HandoffProject(
    val name: String,
    val status: String = "",
    @SerialName("next_steps") val nextSteps: List<String> = emptyList()
)

@Serializable
data class HandoffRecentMessage(
    val role: String,
    val content: String,
    val timestamp: Long = 0
)

@Serializable
data class HandoffPersonalityCompatibility(
    @SerialName("original_personality") val originalPersonality: List<String> = emptyList(),
    @SerialName("current_personality") val currentPersonality: List<String> = emptyList(),
    @SerialName("personality_evolution") val personalityEvolution: List<String> = emptyList(),
    @SerialName("important_personality_changes") val importantPersonalityChanges: List<String> = emptyList()
)

@Serializable
data class RpHandoffContext(
    @SerialName("project_id") val projectId: String,
    @SerialName("project_name") val projectName: String,
    @SerialName("character_id") val characterId: String = "",
    @SerialName("current_location") val currentLocation: String = "",
    @SerialName("current_scene") val currentScene: String = "",
    @SerialName("world_time") val worldTime: String = "",
    val weather: String = "",
    @SerialName("public_background") val publicBackground: String = "",
    @SerialName("world_rules") val worldRules: String = "",
    val relationships: Map<String, String> = emptyMap(),
    @SerialName("character_memories") val characterMemories: List<String> = emptyList(),
    @SerialName("major_events") val majorEvents: List<String> = emptyList(),
    @SerialName("unresolved_plot") val unresolvedPlot: List<String> = emptyList()
)

@Serializable
data class MaidManagerHandoff(
    val format: String = HANDOFF_FORMAT,
    val version: Int = HANDOFF_VERSION,
    @SerialName("created_at") val createdAt: Long,
    val mode: HandoffMode = HandoffMode.STANDARD,
    @SerialName("source_type") val sourceType: String = "conversation",
    val conversation: HandoffConversation,
    val character: HandoffCharacter? = null,
    @SerialName("user_preferences") val userPreferences: List<String> = emptyList(),
    @SerialName("important_memories") val importantMemories: List<HandoffMemory> = emptyList(),
    @SerialName("current_projects") val currentProjects: List<HandoffProject> = emptyList(),
    @SerialName("important_decisions") val importantDecisions: List<String> = emptyList(),
    @SerialName("unresolved_questions") val unresolvedQuestions: List<String> = emptyList(),
    @SerialName("open_tasks") val openTasks: List<String> = emptyList(),
    @SerialName("recent_context") val recentContext: List<HandoffRecentMessage> = emptyList(),
    @SerialName("last_important_events") val lastImportantEvents: List<String> = emptyList(),
    val personality: HandoffPersonalityCompatibility = HandoffPersonalityCompatibility(),
    val rp: RpHandoffContext? = null,
    @SerialName("resume_instruction") val resumeInstruction: String =
        "继续按照以上上下文与用户交流。已有信息无需重新询问；不确定的内容应先确认，不要把压缩信息当作逐字原始记录。"
)

@Serializable
internal data class HandoffExtraction(
    val summary: String = "",
    @SerialName("current_topic") val currentTopic: String = "",
    @SerialName("core_personality") val corePersonality: List<String> = emptyList(),
    @SerialName("current_personality") val currentPersonality: List<String> = emptyList(),
    @SerialName("character_state") val characterState: List<String> = emptyList(),
    @SerialName("user_preferences") val userPreferences: List<String> = emptyList(),
    @SerialName("important_memories") val importantMemories: List<HandoffMemory> = emptyList(),
    @SerialName("current_projects") val currentProjects: List<HandoffProject> = emptyList(),
    @SerialName("important_decisions") val importantDecisions: List<String> = emptyList(),
    @SerialName("unresolved_questions") val unresolvedQuestions: List<String> = emptyList(),
    @SerialName("open_tasks") val openTasks: List<String> = emptyList(),
    @SerialName("last_important_events") val lastImportantEvents: List<String> = emptyList()
)
