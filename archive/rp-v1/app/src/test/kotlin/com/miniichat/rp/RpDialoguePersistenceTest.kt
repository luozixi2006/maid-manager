package com.miniichat.rp

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpDialoguePersistenceTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun oldWorldWithoutVisibleHistoriesMigratesWithEmptyDefault() {
        val old = """{"id":"world-a","name":"旧世界"}"""
        val world = json.decodeFromString(RpWorld.serializer(), old)

        assertTrue(world.characterDialogues.isEmpty())
    }

    @Test
    fun visibleHistoryPersistsByWorldAndStableCharacterId() {
        val message = RpDialogueMessage(
            id = "message-1",
            role = "user",
            text = "你这里有什么材料？",
            worldId = "world-a",
            characterId = "character-a",
            sequence = 1
        )
        val world = RpWorld(
            id = "world-a",
            name = "测试世界",
            characterDialogues = mapOf(
                "character-a" to RpDialogue("character-a", listOf(message))
            )
        )

        val restored = json.decodeFromString(
            RpWorld.serializer(),
            json.encodeToString(RpWorld.serializer(), world)
        )
        val restoredMessage = restored.characterDialogues.getValue("character-a").messages.single()
        assertEquals("world-a", restoredMessage.worldId)
        assertEquals("character-a", restoredMessage.characterId)
        assertEquals("你这里有什么材料？", restoredMessage.text)
    }

    @Test
    fun portraitPromptUsesWorldAndCharacterVisualFacts() {
        val world = RpWorld(
            id = "world-a",
            name = "阴雨小镇",
            publicBackground = "中世纪沿海地区文化",
            rules = "低技术、无现代服饰",
            visualStyle = "低饱和黑暗奇幻半写实"
        )
        val character = RpCharacter(
            id = "character-a",
            name = "格瑞姆",
            knownIdentity = "铁匠",
            gender = "男",
            age = "四十岁",
            hair = "灰黑短发",
            appearance = "严肃，脸上有旧伤",
            clothing = "旧皮围裙"
        )

        val prompt = buildRpPortraitPrompt(world, character)
        assertTrue(prompt.contains("低饱和黑暗奇幻半写实"))
        assertTrue(prompt.contains("中世纪沿海地区文化"))
        assertTrue(prompt.contains("铁匠"))
        assertTrue(prompt.contains("旧皮围裙"))
    }
}
