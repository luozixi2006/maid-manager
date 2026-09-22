package com.miniichat.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MemoryRetrievalTest {
    private val now = 1_700_000_000_000L
    private val policy = MemoryRetrieval.Policy()

    private fun memory(
        id: String, content: String, personaId: String = "p1", enabled: Boolean = true,
        status: String = "active", confidence: Double = 0.8, importance: Double = 0.6, lastConfirmedAt: Long = now
    ) = LongTermMemory(
        id = id, content = content, category = "偏好", createdAt = now - 5000, updatedAt = now - 5000,
        enabled = enabled, personaId = personaId, status = status, importance = importance, confidence = confidence, lastConfirmedAt = lastConfirmedAt)

    @Test fun chinesePreferenceWithSharedTermsIsRetrieved() {
        val picked = MemoryRetrieval.select("p1", "我喜欢喝咖啡", listOf(memory("m1", "用户喜欢咖啡")), now, policy)
        assertEquals(listOf("m1"), picked.map { it.id })
    }

    @Test fun otherPersonaDisabledSupersededConflictLowConfidenceAndIrrelevantAreExcluded() {
        val memories = listOf(
            memory("other", "用户喜欢咖啡", personaId = "p2"),
            memory("off", "用户喜欢咖啡", enabled = false),
            memory("superseded", "用户喜欢咖啡", status = "superseded"),
            memory("conflict", "用户喜欢咖啡", status = "conflict"),
            memory("lowConfidence", "用户喜欢咖啡", confidence = 0.2),
            memory("irrelevant", "量子力学很难理解"))
        assertTrue(MemoryRetrieval.select("p1", "我喜欢喝咖啡", memories, now, policy).isEmpty())
        assertTrue(MemoryRetrieval.select("p1", "我喜欢喝咖啡", listOf(memory("legacy", "用户喜欢咖啡", personaId = "")), now, policy).isEmpty())
        try {
            MemoryRetrieval.select("", "我喜欢喝咖啡", listOf(memory("m1", "用户喜欢咖啡")), now, policy)
            fail("blank persona must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test fun limitAndCharacterBudgetBoundTheSelection() {
        val memories = listOf(memory("a", "用户喜欢咖啡"), memory("b", "用户喜欢咖啡"))
        assertEquals(listOf("a"), MemoryRetrieval.select("p1", "用户喜欢咖啡", memories, now, MemoryRetrieval.Policy(limit = 1)).map { it.id })
        assertEquals(listOf("a"), MemoryRetrieval.select("p1", "用户喜欢咖啡", memories, now, MemoryRetrieval.Policy(limit = 10, charBudget = 6)).map { it.id })
        assertTrue(MemoryRetrieval.select("p1", "用户喜欢咖啡", memories, now, MemoryRetrieval.Policy(limit = 10, charBudget = 5)).isEmpty())
    }

    @Test fun cosineRejectsInvalidDimensionsAndNonFiniteValues() {
        assertEquals(0.0, MemoryRetrieval.cosine(listOf(1f, 2f), listOf(1f)), 0.0)
        assertEquals(0.0, MemoryRetrieval.cosine(listOf(1f), listOf(1f, 2f)), 0.0)
        assertEquals(0.0, MemoryRetrieval.cosine(emptyList(), emptyList()), 0.0)
        assertEquals(0.0, MemoryRetrieval.cosine(listOf(Float.NaN, 1f), listOf(1f, 1f)), 0.0)
        assertEquals(0.0, MemoryRetrieval.cosine(listOf(1f, 1f), listOf(Float.POSITIVE_INFINITY, 1f)), 0.0)
        assertEquals(0.0, MemoryRetrieval.cosine(listOf(1f, 1f), listOf(Float.NEGATIVE_INFINITY, 1f)), 0.0)
        assertEquals(1.0, MemoryRetrieval.cosine(listOf(1f, 0f), listOf(4f, 0f)), 1e-9)
    }

    @Test fun semanticScoreOnlyAppliesToTheMatchingEmbeddingModel() {
        val embedded = memory("m1", "量子力学很难理解").copy(embedding = listOf(1f, 0f), embeddingModel = "emb-1")
        val matched = MemoryRetrieval.select("p1", "完全不同的问题", listOf(embedded), now, policy,
            queryEmbedding = listOf(1f, 0f), embeddingModel = "emb-1")
        assertEquals(listOf("m1"), matched.map { it.id })
        assertTrue(MemoryRetrieval.select("p1", "完全不同的问题", listOf(embedded), now, policy,
            queryEmbedding = listOf(1f, 0f), embeddingModel = "emb-2").isEmpty())
    }
}
