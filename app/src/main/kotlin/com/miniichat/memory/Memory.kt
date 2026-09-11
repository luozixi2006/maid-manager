package com.miniichat.memory

data class LongTermMemory(
    val id: String,
    val content: String,
    val category: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val enabled: Boolean = true
)

object MemoryCategories {
    val all = listOf("用户信息", "偏好", "重要经历", "聊天偏好", "长期目标", "其他")
}
