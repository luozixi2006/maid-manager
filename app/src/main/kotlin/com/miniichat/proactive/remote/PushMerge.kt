package com.miniichat.proactive.remote

import com.miniichat.data.Conversation
import com.miniichat.data.Message
import com.miniichat.data.MessageDeliveryStatus

/** Outcome of merging a validated remote envelope into an existing conversation. */
sealed interface PushMergeResult {
    /** Envelope was appended; [conversation] is a new instance, the input is untouched. */
    data class Added(val conversation: Conversation) : PushMergeResult

    /** An identical proactive message already exists; nothing was changed. */
    data object Duplicate : PushMergeResult

    /** The envelope cannot be merged; [reason] is a short stable code. */
    data class Rejected(val reason: String) : PushMergeResult
}

/**
 * Pure merge helper for remote proactive character messages.
 *
 * Never creates a conversation, persona or provider, never rewrites or reorders
 * existing history (including streaming messages), and never sorts timestamps.
 */
object PushMerge {

    const val REASON_DESTINATION_MISMATCH = "destination_mismatch"
    const val REASON_MESSAGE_ID_CONFLICT = "message_id_conflict"

    fun merge(conversation: Conversation, envelope: PushEnvelope): PushMergeResult {
        if (conversation.id != envelope.conversationId ||
            conversation.assistantId != envelope.personaId
        ) {
            return PushMergeResult.Rejected(REASON_DESTINATION_MISMATCH)
        }

        val existing = conversation.messages.firstOrNull { it.id == envelope.messageId }
        if (existing != null) {
            val identical = existing.role == ROLE_ASSISTANT &&
                existing.content == envelope.content &&
                existing.personaId == envelope.personaId &&
                existing.modelId == envelope.modelId &&
                existing.providerId == envelope.providerId &&
                existing.createdAt == envelope.createdAt &&
                existing.isProactive
            return if (identical) {
                PushMergeResult.Duplicate
            } else {
                PushMergeResult.Rejected(REASON_MESSAGE_ID_CONFLICT)
            }
        }

        val appended = Message(
            id = envelope.messageId,
            role = ROLE_ASSISTANT,
            content = envelope.content,
            modelId = envelope.modelId,
            providerId = envelope.providerId,
            personaId = envelope.personaId,
            isProactive = true,
            deliveryStatus = MessageDeliveryStatus.SUCCEEDED,
            createdAt = envelope.createdAt
        )

        return PushMergeResult.Added(
            conversation.copy(
                messages = conversation.messages + appended,
                updatedAt = maxOf(conversation.updatedAt, envelope.createdAt)
            )
        )
    }

    private const val ROLE_ASSISTANT = "assistant"
}
