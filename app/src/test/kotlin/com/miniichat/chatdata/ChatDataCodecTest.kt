package com.miniichat.chatdata

import com.miniichat.data.Assistant
import com.miniichat.data.Conversation
import com.miniichat.data.Message
import com.miniichat.memory.LongTermMemory
import com.miniichat.memory.MemorySource
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

    @Test
    fun archiveMemoryRoundTripsAllMetadataFields() {
        val memory = LongTermMemory(
            id = "l1",
            content = "fact",
            category = "cat",
            createdAt = 10L,
            updatedAt = 20L,
            enabled = true,
            personaId = "p1",
            importance = 0.9,
            confidence = 0.7,
            lastConfirmedAt = 30L,
            lastUsedAt = 40L,
            sources = listOf(MemorySource("s1", "chat", 50L, "quote")),
            stable = true,
            embedding = listOf(0.1f, 0.2f),
            embeddingModel = "model-x",
            status = "archived",
            supersedes = listOf("old"),
            revision = 3,
            origin = "auto"
        )
        val decoded = ChatDataCodec.decodeArchive(
            ChatDataCodec.encodeArchive(
                conversations = emptyList(),
                personas = emptyList(),
                memories = listOf(memory),
                exportedAt = 1L
            )
        )
        assertEquals(1, decoded.version)
        val archived = decoded.memories.single()
        assertEquals("p1", archived.personaId)
        assertEquals(0.9, archived.importance, 0.0)
        assertEquals(0.7, archived.confidence, 0.0)
        assertEquals(30L, archived.lastConfirmedAt)
        assertEquals(40L, archived.lastUsedAt)
        assertEquals(listOf(MemorySource("s1", "chat", 50L, "quote")), archived.sources)
        assertTrue(archived.stable)
        assertEquals(listOf(0.1f, 0.2f), archived.embedding)
        assertEquals("model-x", archived.embeddingModel)
        assertEquals("archived", archived.status)
        assertEquals(listOf("old"), archived.supersedes)
        assertEquals(3, archived.revision)
        assertEquals("auto", archived.origin)
        val merged = ChatDataCodec.mergeArchive(decoded, emptyList(), emptyList(), emptyList())
        assertEquals(memory, merged.memories.single())
    }

    @Test
    fun legacyArchiveMemoryDecodesWithUnassignedDefaults() {
        val legacy = """
            {
              "format": "MaidManagerChatArchive",
              "version": 1,
              "exported_at": 1,
              "conversations": [],
              "memories": [
                {"id": "m1", "content": "old fact", "category": "cat",
                 "created_at": 10, "updated_at": 20, "enabled": true}
              ]
            }
        """.trimIndent()
        val decoded = ChatDataCodec.decodeArchive(legacy)
        val archived = decoded.memories.single()
        assertEquals("", archived.personaId)
        assertEquals(0.6, archived.importance, 0.0)
        assertEquals(0.8, archived.confidence, 0.0)
        assertEquals(0L, archived.lastConfirmedAt)
        assertEquals(0L, archived.lastUsedAt)
        assertTrue(archived.sources.isEmpty())
        assertFalse(archived.stable)
        assertTrue(archived.embedding.isEmpty())
        assertEquals("", archived.embeddingModel)
        assertEquals("active", archived.status)
        assertTrue(archived.supersedes.isEmpty())
        assertEquals(1, archived.revision)
        assertEquals("manual", archived.origin)
        val merged = ChatDataCodec.mergeArchive(decoded, emptyList(), emptyList(), emptyList())
        assertEquals("", merged.memories.single().personaId)
        assertTrue(merged.memories.single().enabled)
    }

    @Test
    fun mergeRemapsMemoryOwnerWhenPersonaCollides() {
        val archive = MaidManagerChatArchive(
            conversations = emptyList(),
            exportedAt = 1L,
            personas = listOf(Assistant("p1", "incoming", systemPrompt = "new")),
            memories = listOf(
                ArchiveMemory("m1", "mine", "cat", 10L, 20L, true, personaId = "p1"),
                ArchiveMemory("m2", "legacy", "cat", 10L, 20L, true)
            )
        )
        val result = ChatDataCodec.mergeArchive(
            archive,
            existingConversations = emptyList(),
            existingPersonas = listOf(Assistant("p1", "existing", systemPrompt = "old")),
            existingMemories = emptyList()
        ) { "generated-1" }
        val owned = result.memories.single { it.id == "m1" }
        assertEquals("generated-1", owned.personaId)
        assertEquals("mine", owned.content)
        assertEquals("", result.memories.single { it.id == "m2" }.personaId)
        assertEquals(2, result.importedMemories)
    }

    @Test
    fun memoryCollisionWithDifferentOwnerIsPreservedNotDropped() {
        val existing = LongTermMemory(id = "m", content = "shared", category = "cat", personaId = "a")
        val archive = MaidManagerChatArchive(
            conversations = emptyList(),
            exportedAt = 1L,
            memories = listOf(
                ArchiveMemory("m", "shared", "cat", existing.createdAt, existing.updatedAt, true, personaId = "b")
            )
        )
        val result = ChatDataCodec.mergeArchive(archive, emptyList(), emptyList(), listOf(existing)) { "generated-1" }
        assertEquals(2, result.memories.size)
        assertEquals(setOf("a", "b"), result.memories.map { it.personaId }.toSet())
        assertNotEquals(result.memories[0].id, result.memories[1].id)
    }
}
