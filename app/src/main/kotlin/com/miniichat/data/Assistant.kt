package com.miniichat.data

import kotlinx.serialization.Serializable

@Serializable
data class Assistant(
    val id: String,
    val name: String,
    val avatar: String = "女",
    val avatarPath: String? = null,
    val systemPrompt: String = "You are a helpful assistant.",
    val originalPersonality: String = "",
    val currentPersonality: String = "",
    val preferredProviderId: String? = null,
    val preferredModel: String? = null,
    val temperature: Float? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val proactiveEnabled: Boolean = false,
    val proactiveConsentVersion: Int = 0,
    val lastProactiveMessageAt: Long = 0L,
    val nextProactiveCheckAt: Long = 0L,
    val proactiveMessageSummaries: List<String> = emptyList(),
    val proactiveFailureCount: Int = 0,
    val conversationName: String = ""
) {
    val displayName: String get() = conversationName.trim().ifBlank { name }
    // Old installations keep their effective choice; new per-persona consent has no hidden master switch.
    fun canContact(legacyMasterEnabled: Boolean): Boolean = proactiveEnabled && (proactiveConsentVersion >= 1 || legacyMasterEnabled)
}

object AssistantPresets {
    fun defaults(): List<Assistant> = listOf(
        Assistant(
            id = "default",
            name = "默认人设",
            avatar = "女",
            systemPrompt = "你是一位可靠、友善、简洁的私人助理。请使用用户所用的语言回答。"
        )
    )
}
