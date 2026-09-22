package com.miniichat.memory

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Model output is data: every source, quote and id is re-validated locally before storage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MemoryLearningTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val now = System.currentTimeMillis()

    private fun evidence(id: String = "e1", kind: String = "user", text: String, at: Long = now - 1000) =
        MemoryEvidence(id, kind, at, text)

    private fun change(
        action: String = "ADD", ids: List<String> = emptyList(), content: String, category: String = "偏好",
        sourceIds: List<String> = listOf("e1"), quotes: List<String> = listOf("我喜欢猫"),
        importance: Double = 0.8, confidence: Double = 0.8, stable: Boolean = false
    ) = MemoryChange(action, ids, content, category, importance, confidence, stable, sourceIds, quotes)

    private fun apply(db: MemoryDatabase, change: MemoryChange, evidence: List<MemoryEvidence>, candidates: List<LongTermMemory> = emptyList()) =
        MemoryLearning.apply(db, "p1", MemoryPlan(listOf(change)), evidence, candidates, now)

    @Test fun invalidSecondChangeRollsBackTheWholePlan() {
        MemoryDatabase(app).use { db ->
            val valid = change(content = "喜欢猫")
            val invalid = change(content = "没有依据", sourceIds = listOf("missing"))
            assertThrows(IllegalStateException::class.java) {
                MemoryLearning.apply(db,"p1",MemoryPlan(listOf(valid,invalid)),listOf(evidence(text="我喜欢猫")),emptyList(),now)
            }
            assertTrue(db.queryAll().isEmpty())
        }
    }

    @Test fun laterClarificationCanResolveAnUncertainMemory() {
        MemoryDatabase(app).use { db ->
            val old = LongTermMemory(id="uncertain",content="猫的偏好待确认",category="偏好",personaId="p1",status="conflict")
            db.upsert(old)
            assertEquals(1,apply(db,change(action="UPDATE",ids=listOf(old.id),content="喜欢猫"),listOf(evidence(text="我喜欢猫")),listOf(old)))
            assertEquals("active",db.queryAll().single().status)
            assertEquals("喜欢猫",db.queryAll().single().content)
        }
    }

    @Test fun nonexistentSourceIsRejected() {
        MemoryDatabase(app).use { db ->
            assertThrows(IllegalStateException::class.java) {
                apply(db, change(content = "用户喜欢猫", sourceIds = listOf("missing")), listOf(evidence(text = "我喜欢猫")))
            }
            assertTrue(db.queryAll().isEmpty())
        }
    }

    @Test fun quoteThatIsNotInTheSourceIsRejected() {
        MemoryDatabase(app).use { db ->
            assertThrows(IllegalArgumentException::class.java) {
                apply(db, change(content = "用户喜欢猫", quotes = listOf("我讨厌猫")), listOf(evidence(text = "我喜欢猫")))
            }
            assertTrue(db.queryAll().isEmpty())
        }
    }

    @Test fun shortUserPreferenceIsAcceptedAndKeepsItsSource() {
        MemoryDatabase(app).use { db ->
            assertEquals(1, apply(db, change(content = "喜欢猫"), listOf(evidence(text = "我喜欢猫。"))))
            val stored = db.queryAll().single()
            assertEquals("喜欢猫", stored.content)
            assertEquals("偏好", stored.category)
            assertEquals("p1", stored.personaId)
            assertEquals("automatic", stored.origin)
            assertEquals("active", stored.status)
            assertEquals(listOf("e1"), stored.sources.map { it.id })
            assertEquals("user", stored.sources.single().kind)
            assertEquals("我喜欢猫", stored.sources.single().quote)
            assertEquals(now - 1000, stored.lastConfirmedAt)
        }
    }

    @Test fun relationshipMemoryKeepsItsEvidenceSource() {
        MemoryDatabase(app).use { db ->
            val text = "今天我们一起看了日出，很开心"
            val written = apply(db, change(content = "一起看过日出", category = "相处记忆", quotes = listOf("我们一起看了日出")),
                listOf(evidence(text = text)))
            assertEquals(1, written)
            val stored = db.queryAll().single()
            assertEquals("相处记忆", stored.category)
            assertEquals("e1", stored.sources.single().id)
            assertEquals("我们一起看了日出", stored.sources.single().quote)
        }
    }

    @Test fun modelCannotReferenceAnotherPersonaOrAnUnknownId() {
        MemoryDatabase(app).use { db ->
            val foreign = LongTermMemory(id = "foreign-1", content = "另一个人设的记忆", category = "偏好", personaId = "p2")
            db.upsert(foreign)
            assertThrows(IllegalStateException::class.java) {
                apply(db, change(action = "UPDATE", ids = listOf("foreign-1"), content = "被改写的内容"),
                    listOf(evidence(text = "我喜欢猫")), db.queryAll())
            }
            assertThrows(IllegalStateException::class.java) {
                apply(db, change(action = "UPDATE", ids = listOf("does-not-exist"), content = "被改写的内容"),
                    listOf(evidence(text = "我喜欢猫")), db.queryAll())
            }
            assertEquals(foreign, db.queryAll().single())
        }
    }

    @Test fun replayingTheSamePlanDoesNotDuplicate() {
        MemoryDatabase(app).use { db ->
            val planChange = change(content = "喜欢猫")
            assertEquals(1, apply(db, planChange, listOf(evidence(text = "我喜欢猫。"))))
            assertEquals(0, apply(db, planChange, listOf(evidence(text = "我喜欢猫。"))))
            assertEquals(1, db.queryAll().size)
            assertEquals("喜欢猫", db.queryAll().single().content)
        }
    }

    @Test fun assistantOnlyGuessIsNeverStoredAsAUserFact() {
        MemoryDatabase(app).use { db ->
            val assistant = evidence(id = "a1", kind = "assistant", text = "我想你应该喜欢猫")
            assertEquals(0, apply(db, change(content = "用户喜欢猫", sourceIds = listOf("a1"), quotes = listOf("喜欢猫")), listOf(assistant)))
            assertTrue(db.queryAll().isEmpty())
        }
    }
}
