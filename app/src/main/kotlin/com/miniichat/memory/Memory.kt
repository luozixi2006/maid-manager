package com.miniichat.memory

@kotlinx.serialization.Serializable
data class LongTermMemory(
    val id: String,
    val content: String,
    val category: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val enabled: Boolean = true,
    // Empty means a legacy unassigned memory, NEVER a shared/default persona.
    val personaId: String = "",
    val importance: Double = 0.6,
    val confidence: Double = 0.8,
    val lastConfirmedAt: Long = createdAt,
    val lastUsedAt: Long = 0,
    val sources: List<MemorySource> = emptyList(),
    val stable: Boolean = false,
    val embedding: List<Float> = emptyList(),
    val embeddingModel: String = "",
    val status: String = "active",
    val supersedes: List<String> = emptyList(),
    val revision: Int = 1,
    val origin: String = "manual"
)

@kotlinx.serialization.Serializable
data class MemorySource(val id: String, val kind: String, val at: Long, val quote: String = "")

object MemoryCategories {
    val all = listOf("用户信息", "偏好", "重要经历", "聊天偏好", "长期目标", "项目进展", "相处记忆", "习惯", "其他")
}
