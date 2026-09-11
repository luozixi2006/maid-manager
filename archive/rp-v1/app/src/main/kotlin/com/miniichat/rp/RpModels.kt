package com.miniichat.rp

import kotlinx.serialization.Serializable

@Serializable
enum class RpStateType { NUMBER, TEXT, BOOLEAN, OPTION, COUNTER, PROGRESS }

@Serializable
enum class RpStateOwner {
    /** Kept for old saves; product copy treats this as the RP project global scope. */
    WORLD,
    USER,
    CHARACTER,
    CHARACTER_TEMPLATE,
    LOCATION,
    SCENE,
    CUSTOM
}

@Serializable
enum class RpProjectMode { SANDBOX, STORY, HYBRID }

/** Optional capabilities of an RP project. Defaults preserve V1 world saves. */
@Serializable
data class RpProjectModules(
    val locationsEnabled: Boolean = true,
    val timelineEnabled: Boolean = true,
    val randomEventsEnabled: Boolean = true,
    val storyEnabled: Boolean = false,
    val npcSimulationEnabled: Boolean = true
)

@Serializable
data class RpStateDefinition(
    val id: String,
    val name: String,
    val icon: String = "•",
    val type: RpStateType = RpStateType.TEXT,
    val owner: RpStateOwner = RpStateOwner.WORLD,
    /** Stable entity id for a character/location/scene; blank keeps legacy/template behavior. */
    val ownerTargetId: String = "",
    val ownerTargetName: String = "",
    val playerVisible: Boolean = true,
    val defaultValue: String = "",
    val options: List<String> = emptyList(),
    val maxValue: Double = 100.0
)

@Serializable
data class RpScene(
    val id: String,
    val name: String,
    val description: String = "",
    val currentStatus: String = "",
    val playerVisible: Boolean = true
)

@Serializable
data class RpStoryArc(
    val id: String,
    val name: String,
    val description: String = "",
    val order: Int = 0,
    val active: Boolean = false,
    val completed: Boolean = false
)

@Serializable
data class RpStoryEvent(
    val id: String,
    val name: String,
    val description: String = "",
    val triggerCondition: String = "",
    val characterIds: List<String> = emptyList(),
    val sceneIds: List<String> = emptyList(),
    val prerequisiteEventIds: List<String> = emptyList(),
    val completed: Boolean = false,
    val result: String = "",
    val aftermath: String = ""
)

@Serializable
data class RpStateValue(
    val definitionId: String,
    val targetId: String,
    val value: String
)

@Serializable
data class RpLocation(
    val id: String,
    val name: String,
    val description: String = "",
    val type: String = "",
    val background: String = "",
    val currentStatus: String = "",
    val connectedLocationIds: List<String> = emptyList(),
    val discovered: Boolean = true,
    val enterable: Boolean = true
)

