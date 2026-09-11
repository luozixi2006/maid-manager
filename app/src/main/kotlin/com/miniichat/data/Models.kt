package com.miniichat.data

import kotlinx.serialization.Serializable

enum class Role { user, assistant, system }

@Serializable
enum class MessageDeliveryStatus {
    SUCCEEDED,
    STREAMING,
    PARTIAL_FAILED,
    FAILED
}

@Serializable
data class Attachment(
    val type: String,        // "image" | "file"
    val uri: String,         // content:// or file path
    val mimeType: String,
    val name: String,
    val sizeBytes: Long = 0,
    val originalPrompt: String = "",
    val effectivePrompt: String = "",
    val generationModel: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val seed: Long? = null,
    val generationId: String = ""
)

@Serializable
data class Message(
    val id: String,
    val role: String,
    val content: String,
    val attachments: List<Attachment> = emptyList(),
    val modelId: String = "",
    val providerId: String = "",
    val personaId: String = "",
    val webEnabled: Boolean = false,
    val reasoningEnabled: Boolean = false,
    val reasoningText: String = "",
    val reasoningSummary: String = "",
    val sources: List<SourceReference> = emptyList(),
    val ttsCachePath: String? = null,
    val isProactive: Boolean = false,
    val deliveryStatus: MessageDeliveryStatus = MessageDeliveryStatus.SUCCEEDED,
    val errorReportId: String? = null,
    val attemptedRoutes: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class SourceReference(
    val title: String,
    val snippet: String = "",
    val url: String = ""
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<Message> = emptyList(),
    val assistantId: String = "default",
    /** Imported structured context sent as a system message, not shown as original chat history. */
    val handoffContext: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
