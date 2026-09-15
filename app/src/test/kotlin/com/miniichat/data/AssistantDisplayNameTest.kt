package com.miniichat.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AssistantDisplayNameTest {
    @Test fun oldPersonaUsesRemarkUntilUserSetsAName() {
        val original = Json.decodeFromString(Assistant.serializer(), """{"id":"old","name":"英语口语练习"}""")
        assertEquals("英语口语练习", original.displayName)
        val named = original.copy(conversationName = "  Alice  ", avatarPath = "/local/photo.jpg")
        assertEquals("Alice", named.displayName)
        assertEquals("英语口语练习", named.name)
        val restored = Json.decodeFromString(Assistant.serializer(), Json.encodeToString(Assistant.serializer(), named))
        assertEquals(named, restored)
        assertEquals("Alice", restored.copy(name = "修改备注").displayName)
        assertEquals("修改备注", restored.copy(name = "修改备注", conversationName = " ").displayName)
    }
}
