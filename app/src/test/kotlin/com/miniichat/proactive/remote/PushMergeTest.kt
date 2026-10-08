package com.miniichat.proactive.remote

import com.miniichat.data.Conversation
import com.miniichat.data.Message
import com.miniichat.data.MessageDeliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushMergeTest {

    private fun envelope(
        messageId: String = "m1",
        conversationId: String = "c1",
        personaId: String = "default",
        content: String = "hello",
        createdAt: Long = 1_000L,
        modelId: String = "gpt",
        providerId: String = "openai"
    ) = PushEnvelope(
        messageId = messageId,
        conversationId = conversationId,
        personaId = personaId,
        content = content,
        createdAt = createdAt,
        modelId = modelId,
        providerId = providerId
    )

    private fun conversation(
        messages: List<Message> = emptyList(),
        updatedAt: Long = 500L,
        handoffContext: String = "imported-context"
    ) = Conversation(
        id = "c1",
        title = "Chat title",
        messages = messages,
        assistantId = "default",
        handoffContext = handoffContext,
        createdAt = 100L,
        updatedAt = updatedAt
    )

    @Test
    fun appendsNewProactiveMessage() {
        val result = PushMerge.merge(conversation(), envelope())
        val added = result as PushMergeResult.Added

        assertEquals(1, added.conversation.messages.size)
        val message = added.conversation.messages.single()
        assertEquals("m1", message.id)
        assertEquals("assistant", message.role)
        assertEquals("hello", message.content)
        assertEquals("default", message.personaId)
        assertEquals("gpt", message.modelId)
        assertEquals("openai", message.providerId)
        assertEquals(1_000L, message.createdAt)
        assertTrue(message.isProactive)
        assertEquals(MessageDeliveryStatus.SUCCEEDED, message.deliveryStatus)
    }

    @Test
    fun identicalMessageIsDuplicateAndOriginalUntouched() {
        val existing = Message(
            id = "m1",
            role = "assistant",
            content = "hello",
            modelId = "gpt",
            providerId = "openai",
            personaId = "default",
            isProactive = true,
            createdAt = 1_000L
        )
        val original = conversation(messages = listOf(existing), updatedAt = 5_000L)

        assertEquals(PushMergeResult.Duplicate, PushMerge.merge(original, envelope()))
        assertEquals(1, original.messages.size)
    }

    @Test
    fun sameIdDifferentPayloadRejected() {
        val conflicting = Message(
            id = "m1",
            role = "assistant",
            content = "different",
            modelId = "gpt",
            providerId = "openai",
            personaId = "default",
            isProactive = true,
            createdAt = 1_000L
        )
        val result = PushMerge.merge(conversation(messages = listOf(conflicting)), envelope())
        assertEquals(
            PushMergeResult.Rejected("message_id_conflict"),
            result
        )
    }

    @Test
    fun sameIdNonProactiveOrUserMessageRejected() {
        val userMessage = Message(
            id = "m1",
            role = "user",
            content = "hello",
            modelId = "gpt",
            providerId = "openai",
            personaId = "default",
            isProactive = false,
            createdAt = 1_000L
        )
        assertEquals(
            PushMergeResult.Rejected("message_id_conflict"),
            PushMerge.merge(conversation(messages = listOf(userMessage)), envelope())
        )
    }

    @Test
    fun wrongConversationOrPersonaRejected() {
        assertEquals(
            PushMergeResult.Rejected("destination_mismatch"),
            PushMerge.merge(conversation(), envelope(conversationId = "other"))
        )
        assertEquals(
            PushMergeResult.Rejected("destination_mismatch"),
            PushMerge.merge(conversation(), envelope(personaId = "other-persona"))
        )
    }

    @Test
    fun preservesHistoryStreamingTitleAndHandoff() {
        val streaming = Message(
            id = "stream-1",
            role = "assistant",
            content = "partial",
            deliveryStatus = MessageDeliveryStatus.STREAMING
        )
        val history = Message(id = "h1", role = "user", content = "earlier")
        val original = conversation(messages = listOf(history, streaming))

        val added = PushMerge.merge(original, envelope(messageId = "m2")) as PushMergeResult.Added
        val merged = added.conversation

        assertEquals(listOf("h1", "stream-1", "m2"), merged.messages.map { it.id })
        assertEquals(MessageDeliveryStatus.STREAMING, merged.messages[1].deliveryStatus)
        assertEquals("Chat title", merged.title)
        assertEquals("imported-context", merged.handoffContext)
        assertEquals("c1", merged.id)
        assertEquals("default", merged.assistantId)
        assertEquals(original.createdAt, merged.createdAt)
        // Original list identity is preserved for untouched entries.
        assertEquals(streaming, merged.messages[1])
    }

    @Test
    fun olderDeliveredTimestampDoesNotReduceUpdatedAt() {
        val original = conversation(updatedAt = 9_000L)
        val added = PushMerge.merge(original, envelope(createdAt = 1_000L)) as PushMergeResult.Added
        assertEquals(9_000L, added.conversation.updatedAt)
    }

    @Test
    fun newerDeliveredTimestampAdvancesUpdatedAt() {
        val original = conversation(updatedAt = 500L)
        val added = PushMerge.merge(original, envelope(createdAt = 7_000L)) as PushMergeResult.Added
        assertEquals(7_000L, added.conversation.updatedAt)
    }
}
