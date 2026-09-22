package com.miniichat.data

import android.app.Application
import java.util.concurrent.CyclicBarrier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AssistantStoreConcurrencyTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private val alpha = Assistant(
        id = "alpha",
        name = "备注A",
        avatar = "男",
        systemPrompt = "sys-A",
        currentPersonality = "p-A",
        temperature = 0.25f
    )
    private val beta = Assistant(
        id = "beta",
        name = "备注B",
        conversationName = "Bee",
        proactiveEnabled = true,
        proactiveTiming = "fixed",
        proactiveFailureCount = 3
    )

    @Test fun updateChangesOnlyRequestedFields() = runBlocking {
        val store = AssistantStore(app)
        store.save(listOf(alpha, beta))

        store.update("alpha") { it.copy(proactiveEnabled = true, proactiveTiming = "hourly") }

        val loaded = store.snapshot()
        assertEquals(listOf("alpha", "beta"), loaded.map { it.id })
        assertEquals(
            alpha.copy(proactiveEnabled = true, proactiveTiming = "hourly"),
            loaded.single { it.id == "alpha" }
        )
        assertEquals("备注A", loaded.single { it.id == "alpha" }.name)
        assertEquals(beta, loaded.single { it.id == "beta" })
    }

    @Test fun updateDoesNotResurrectDeletedAssistant() = runBlocking {
        val store = AssistantStore(app)
        store.save(listOf(alpha, beta))
        store.delete("alpha")
        assertEquals(listOf("beta"), store.snapshot().map { it.id })

        // Neither the deleted id nor an unknown id can be re-added through update.
        store.update("alpha") { it.copy(name = "resurrect", proactiveEnabled = true) }
        store.update("missing") { it.copy(name = "nope") }

        val loaded = store.snapshot()
        assertEquals(1, loaded.size)
        assertEquals(beta, loaded.single())
        assertFalse(loaded.any { it.id == "alpha" })

        // Only an explicit upsert can bring a persona back.
        store.upsert(alpha.copy(name = "A-restored"))
        val restored = store.snapshot()
        assertEquals(2, restored.size)
        assertTrue(restored.any { it.id == "alpha" && it.name == "A-restored" })
        assertEquals(beta, restored.single { it.id == "beta" })
    }

    @Test fun concurrentUpdateAndTransformAllKeepUnrelatedEdits() = runBlocking {
        val store = AssistantStore(app)
        store.save(listOf(alpha, beta))
        val barrier = CyclicBarrier(2)

        val addGamma = async(Dispatchers.IO) {
            barrier.await()
            store.transformAll { list -> list + Assistant(id = "gamma", name = "G") }
        }
        val renameAlpha = async(Dispatchers.IO) {
            barrier.await()
            store.update("alpha") { it.copy(conversationName = "Alice") }
        }
        addGamma.await()
        renameAlpha.await()

        val loaded = store.snapshot()
        assertEquals(listOf("alpha", "beta", "gamma"), loaded.map { it.id })
        assertEquals(alpha.copy(conversationName = "Alice"), loaded.single { it.id == "alpha" })
        assertEquals(beta, loaded.single { it.id == "beta" })
        assertEquals("G", loaded.single { it.id == "gamma" }.name)
    }

    @Test fun concurrentUpdatesOfOneAssistantDoNotLoseWrites() = runBlocking {
        val store = AssistantStore(app)
        val target = Assistant(id = "target", name = "T")
        val other = Assistant(id = "other", name = "O", proactiveFailureCount = 7)
        store.save(listOf(target, other))

        val writers = 12
        val barrier = CyclicBarrier(writers)
        val jobs = (1..writers).map {
            async(Dispatchers.IO) {
                barrier.await()
                store.update("target") { it.copy(proactiveFailureCount = it.proactiveFailureCount + 1) }
            }
        }
        jobs.awaitAll()

        val loaded = store.snapshot()
        assertEquals(listOf("target", "other"), loaded.map { it.id })
        val updated = loaded.single { it.id == "target" }
        assertEquals(writers, updated.proactiveFailureCount)
        assertEquals(target, updated.copy(proactiveFailureCount = 0))
        assertEquals(other, loaded.single { it.id == "other" })
    }
}
