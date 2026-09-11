package com.miniichat.rp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpModelAnalyzerTest {
    private val cheap = RpCatalogModel(
        id = "economy-chat-8b",
        name = "Economy Chat 8B",
        description = "fast multilingual instruct JSON structured output",
        contextLength = 32_000,
        promptPricePerToken = 0.10 / 1_000_000,
        completionPricePerToken = 0.20 / 1_000_000,
        supportsStructuredOutput = true
    )
    private val balanced = RpCatalogModel(
        id = "balanced-roleplay-32b",
        name = "Balanced Roleplay 32B",
        description = "Chinese creative writing storytelling roleplay instruct",
        contextLength = 128_000,
        promptPricePerToken = 1.0 / 1_000_000,
        completionPricePerToken = 2.0 / 1_000_000,
        supportsStructuredOutput = false
    )
    private val flagship = RpCatalogModel(
        id = "frontier-flagship-reasoning-72b",
        name = "Frontier Flagship 72B",
        description = "most capable Chinese creative writing storytelling reasoning analysis instruct",
        contextLength = 256_000,
        promptPricePerToken = 10.0 / 1_000_000,
        completionPricePerToken = 20.0 / 1_000_000,
        supportedParameters = listOf("reasoning", "response_format"),
        supportsStructuredOutput = true
    )

    @Test
    fun threeStrategiesUseDifferentObjectivesWhenPoolHasRealDifferences() {
        val result = RpModelAnalyzer.analyze(listOf(cheap, balanced, flagship))

        assertEquals("economy-chat-8b", result.default(RpModelProfile.SAVER, RpModelTask.CHARACTER))
        assertEquals("balanced-roleplay-32b", result.default(RpModelProfile.BALANCED, RpModelTask.CHARACTER))
        assertEquals("frontier-flagship-reasoning-72b", result.default(RpModelProfile.HIGH_QUALITY, RpModelTask.CHARACTER))
        assertFalse(RpModelAnalyzer.profilesEquivalent(result))
    }

    @Test
    fun singleModelMayBeSharedAndReturnsDifferentiationNotice() {
        val result = RpModelAnalyzer.analyze(listOf(cheap))

        assertTrue(RpModelAnalyzer.profilesEquivalent(result))
        assertTrue(RpModelAnalyzer.differentiationNotice(listOf(cheap), result).orEmpty().contains("模型较少"))
    }

    @Test
    fun unknownPriceIsNotTreatedAsFreeInSaverMode() {
        val unknown = RpCatalogModel(id = "mystery-model", name = "Mystery Model")
        val result = RpModelAnalyzer.analyze(listOf(unknown, cheap))

        assertEquals("economy-chat-8b", result.default(RpModelProfile.SAVER, RpModelTask.STATE))
    }

    @Test
    fun responsibilitiesAreScoredIndependently() {
        val result = RpModelAnalyzer.analyze(listOf(cheap, balanced, flagship))

        assertNotEquals(
            result.default(RpModelProfile.BALANCED, RpModelTask.CHARACTER),
            result.default(RpModelProfile.BALANCED, RpModelTask.STATE)
        )
    }

    @Test
    fun qwenFamilyWithDifferentSizesIsNotMechanicallyCopiedAcrossAllProfiles() {
        val models = listOf(
            RpCatalogModel("Qwen/Qwen3-8B", "Qwen3 8B", contextLength = 32_000),
            RpCatalogModel("Qwen/Qwen3-32B", "Qwen3 32B", contextLength = 128_000),
            RpCatalogModel("Qwen/Qwen3-72B", "Qwen3 72B", contextLength = 256_000)
        )
        val result = RpModelAnalyzer.analyze(models)

        assertFalse(RpModelAnalyzer.profilesEquivalent(result))
    }

    @Test
    fun creationIsAnIndependentModelResponsibility() {
        val result = RpModelAnalyzer.analyze(listOf(cheap, balanced, flagship))

        assertEquals("economy-chat-8b", result.default(RpModelProfile.SAVER, RpModelTask.CREATION))
        assertEquals(
            "frontier-flagship-reasoning-72b",
            result.default(RpModelProfile.HIGH_QUALITY, RpModelTask.CREATION)
        )
    }

    @Test
    fun successfulCreationHistoryIsRecordedAsARecommendationSignal() {
        val reliability = mapOf(
            cheap.id to RpModelReliability(creationSuccessCount = 4),
            flagship.id to RpModelReliability(creationFormatFailureCount = 4)
        )
        val result = RpModelAnalyzer.analyze(listOf(cheap, flagship), reliability)

        assertTrue(result.getValue(RpModelProfile.BALANCED).containsKey(RpModelTask.CREATION))
        assertTrue(RpModelAnalyzer.labels(cheap, RpModelTask.CREATION).isNotEmpty())
    }
}

private fun Map<RpModelProfile, Map<RpModelTask, RpTaskModels>>.default(
    profile: RpModelProfile,
    task: RpModelTask
): String = getValue(profile).getValue(task).defaultModel
