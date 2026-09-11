package com.miniichat.rp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GeneratedLocation(
    val name: String,
    val description: String = "",
    val type: String = "",
    val background: String = "",
    val currentStatus: String = "",
    val connections: List<String> = emptyList(),
    val discovered: Boolean = true,
    val enterable: Boolean = true
)

@Serializable
data class GeneratedCharacter(
    val name: String,
    val knownIdentity: String = "",
    val knownDescription: String = "",
    val privateProfile: String = "",
    val species: String = "",
    val gender: String = "",
    val age: String = "",
    val hair: String = "",
    val appearance: String = "",
    val clothing: String = "",
    val initialLocation: String = "",
    val mood: String = "平静",
    val hasAppeared: Boolean = false
)

@Serializable
data class GeneratedState(
    val name: String,
    val icon: String = "•",
    val type: String = "TEXT",
    val owner: String = "WORLD",
    val ownerTargetName: String = "",
    val playerVisible: Boolean = true,
    val defaultValue: String = "",
    val options: List<String> = emptyList(),
    val maxValue: Double = 100.0
)

@Serializable
data class GeneratedScene(
    val name: String,
    val description: String = "",
    val currentStatus: String = "",
    val playerVisible: Boolean = true
)

@Serializable
data class GeneratedStoryArc(
    val name: String,
    val description: String = "",
    val active: Boolean = false
)

@Serializable
data class GeneratedStoryEvent(
    val name: String,
    val description: String = "",
    val triggerCondition: String = "",
    val characters: List<String> = emptyList(),
    val scenes: List<String> = emptyList(),
    val prerequisites: List<String> = emptyList()
)

@Serializable
data class GeneratedEventRule(
    val name: String,
    val description: String,
    val frequencyHint: String = "偶尔"
)

@Serializable
data class GeneratedWorld(
    val name: String = "未命名 RP",
    val projectMode: String = "HYBRID",
    val locationsEnabled: Boolean = true,
    val timelineEnabled: Boolean = true,
    val randomEventsEnabled: Boolean = true,
    val storyEnabled: Boolean = false,
    val npcSimulationEnabled: Boolean = true,
    val summary: String = "",
    val publicBackground: String = "",
    val privateBackground: String = "",
    val rules: String = "",
    val visualStyle: String = "",
    val timeRules: String = "",
    val initialTimeMinutes: Long = 480,
    val weather: String = "",
    val initialLocation: String = "",
    val locations: List<GeneratedLocation> = emptyList(),
    val scenes: List<GeneratedScene> = emptyList(),
    val currentScene: String = "",
    val storyArcs: List<GeneratedStoryArc> = emptyList(),
    val storyEvents: List<GeneratedStoryEvent> = emptyList(),
    val characters: List<GeneratedCharacter> = emptyList(),
    val states: List<GeneratedState> = emptyList(),
    val eventRules: List<GeneratedEventRule> = emptyList(),
    val openingNarration: String = ""
)

@Serializable
data class SceneCharacterUpdate(
    val name: String,
    val hasAppeared: Boolean? = null,
    val knownIdentity: String? = null,
    val knownDescription: String? = null,
    val privateProfile: String? = null,
    val location: String? = null,
    val action: String? = null,
    val goal: String? = null,
    val plan: String? = null,
    val mood: String? = null,
    val relationToUser: String? = null
)

@Serializable
data class SceneStateUpdate(
    val stateName: String,
    val targetType: String,
    val targetName: String = "",
    val value: String
)

@Serializable
data class SceneEvent(
    val title: String,
    val summary: String,
    val location: String = "",
    val knownToPlayer: Boolean = false,
    val major: Boolean = false,
    val completed: Boolean = false
)

@Serializable
data class SceneResult(
    val narration: String = "",
    val timeAdvanceMinutes: Long = 15,
    val weather: String? = null,
    val currentLocation: String? = null,
    val currentScene: String? = null,
    val presentCharacters: List<String> = emptyList(),
    val newLocations: List<GeneratedLocation> = emptyList(),
    val newScenes: List<GeneratedScene> = emptyList(),
    val newCharacters: List<GeneratedCharacter> = emptyList(),
    val characterUpdates: List<SceneCharacterUpdate> = emptyList(),
    val stateUpdates: List<SceneStateUpdate> = emptyList(),
    val events: List<SceneEvent> = emptyList(),
    val activeStoryArc: String? = null,
    val completedStoryEvents: List<String> = emptyList(),
    val majorEventCompleted: Boolean = false
)

@Serializable
data class SceneStateResult(val stateUpdates: List<SceneStateUpdate> = emptyList())

