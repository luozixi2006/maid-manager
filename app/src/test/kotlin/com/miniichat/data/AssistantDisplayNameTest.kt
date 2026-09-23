package com.miniichat.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AssistantDisplayNameTest {
    @Test fun oldPersonaUsesRemarkUntilUserSetsAName() {
        val original = Json.decodeFromString(Assistant.serializer(), """{"id":"old","name":"英语口语练习"}""")
        assertEquals("英语口语练习", original.displayName)
        // A wall-clock default can be omitted at encode time and change during decode.
        // Keep this name/legacy-field test deterministic instead of racing the clock.
        val named = original.copy(createdAt = 1L, conversationName = "  Alice  ", avatarPath = "/local/photo.jpg")
        assertEquals("Alice", named.displayName)
        assertEquals("英语口语练习", named.name)
        val restored = Json.decodeFromString(Assistant.serializer(), Json.encodeToString(Assistant.serializer(), named))
        assertEquals(named, restored)
        assertEquals("Alice", restored.copy(name = "修改备注").displayName)
        assertEquals("修改备注", restored.copy(name = "修改备注", conversationName = " ").displayName)
    }
}
