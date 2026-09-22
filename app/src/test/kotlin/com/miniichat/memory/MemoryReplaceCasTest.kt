package com.miniichat.memory

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MemoryReplaceCasTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val now = 1_700_000_000_000L

    private fun memory(id: String, content: String, personaId: String, revision: Int = 1) =
        LongTermMemory(id = id, content = content, category = "偏好", createdAt = 1, updatedAt = 2, personaId = personaId, revision = revision)

    private fun replacement(id: String) =
        LongTermMemory(id = id, content = "合并后的事实", category = "偏好", createdAt = 1, updatedAt = now, personaId = "p1")

    @Test fun staleRevisionAndCrossPersonaExpectedIdsAreRejectedWithoutTouchingRecords() {
        MemoryDatabase(app).use { db ->
            val old = memory("old-1", "用户喜欢咖啡", "p1")
            val disabled = memory("off-1", "用户喜欢喝茶", "p1").copy(enabled = false)
            val supersededRow = memory("dead-1", "用户喜欢跑步", "p1").copy(status = "superseded")
            val foreign = memory("p2-1", "另一个人设的记忆", "p2")
            db.upsertAll(listOf(old, disabled, supersededRow, foreign))

            // stale revision / not enabled / not active / other persona all fail the compare-and-swap
            assertFalse(db.replace("p1", listOf(old.copy(revision = 99)), replacement("new-1")))
            assertFalse(db.replace("p1", listOf(disabled), replacement("new-2")))
            assertFalse(db.replace("p1", listOf(supersededRow), replacement("new-3")))
            assertFalse(db.replace("p1", listOf(foreign), replacement("new-4")))
            // replacement may not claim an id that already belongs to another record/persona
            assertFalse(db.replace("p1", listOf(old), replacement("p2-1")))
            assertThrows(IllegalArgumentException::class.java) { db.replace("p1", listOf(old), replacement("new-5").copy(personaId = "p2")) }
            assertThrows(IllegalArgumentException::class.java) { db.replace("", emptyList(), replacement("new-6")) }

            val after = db.queryAll().associateBy { it.id }
            assertEquals(4, after.size)
            val untouched = after.getValue("old-1")
            assertEquals("用户喜欢咖啡", untouched.content)
            assertEquals(1, untouched.revision)
            assertEquals("active", untouched.status)
            assertTrue(untouched.enabled)
            assertEquals(disabled, after.getValue("off-1"))
            assertEquals(supersededRow, after.getValue("dead-1"))
            assertEquals(foreign, after.getValue("p2-1"))
        }
    }

    @Test fun validUpdateAndMergeSupersedeOldRecords() {
        MemoryDatabase(app).use { db ->
            val old = memory("old-1", "用户喜欢咖啡", "p1")
            db.upsert(old)
            val updated = old.copy(content = "用户喜欢手冲咖啡", updatedAt = now, revision = 2, supersedes = listOf("old-1"))
            assertTrue(db.replace("p1", listOf(old), updated))
            val afterUpdate = db.queryAll().single { it.id == "old-1" }
            assertEquals("用户喜欢手冲咖啡", afterUpdate.content)
            assertEquals(2, afterUpdate.revision)
            assertEquals("active", afterUpdate.status)

            val first = memory("m-1", "用户喜欢跑步", "p1")
            val second = memory("m-2", "用户每天跑步", "p1")
            db.upsertAll(listOf(first, second))
            assertTrue(db.replace("p1", listOf(first, second), replacement("merged-1").copy(supersedes = listOf("m-1", "m-2"))))

            val afterMerge = db.queryAll().associateBy { it.id }
            assertEquals("superseded", afterMerge.getValue("m-1").status)
            assertEquals("superseded", afterMerge.getValue("m-2").status)
            assertEquals(2, afterMerge.getValue("m-1").revision)
            assertEquals(2, afterMerge.getValue("m-2").revision)
            assertEquals("active", afterMerge.getValue("merged-1").status)
            assertEquals("p1", afterMerge.getValue("merged-1").personaId)
        }
    }
}
