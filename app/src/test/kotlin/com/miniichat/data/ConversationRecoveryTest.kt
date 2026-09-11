package com.miniichat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRecoveryTest {
    @Test
    fun emptyStreamingPlaceholderIsRemovedAfterRestart() {
        val conversation = conversation(Message("pending", "assistant", "", deliveryStatus = MessageDeliveryStatus.STREAMING))

        assertTrue(conversation.recoverInterruptedTurn().messages.isEmpty())
    }

    @Test
    fun partialStreamingReplyIsMarkedIncompleteAfterRestart() {
        val conversation = conversation(
            Message("partial", "assistant", "尚未说完", deliveryStatus = MessageDeliveryStatus.STREAMING)
        )

        assertEquals(
            MessageDeliveryStatus.PARTIAL_FAILED,
            conversation.recoverInterruptedTurn().messages.single().deliveryStatus
        )
    }

    @Test
    fun streamingTurnCreatedByCurrentProcessIsNotRecovered() {
        val message = Message(
            "current",
            "assistant",
            "",
            deliveryStatus = MessageDeliveryStatus.STREAMING,
            createdAt = 101
        )

        assertEquals(
            MessageDeliveryStatus.STREAMING,
            conversation(message).recoverInterruptedTurn(startupCutoff = 100).messages.single().deliveryStatus
        )
    }

    private fun conversation(message: Message) = Conversation(
        id = "conversation",
        title = "test",
        messages = listOf(message)
    )
}
