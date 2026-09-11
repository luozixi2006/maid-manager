package com.miniichat.data

/** Repairs a turn whose process died before the streaming job could run its finalizer. */
internal fun Conversation.recoverInterruptedTurn(startupCutoff: Long = Long.MAX_VALUE): Conversation {
    var changed = false
    val recovered = messages.mapNotNull { message ->
        if (message.role != "assistant" ||
            message.deliveryStatus != MessageDeliveryStatus.STREAMING ||
            message.createdAt >= startupCutoff
        ) {
            return@mapNotNull message
        }
        changed = true
        if (message.content.isBlank() && message.reasoningText.isBlank()) {
            null
        } else {
            message.copy(deliveryStatus = MessageDeliveryStatus.PARTIAL_FAILED)
        }
    }
    return if (changed) copy(messages = recovered, updatedAt = System.currentTimeMillis()) else this
}