@Serializable
data class RpMemory(
    val id: String,
    val summary: String,
    val importance: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class RpCharacter(
    val id: String,
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
    val avatarPath: String? = null,
    val portraitPrompt: String = "",
    val hasAppeared: Boolean = false,
    val currentLocationId: String = "",
    val currentAction: String = "",
    val currentGoal: String = "",
    val currentPlan: String = "",
    val mood: String = "平静",
    val relationToUser: String = "陌生",
    val relationships: Map<String, String> = emptyMap(),
    val memories: List<RpMemory> = emptyList(),
    val conversationSummaries: List<String> = emptyList(),
    val firstAppearedAt: Long? = null,
    val proactiveEnabled: Boolean = true,
    val lastProactiveMessageAt: Long = 0L,
    val nextProactiveCheckAt: Long = 0L,
    val proactiveMessageSummaries: List<String> = emptyList(),
    val proactiveFailureCount: Int = 0
)

@Serializable
data class RpEventRule(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean = true,
    val frequencyHint: String = "偶尔"
)

@Serializable
data class RpSceneEntry(
    val id: String,
    val role: String,
    val text: String,
    val worldMinute: Long,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class RpDialogueMessage(
    val id: String,
    val role: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val worldId: String = "",
    val characterId: String = "",
    val messageType: String = "text",
    val isProactive: Boolean = false,
    val sequence: Long = 0L
)

@Serializable
data class RpDialogue(
    val characterId: String,
    val messages: List<RpDialogueMessage> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sessionStartIndex: Int = 0
)

@Serializable
data class RpWorldEvent(
    val id: String,
    val title: String,
    val summary: String,
    val locationId: String = "",
    val worldMinute: Long,
    val knownToPlayer: Boolean = false,
    val major: Boolean = false,
    val completed: Boolean = false,
    val ruleChecked: Boolean = false
)

@Serializable
data class RpRuleCorrection(
    val id: String,
    val issue: String,
    val correctFact: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class RpWorld(
    val id: String,
    val name: String,
    val idea: String = "",
    val summary: String = "",
    val publicBackground: String = "",
    val privateBackground: String = "",
    val rules: String = "",
    val visualStyle: String = "",
    val projectMode: RpProjectMode = RpProjectMode.HYBRID,
    val modules: RpProjectModules = RpProjectModules(),
    val timeRules: String = "",
    val worldMinute: Long = 8 * 60L,
    val weather: String = "",
    val currentLocationId: String = "",
    val locations: List<RpLocation> = emptyList(),
    val currentSceneId: String = "",
    val scenes: List<RpScene> = emptyList(),
    val storyArcs: List<RpStoryArc> = emptyList(),
    val storyEvents: List<RpStoryEvent> = emptyList(),
    val characters: List<RpCharacter> = emptyList(),
    val presentCharacterIds: List<String> = emptyList(),
    val stateDefinitions: List<RpStateDefinition> = emptyList(),
    val stateValues: List<RpStateValue> = emptyList(),
    val eventRules: List<RpEventRule> = emptyList(),
    val sceneHistory: List<RpSceneEntry> = emptyList(),
    /** Complete user-visible histories, keyed by the stable character id within this world. */
    val characterDialogues: Map<String, RpDialogue> = emptyMap(),
    val activeDialogue: RpDialogue? = null,
    val recentEvents: List<RpWorldEvent> = emptyList(),
    val ruleCorrections: List<RpRuleCorrection> = emptyList(),
    val portraitGenerationEnabled: Boolean = true,
    val proactiveMessagesEnabled: Boolean = false,
    val setupComplete: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

fun RpWorld.locationName(id: String = currentLocationId): String =
    locations.firstOrNull { it.id == id }?.let { if (it.discovered) it.name else "？？？" }
        ?: "未设置地点"

fun RpWorld.sceneName(id: String = currentSceneId): String =
    scenes.firstOrNull { it.id == id }?.name
        ?: locations.firstOrNull { it.id == currentLocationId }?.name
        ?: "当前场景"

fun RpWorld.activeArcName(): String? = storyArcs
    .sortedBy { it.order }
    .firstOrNull { it.active && !it.completed }?.name

fun RpWorld.worldTimeLabel(): String {
    val day = worldMinute / (24 * 60) + 1
    val minuteOfDay = (worldMinute % (24 * 60)).toInt()
    return "第${day}天 %02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}

data class RpPortraitProvider(
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
    val customHeaders: Map<String, String> = emptyMap(),
    val useUnifiedService: Boolean = false,
    val width: Int = 768,
    val height: Int = 768,
    val steps: Int = 4,
    val guidanceScale: Float = 1.0f,
    val timeoutSeconds: Int = 600
)

/** Vendor-neutral adapter for an OpenAI-compatible or future image generation service. */
interface RpPortraitGenerator {
    suspend fun generateFixedPortrait(
        provider: RpPortraitProvider,
        prompt: String,
        outputPath: String
    ): String

    fun close() = Unit
}
