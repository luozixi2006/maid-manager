package com.miniichat.chatdata

import com.miniichat.data.Assistant
import com.miniichat.data.Conversation
import com.miniichat.data.Message
import com.miniichat.memory.LongTermMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDataCodecTest {
    @Test
    fun archiveRoundTripContainsLocalChatDataButNoCredentialField() {
        val raw = ChatDataCodec.encodeArchive(
            conversations = listOf(Conversation("c1", "chat", listOf(Message("m1", "user", "hello")))),
            personas = listOf(Assistant("a1", "persona", systemPrompt = "prompt")),
            memories = listOf(LongTermMemory("l1", "memory", "test")),
            exportedAt = 123L,
            appVersion = "test"
        )
        val decoded = ChatDataCodec.decodeArchive(raw)
        assertEquals("hello", decoded.conversations.single().messages.single().content)
        assertFalse(raw.contains("apiKey", ignoreCase = true))
        assertFalse(raw.contains("Authorization", ignoreCase = true))
    }

    @Test
    fun mergeNeverOverwritesCollidingConversation() {
        val existing = Conversation("same", "existing", listOf(Message("same-message", "user", "old")))
        val incoming = Conversation("same", "incoming", listOf(Message("same-message", "assistant", "new")))
        val archive = MaidManagerChatArchive(exportedAt = 1L, conversations = listOf(incoming))
        var id = 0
        val result = ChatDataCodec.mergeArchive(
            archive,
            listOf(existing),
            emptyList(),
            emptyList()
        ) { "generated-${++id}" }
        assertEquals(2, result.conversations.size)
        assertTrue(result.conversations.any { it.title == "existing" && it.messages.single().content == "old" })
        val imported = result.conversations.first { it.title.contains("导入") }
        assertNotEquals("same", imported.id)
        assertNotEquals("same-message", imported.messages.single().id)
    }

    @Test
    fun handoffCanBeReadFromMarkdownFence() {
        val handoff = MaidManagerHandoff(
            createdAt = 1L,
            conversation = HandoffConversation(title = "handoff", summary = "context")
        )
        val decoded = ChatDataCodec.decodeHandoff("```json\n${ChatDataCodec.encodeHandoff(handoff)}\n```")
        assertEquals("context", decoded.conversation.summary)
    }
}
