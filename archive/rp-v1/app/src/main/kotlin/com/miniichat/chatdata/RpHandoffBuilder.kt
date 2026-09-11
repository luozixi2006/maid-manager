package com.miniichat.chatdata

import com.miniichat.rp.RpWorld
import com.miniichat.rp.activeArcName
import com.miniichat.rp.locationName
import com.miniichat.rp.sceneName
import com.miniichat.rp.worldTimeLabel

object RpHandoffBuilder {
    fun build(
        world: RpWorld,
        mode: HandoffMode = HandoffMode.STANDARD,
        now: Long = System.currentTimeMillis()
    ): MaidManagerHandoff {
        val dialogue = world.activeDialogue
            ?: world.characterDialogues.values.maxByOrNull { it.updatedAt }
        val character = world.characters.firstOrNull { it.id == dialogue?.characterId }
        val policy = mode.policy()
        var remaining = policy.recentContextChars
        val recent = dialogue?.messages.orEmpty().asReversed().mapNotNull { message ->
            if (remaining <= 0 || message.text.isBlank()) return@mapNotNull null
            val text = if (message.text.length <= remaining) message.text else message.text.takeLast(remaining)
            remaining -= text.length
            HandoffRecentMessage(message.role, text, message.createdAt)
        }.asReversed()
        val unresolved = world.recentEvents.filterNot { it.completed }.map { it.title }
        val majorEvents = world.recentEvents.filter { it.major }.map { it.summary.ifBlank { it.title } }
        val characterMemories = character?.memories.orEmpty().map { it.summary }

        return MaidManagerHandoff(
            createdAt = now,
            mode = mode,
            sourceType = "rp",
            conversation = HandoffConversation(
                id = dialogue?.characterId.orEmpty(),
                title = listOfNotNull(world.name, character?.name).joinToString(" · "),
                lastTopic = world.activeArcName().orEmpty(),
                summary = world.summary,
                messageCount = dialogue?.messages?.size ?: world.sceneHistory.size,
                startedAt = dialogue?.startedAt ?: world.createdAt,
                updatedAt = world.updatedAt
            ),
            character = character?.let {
                HandoffCharacter(
                    name = it.name,
                    personaId = it.id,
                    systemPrompt = it.privateProfile,
                    corePersonality = listOfNotNull(it.knownIdentity, it.knownDescription).filter(String::isNotBlank),
                    currentPersonality = listOfNotNull(it.mood, it.relationToUser).filter(String::isNotBlank),
                    currentState = listOfNotNull(it.currentAction, it.currentGoal, it.currentPlan).filter(String::isNotBlank)
                )
            },
            importantMemories = characterMemories.map { HandoffMemory(it, 0.9, "RP 人物记忆") },
            currentProjects = listOf(
                HandoffProject(
                    name = world.name,
                    status = world.activeArcName() ?: "进行中",
                    nextSteps = unresolved
                )
            ),
            unresolvedQuestions = unresolved,
            openTasks = world.storyArcs.filter { it.active && !it.completed }.map { it.name },
            recentContext = recent,
            lastImportantEvents = majorEvents,
            personality = HandoffPersonalityCompatibility(
                originalPersonality = character?.knownDescription?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
                currentPersonality = listOfNotNull(character?.mood, character?.relationToUser).filter(String::isNotBlank)
            ),
            rp = RpHandoffContext(
                projectId = world.id,
                projectName = world.name,
                characterId = character?.id.orEmpty(),
                currentLocation = world.locationName(),
                currentScene = world.sceneName(),
                worldTime = world.worldTimeLabel(),
                weather = world.weather,
                publicBackground = world.publicBackground,
                worldRules = world.rules,
                relationships = character?.relationships.orEmpty(),
                characterMemories = characterMemories,
                majorEvents = majorEvents,
                unresolvedPlot = unresolved
            )
        )
    }
}