@Serializable
data class NarrationResult(val narration: String = "")

@Serializable
data class DialogueResult(
    val reply: String = "",
    val mood: String? = null,
    val action: String? = null,
    val goal: String? = null,
    val relationToUser: String? = null,
    val stateUpdates: List<SceneStateUpdate> = emptyList(),
    val memoryCandidates: List<String> = emptyList()
)

@Serializable
data class DialogueSummaryResult(
    val summary: String = "",
    val importantMemories: List<String> = emptyList(),
    val updatedUnderstandingOfUser: String? = null,
    val stateUpdates: List<SceneStateUpdate> = emptyList()
)

@Serializable
data class RuleIssue(val issue: String, val correctFact: String)

@Serializable
data class RuleCheckResult(val issues: List<RuleIssue> = emptyList())

object RpEngine {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
    }

    fun worldGenerationPrompt(nameHint: String, idea: String): Pair<String, String> {
        val system = """
            你是通用 RP 剧本/项目设计器。根据用户的一句话构想生成一个可直接开始、且所有内容可编辑的 RP 项目。
            先判断适合 SANDBOX（自由沙盒）、STORY（重剧情）或 HYBRID（混合）。不得套用固定小镇、地图、时代、NPC 数量或固定玩法。
            地图、时间线、随机事件、剧情阶段和 NPC 自主模拟都是可选模块。纯剧情或封闭场景 RP 可以 locationsEnabled=false 且 locations=[]，绝不能为了满足格式强行造地图。
            scenes 是叙事场景，不依赖地点；STORY/HYBRID 可生成可编辑的 storyArcs 和 storyEvents。剧情事件只维护后台结构，玩家仍用自然语言行动。
            若启用地点，地点是独立实体且 connections 使用地点名称；允许未发现地点。角色只写必要人物或线索，不泄露玩家不应知道的秘密。
            状态类型只能是 NUMBER/TEXT/BOOLEAN/OPTION/COUNTER/PROGRESS。所属支持 WORLD（RP 全局）、USER、CHARACTER（指定角色）、CHARACTER_TEMPLATE（每个角色独立实例）、LOCATION、SCENE、CUSTOM；指定对象写 ownerTargetName。
            随机事件模块和事件规则都可以关闭/为空。剧情优先级必须服从用户描述，AI 不得替玩家做重大选择。
            第一次只生成可立即开始所需的紧凑内容：通常 1-5 个必要人物、2-8 个状态；启用地图时通常 3-8 个地点；启用剧情时通常 1-3 个阶段和 2-6 个初始剧情事件。不要生成百科、几十个 NPC、长期完整剧情或大量未使用地点。
            可选模块关闭时，对应字段可以省略或返回空数组。必须优先保证 JSON 完整闭合，不能为追求数量截断输出。
            只输出一个 JSON 对象，不要 Markdown，不要解释。字段严格如下：
            {"name":"","projectMode":"HYBRID","locationsEnabled":true,"timelineEnabled":true,"randomEventsEnabled":true,"storyEnabled":true,"npcSimulationEnabled":true,"summary":"","publicBackground":"","privateBackground":"","rules":"","visualStyle":"","timeRules":"","initialTimeMinutes":480,"weather":"","initialLocation":"","locations":[{"name":"","description":"","type":"","background":"","currentStatus":"","connections":[],"discovered":true,"enterable":true}],"scenes":[{"name":"","description":"","currentStatus":"","playerVisible":true}],"currentScene":"","storyArcs":[{"name":"","description":"","active":true}],"storyEvents":[{"name":"","description":"","triggerCondition":"","characters":[],"scenes":[],"prerequisites":[]}],"characters":[{"name":"","knownIdentity":"","knownDescription":"","privateProfile":"","species":"","gender":"","age":"","hair":"","appearance":"","clothing":"","initialLocation":"","mood":"平静","hasAppeared":false}],"states":[{"name":"","icon":"","type":"TEXT","owner":"WORLD","ownerTargetName":"","playerVisible":true,"defaultValue":"","options":[],"maxValue":100}],"eventRules":[{"name":"","description":"","frequencyHint":"偶尔"}],"openingNarration":""}
        """.trimIndent()
        val user = "RP 名称提示：${nameHint.ifBlank { "由你生成" }}\n用户想玩的 RP：$idea"
        return system to user
    }

    fun scenePrompt(world: RpWorld, action: String, expandUserPlot: Boolean): Pair<String, String> {
        val visibleHistory = world.sceneHistory.takeLast(14).joinToString("\n") {
            "${if (it.role == "user") "user" else "旁白"}: ${it.text}"
        }
        val locations = if (world.modules.locationsEnabled) world.locations.joinToString("\n") { location ->
            val connections = location.connectedLocationIds.mapNotNull { id ->
                world.locations.firstOrNull { it.id == id }?.name
            }
            "- ${location.name} | ${location.type} | ${location.description} | 连接:${connections.joinToString()} | 已发现:${location.discovered} | 可进入:${location.enterable}"
        } else "（当前 RP 未启用地点/地图模块）"
        val scenes = world.scenes.joinToString("\n") { scene ->
            "- ${scene.name} | ${scene.description} | 状态:${scene.currentStatus} | 玩家可见:${scene.playerVisible}"
        }.ifBlank { "（未定义固定场景，按当前剧情自然描述）" }
        val story = if (world.modules.storyEnabled) {
            val arcs = world.storyArcs.sortedBy { it.order }.joinToString("；") {
                "${it.name}[进行中=${it.active},完成=${it.completed}]"
            }
            val nodes = world.storyEvents.joinToString("\n") {
                "- ${it.name} | 条件:${it.triggerCondition} | 完成:${it.completed} | 结果:${it.result}"
            }
            "剧情阶段:$arcs\n剧情事件:\n${nodes.ifBlank { "（暂无）" }}"
        } else "（当前 RP 未启用剧情阶段模块）"
        val characters = world.characters.joinToString("\n") { character ->
            "- ${character.name} | 已出场:${character.hasAppeared} | 地点:${world.locationName(character.currentLocationId)} | 行为:${character.currentAction} | 目标:${character.currentGoal} | 计划:${character.currentPlan} | 心情:${character.mood} | 对user:${character.relationToUser} | 私有:${character.privateProfile} | 记忆:${character.memories.takeLast(8).joinToString { it.summary }}"
        }
        val states = world.stateValues.joinToString("\n") { value ->
            val definition = world.stateDefinitions.firstOrNull { it.id == value.definitionId }
            "- ${definition?.name}:${value.value} owner=${definition?.owner} target=${value.targetId} visible=${definition?.playerVisible}"
        }
        val events = world.recentEvents.takeLast(15).joinToString("\n") {
            "- ${it.title}:${it.summary} 玩家知道=${it.knownToPlayer} 已结束=${it.completed}"
        }
        val corrections = world.ruleCorrections.takeLast(15).joinToString("\n") {
            "- 正确事实:${it.correctFact}（曾发现:${it.issue}）"
        }
        val eventRules = world.eventRules.filter { it.enabled }.joinToString("\n") {
            "- ${it.name}:${it.description} 频率:${it.frequencyHint}"
        }
        val expansionRule = if (expandUserPlot) {
            "补全用户写下剧情的自然过程、环境、动作和合理对话；不得改变用户已经写明的结果、拒绝、接受或方向。"
        } else {
            "响应用户行动并推进世界。不要擅自替 user 做新的关键决定。"
        }
        val system = """
            你是一个持续运行的通用 RP 项目引擎，负责场景、剧情与 NPC，不得控制 user。
            $expansionRule
            ${if (world.modules.npcSimulationEnabled) "NPC 有独立行动、目标、计划和关系；不得为了剧情强行出现在 user 身边。" else "只推进当前剧情需要的人物，不做无关的后台 NPC 自主模拟。"}
            user 不在场且没有消息渠道时，旁白不能泄露隐藏事件。${if (world.modules.randomEventsEnabled) "随机事件可以不发生。" else "当前未启用随机事件，不得自行制造无关随机事件。"}
            ${if (world.modules.locationsEnabled) "新地点必须与已有背景、地图和相邻地点一致；创建后永久保存。" else "当前未启用地图；不得因为格式创建 newLocations 或强迫玩家移动。"}
            新人物只有真正进入剧情时才将 hasAppeared 设为 true。剧情事件只提供方向，不得把 RP 变成固定选项，也不得替 user 决定。
            RP 项目名称:${world.name}
            RP 类型:${world.projectMode}
            公开背景:${world.publicBackground}
            隐藏背景:${world.privateBackground}
            RP 规则:${world.rules}
            视觉风格:${world.visualStyle}
            ${if (world.modules.timelineEnabled) "时间规则:${world.timeRules}\n当前时间:${world.worldTimeLabel()} 天气:${world.weather}" else "（当前 RP 未启用时间线，不要强制推进日期或天气）"}
            当前场景:${world.sceneName()}
            场景:\n$scenes
            地点/地图:\n$locations
            $story
            人物内部状态:\n$characters
            自定义状态:\n$states
            可用事件规则:\n$eventRules
            最近事件:\n$events
            一致性修正:\n$corrections
            最近玩家可见剧情:\n$visibleHistory
            只输出一个 JSON 对象，不要 Markdown：
            {"narration":"只写玩家此刻可以感知的旁白，不替玩家决定","timeAdvanceMinutes":15,"weather":null,"currentLocation":null,"currentScene":null,"presentCharacters":[],"newLocations":[],"newScenes":[],"newCharacters":[],"characterUpdates":[],"stateUpdates":[],"events":[],"activeStoryArc":null,"completedStoryEvents":[],"majorEventCompleted":false}
            newLocations 与世界生成时地点结构相同。newCharacters 与世界生成时角色结构相同。
            characterUpdates 字段为 name/hasAppeared/knownIdentity/knownDescription/privateProfile/location/action/goal/plan/mood/relationToUser。
            stateUpdates 字段为 stateName/targetType/targetName/value。events 字段为 title/summary/location/knownToPlayer/major/completed。
        """.trimIndent()
        return system to action
    }

    fun dialoguePrompt(world: RpWorld, character: RpCharacter, messages: List<RpDialogueMessage>): Pair<String, String> {
        val history = messages.dropLast(1).takeLast(16).joinToString("\n") { "${it.role}:${it.text}" }
        val latest = messages.lastOrNull()?.text.orEmpty()
        val system = """
            你扮演 RP 项目中的角色“${character.name}”，只能决定该角色，不得替 user 决定行动、态度、感情、接受或拒绝。
            RP:${world.name}；背景:${world.publicBackground}；规则:${world.rules}
            ${if (world.modules.timelineEnabled) "当前时间:${world.worldTimeLabel()}；" else ""}场景:${world.sceneName()}${if (world.modules.locationsEnabled) "；地点:${world.locationName(character.currentLocationId)}" else ""}
            玩家已知身份:${character.knownIdentity}；玩家已知简介:${character.knownDescription}
            角色私有设定:${character.privateProfile}；心情:${character.mood}；当前行为:${character.currentAction}；目标:${character.currentGoal}；计划:${character.currentPlan}；与user关系:${character.relationToUser}
            长期记忆:${character.memories.takeLast(12).joinToString { it.summary }}
            对话摘要:${character.conversationSummaries.takeLast(8).joinToString()}
            当前会话:\n$history
            只输出 JSON，不要 Markdown：
            {"reply":"角色的语言与动作，不操控user","mood":null,"action":null,"goal":null,"relationToUser":null,"stateUpdates":[],"memoryCandidates":[]}
        """.trimIndent()
        return system to latest
    }

    fun statePrompt(world: RpWorld, action: String, scene: SceneResult): Pair<String, String> {
        val definitions = world.stateDefinitions.joinToString("\n") { definition ->
            "- ${definition.name} type=${definition.type} owner=${definition.owner} options=${definition.options} max=${definition.maxValue}"
        }
        val values = world.stateValues.joinToString("\n") { value ->
            val definition = world.stateDefinitions.firstOrNull { it.id == value.definitionId }
            "- ${definition?.name}:${value.value} target=${value.targetId}"
        }
        val system = """
            你只负责 RP 项目的自定义状态更新。不得增加未定义状态，不得改变用户明确写下的结果。
            RP 规则:${world.rules}
            已定义状态:\n$definitions
            当前值:\n$values
            只输出 JSON，不要 Markdown：{"stateUpdates":[{"stateName":"","targetType":"","targetName":"","value":""}]}
            没有需要变化的状态时返回空数组。
        """.trimIndent()
        val user = "用户行动:$action\nRP 推进结果:${json.encodeToString(SceneResult.serializer(), scene.copy(stateUpdates = emptyList(), narration = ""))}"
        return system to user
    }

    fun narrationPrompt(world: RpWorld, action: String, scene: SceneResult, expand: Boolean): Pair<String, String> {
        val rule = if (expand) {
            "可以补全自然过程，但绝不改变用户已经写明的结果、拒绝、接受或方向。"
        } else {
            "只描述用户当下能感知的场景，不替用户做关键决定。"
        }
        val system = """
            你只负责把已经确定的 RP 项目推进结果写成简洁自然的中文场景旁白。
            $rule
            不得泄露 knownToPlayer=false 的事件，不得添加会改变结构化结果的新事实。
            RP:${world.name}；视觉风格:${world.visualStyle}；规则:${world.rules}
            只输出 JSON，不要 Markdown：{"narration":""}
        """.trimIndent()
        val user = "用户行动:$action\n已确定结果:${json.encodeToString(SceneResult.serializer(), scene.copy(narration = ""))}"
        return system to user
    }

    fun dialogueSummaryPrompt(world: RpWorld, character: RpCharacter, dialogue: RpDialogue): Pair<String, String> {
        // Complete history remains stored for the user; only the recent window is summarized for AI.
        val transcript = dialogue.messages.takeLast(24).joinToString("\n") { "${it.role}:${it.text}" }
        val system = """
            为角色“${character.name}”结束的会话整理长期记忆。不要保存全部原文，只保存重要事实、承诺、欺骗、帮助、冲突和角色对 user 的新认知。
            RP 规则:${world.rules}
            只输出 JSON：{"summary":"简短对话摘要","importantMemories":[],"updatedUnderstandingOfUser":null,"stateUpdates":[]}
        """.trimIndent()
        return system to transcript
    }

    fun ruleCheckPrompt(world: RpWorld): Pair<String, String> {
        val facts = world.recentEvents.takeLast(20).joinToString("\n") { "${it.title}:${it.summary}" }
        val people = world.characters.joinToString("\n") {
            "${it.name}: ${it.privateProfile};地点=${world.locationName(it.currentLocationId)};记忆=${it.memories.takeLast(10).joinToString { memory -> memory.summary }}"
        }
        val system = """
            你是后台 RP 一致性检测器，只在大事件结束后运行。检查性格跑偏、事实冲突、时间线矛盾、越权知识、关系变化缺少来源、剧情重复、结果丢失，以及是否严重偏离用户定义的主题。
            不重写历史，只输出供后续遵守的正确事实。只输出 JSON：{"issues":[{"issue":"","correctFact":""}]}
            RP 背景:${world.publicBackground}\n${world.privateBackground}
            用户最终规则:${world.rules}
            ${if (world.modules.locationsEnabled) "地图:${world.locations.joinToString { it.name + "->" + it.connectedLocationIds.joinToString() }}" else "地图模块未启用"}
            剧情阶段:${world.storyArcs.sortedBy { it.order }.joinToString { it.name + "[active=" + it.active + ",done=" + it.completed + "]" }}
            人物:$people
            最近事件:$facts
        """.trimIndent()
        return system to "检查最近已结束的大事件，并只记录确有必要的修正。"
    }

    /**
     * Parses an object returned by an OpenAI-compatible model without ever treating the
     * unparsed response as player-visible prose. Providers commonly add a Markdown fence,
     * a short preface, or a reasoning block even when prompted for JSON.
     */
    inline fun <reified T> parse(raw: String): T? = jsonObjectCandidates(raw).firstNotNullOfOrNull {
        runCatching { json.decodeFromString<T>(it) }.getOrNull()
    }

    fun parseSceneResult(raw: String): SceneResult? =
        parse<SceneResult>(raw)?.takeIf { it.narration.isNotBlank() }

    fun validateGeneratedWorld(value: GeneratedWorld, nameHint: String): List<String> = buildList {
        if (nameHint.isBlank() && value.name.isBlank()) add("缺少项目名称")
        if (value.summary.isBlank() && value.publicBackground.isBlank()) add("缺少项目背景")
        if (value.rules.isBlank()) add("缺少基础规则")
        if (value.visualStyle.isBlank()) add("缺少视觉设定")
        if (value.scenes.isEmpty() && value.locations.isEmpty() && value.openingNarration.isBlank()) {
            add("缺少初始场景")
        }
    }

    fun looksLikeTruncatedJson(raw: String): Boolean {
        val clean = raw.trim()
        if (clean.isEmpty()) return false
        if (!clean.endsWith('}')) return true
        var depth = 0
        var inString = false
        var escaped = false
        clean.forEach { char ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else when (char) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> depth--
            }
        }
        return inString || depth != 0
    }

    fun jsonObjectCandidates(raw: String): List<String> {
        val withoutThinking = raw.replace(
            Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE),
            ""
        ).trim()
        if (withoutThinking.isEmpty() || withoutThinking.equals("null", ignoreCase = true)) {
            return emptyList()
        }

        val candidates = linkedSetOf<String>()
        if (withoutThinking.startsWith('{') && withoutThinking.endsWith('}')) {
            candidates += withoutThinking
        }
        Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
            .findAll(withoutThinking)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .forEach { fenced ->
                if (fenced.startsWith('{') && fenced.endsWith('}')) candidates += fenced
                extractFirstJsonObject(fenced)?.let(candidates::add)
            }
        extractFirstJsonObject(withoutThinking)?.let(candidates::add)
        return candidates.toList()
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                    if (depth < 0) return null
                }
            }
        }
        return null
    }
}
