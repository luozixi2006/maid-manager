package com.miniichat.rp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpEngineTest {
    @Test
    fun parsesNormalSceneJson() {
        val result = RpEngine.parseSceneResult(
            """{"narration":"你来到门前。","timeAdvanceMinutes":5,"presentCharacters":["格罗姆"]}"""
        )

        assertEquals("你来到门前。", result?.narration)
        assertEquals(5L, result?.timeAdvanceMinutes)
        assertEquals(listOf("格罗姆"), result?.presentCharacters)
    }

    @Test
    fun parsesMarkdownFencedJson() {
        val result = RpEngine.parseSceneResult(
            """
                下面是结果：
                ```json
                {"narration":"雨落在青石路上。","weather":"小雨"}
                ```
            """.trimIndent()
        )

        assertEquals("雨落在青石路上。", result?.narration)
        assertEquals("小雨", result?.weather)
    }

    @Test
    fun preservesChinesePunctuationRareCharactersAndEmoji() {
        val text = "龘先生说：“风、雨……都来了。”🙂"
        val result = RpEngine.parseSceneResult(
            """{"narration":"$text","presentCharacters":[]}"""
        )

        assertEquals(text, result?.narration)
        assertFalse(result?.narration.orEmpty().contains('□'))
        assertFalse(result?.narration.orEmpty().contains('�'))
    }

    @Test
    fun malformedJsonIsRejectedInsteadOfDisplayed() {
        val raw = """{"narration":"不完整", "presentCharacters":[}"""

        assertNull(RpEngine.parseSceneResult(raw))
    }

    @Test
    fun nullAndBlankNarrationAreRejected() {
        assertNull(RpEngine.parseSceneResult("null"))
        assertNull(RpEngine.parseSceneResult("""{"narration":""}"""))
    }

    @Test
    fun nullFieldsAndEmptyArraysKeepTheirMeaning() {
        val result = RpEngine.parseSceneResult(
            """{"narration":"四周无人。","weather":null,"currentLocation":null,"presentCharacters":[],"newLocations":[],"newCharacters":[],"characterUpdates":[],"stateUpdates":[],"events":[]}"""
        )

        assertNull(result?.weather)
        assertNull(result?.currentLocation)
        assertTrue(result?.presentCharacters?.isEmpty() == true)
        assertTrue(result?.stateUpdates?.isEmpty() == true)
    }

    @Test
    fun bracesInsideNarrationDoNotBreakObjectExtraction() {
        val result = RpEngine.parseSceneResult(
            "模型前言\n{\"narration\":\"门牌写着 {旧城}。\",\"events\":[]}\n结束"
        )

        assertEquals("门牌写着 {旧城}。", result?.narration)
    }

    @Test
    fun oldWorldSaveReceivesCompatibleModuleDefaults() {
        val world = RpEngine.json.decodeFromString<RpWorld>(
            """{"id":"old","name":"旧小镇","locations":[],"characters":[]}"""
        )

        assertEquals(RpProjectMode.HYBRID, world.projectMode)
        assertTrue(world.modules.locationsEnabled)
        assertTrue(world.modules.timelineEnabled)
        assertFalse(world.modules.storyEnabled)
    }

    @Test
    fun storyProjectCanExplicitlyDisableMapAndTimeline() {
        val generated = RpEngine.parse<GeneratedWorld>(
            """{"name":"转学日记","projectMode":"STORY","locationsEnabled":false,"timelineEnabled":false,"randomEventsEnabled":false,"storyEnabled":true,"npcSimulationEnabled":false,"locations":[],"scenes":[{"name":"教室"}],"storyArcs":[{"name":"转学","active":true}]}"""
        )

        assertEquals("STORY", generated?.projectMode)
        assertFalse(generated?.locationsEnabled ?: true)
        assertTrue(generated?.storyEnabled == true)
        assertTrue(generated?.locations?.isEmpty() == true)
    }

    @Test
    fun noMapProjectPromptDoesNotRequireMovement() {
        val world = RpWorld(
            id = "story", name = "校园故事", projectMode = RpProjectMode.STORY,
            modules = RpProjectModules(
                locationsEnabled = false, timelineEnabled = false,
                randomEventsEnabled = false, storyEnabled = true,
                npcSimulationEnabled = false
            ),
            scenes = listOf(RpScene("classroom", "教室", "转学第一天")),
            currentSceneId = "classroom"
        )

        val system = RpEngine.scenePrompt(world, "我向同桌打招呼。", false).first

        assertTrue(system.contains("当前未启用地图"))
        assertTrue(system.contains("不得因为格式创建 newLocations"))
        assertTrue(system.contains("未启用时间线"))
        assertTrue(system.contains("当前场景:教室"))
    }

    @Test
    fun stateOwnershipTargetsRemainIndependent() {
        val template = RpStateDefinition(
            id = "affection", name = "好感", owner = RpStateOwner.CHARACTER_TEMPLATE
        )
        val specific = RpStateDefinition(
            id = "trust", name = "信任", owner = RpStateOwner.CHARACTER,
            ownerTargetId = "li", ownerTargetName = "莉"
        )

        assertEquals(RpStateOwner.CHARACTER_TEMPLATE, template.owner)
        assertEquals("li", specific.ownerTargetId)
        assertEquals("莉", specific.ownerTargetName)
    }

    @Test
    fun characterTemplateCreatesIndependentValuesForEveryCharacter() {
        val world = GeneratedWorld(
            name = "关系测试",
            characters = listOf(
                GeneratedCharacter(name = "莉"),
                GeneratedCharacter(name = "格瑞姆")
            ),
            states = listOf(
                GeneratedState(
                    name = "好感", owner = "CHARACTER_TEMPLATE", defaultValue = "10"
                )
            )
        ).toWorld("", "测试")

        val definition = world.stateDefinitions.single()
        val values = world.stateValues.filter { it.definitionId == definition.id }
        assertEquals(2, values.size)
        assertEquals(2, values.map { it.targetId }.distinct().size)
        assertTrue(values.all { it.value == "10" })
    }

    @Test
    fun specifiedCharacterStateOnlyCreatesOneValue() {
        val world = GeneratedWorld(
            name = "关系测试",
            characters = listOf(
                GeneratedCharacter(name = "莉"),
                GeneratedCharacter(name = "格瑞姆")
            ),
            states = listOf(
                GeneratedState(
                    name = "秘密怀疑度", owner = "CHARACTER",
                    ownerTargetName = "格瑞姆", defaultValue = "72", playerVisible = false
                )
            )
        ).toWorld("", "测试")

        val definition = world.stateDefinitions.single()
        assertEquals("格瑞姆", definition.ownerTargetName)
        assertEquals(1, world.stateValues.count { it.definitionId == definition.id })
        assertEquals("72", world.stateValues.single().value)
    }

    @Test
    fun detectsLikelyTokenTruncatedJson() {
        assertTrue(RpEngine.looksLikeTruncatedJson("""{"name":"未完成","scenes":["""))
        assertFalse(RpEngine.looksLikeTruncatedJson("""{"name":"完整"}"""))
    }

    @Test
    fun validatesOnlyRequiredCreationFields() {
        val valid = GeneratedWorld(
            name = "校园故事",
            summary = "转学后的校园日常",
            rules = "不替玩家决定",
            visualStyle = "现代校园",
            scenes = listOf(GeneratedScene("教室")),
            randomEventsEnabled = false,
            eventRules = emptyList()
        )
        assertTrue(RpEngine.validateGeneratedWorld(valid, "").isEmpty())

        val invalid = valid.copy(summary = "", publicBackground = "", scenes = emptyList())
        val issues = RpEngine.validateGeneratedWorld(invalid, "")
        assertTrue(issues.any { it.contains("背景") })
        assertTrue(issues.any { it.contains("场景") })
    }
}
