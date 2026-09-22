package com.miniichat.memory

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MemoryJobsTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test fun pendingJobsSurviveReopeningAndDoneIdsAreNotEnqueuedAgain() {
        MemoryDatabase(app).use { db -> db.enqueue("chat:c1:m1", "p1", "{\"conversation\":\"c1\"}") }
        MemoryDatabase(app).use { db ->
            val jobs = db.jobs(System.currentTimeMillis())
            assertEquals(listOf("chat:c1:m1"), jobs.map { it.id })
            assertEquals("p1", jobs.single().persona)
            assertEquals("{\"conversation\":\"c1\"}", jobs.single().body)
            assertEquals(0, jobs.single().attempts)

            db.done("chat:c1:m1")
            assertTrue(db.jobs(System.currentTimeMillis()).isEmpty())
            db.enqueue("chat:c1:m1", "p1", "{\"conversation\":\"c1\"}")
            assertTrue(db.jobs(System.currentTimeMillis()).isEmpty())
            assertEquals("待整理 0 条 · 需重试 0 条", db.queueStatus())
        }
        MemoryDatabase(app).use { db -> assertTrue(db.jobs(System.currentTimeMillis()).isEmpty()) }
    }

    @Test fun duplicateEnqueueKeepsOneJobAndDoneIsDurable() {
        MemoryDatabase(app).use { db ->
            db.enqueue("chat:c1:m1", "p1", "{\"conversation\":\"c1\"}")
            db.enqueue("chat:c1:m1", "p1", "{\"conversation\":\"c1\"}")
            assertEquals(1, db.jobs(System.currentTimeMillis()).size)
            db.done("chat:c1:m1")
        }
        MemoryDatabase(app).use { db ->
            assertTrue(db.jobs(System.currentTimeMillis()).isEmpty())
            db.enqueue("chat:c1:m1", "p1", "{\"conversation\":\"c1\"}")
            assertTrue(db.jobs(System.currentTimeMillis()).isEmpty())
        }
    }
}
