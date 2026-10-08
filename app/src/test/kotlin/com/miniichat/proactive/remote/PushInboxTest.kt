package com.miniichat.proactive.remote

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PushInboxTest {
    @Test fun acknowledgeOnlyCommittedAndKeepDeviceIsolation() {
        val inbox = newInbox()
        inbox.offer(device, envelope(), System.currentTimeMillis())
        inbox.acknowledged(device, "m1")
        assertTrue(inbox.awaitingAck(device).isEmpty())
        inbox.finish(device, "m1")
        assertEquals(listOf("m1"), inbox.awaitingAck(device))
        inbox.acknowledged(otherDevice, "m1")
        assertEquals(listOf("m1"), inbox.awaitingAck(device))
        inbox.acknowledged(device, "m1")
        assertTrue(inbox.awaitingAck(device).isEmpty())
        assertTrue(inbox.finished(device, "m1"))
    }

    private val device = "device-01_AB"
    private val otherDevice = "device-02_CD"
    private val opened = ArrayList<PushInbox>()

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        opened.clear()
        RuntimeEnvironment.getApplication().deleteDatabase(DB_NAME)
    }

    private fun newInbox(): PushInbox = PushInbox(RuntimeEnvironment.getApplication()).also { opened.add(it) }

    private fun envelope(
        messageId: String = "m1",
        conversationId: String = "c1",
        personaId: String = "default",
        content: String = "hello",
        createdAt: Long = 1_000L,
        modelId: String = "gpt",
        providerId: String = "openai"
    ) = PushEnvelope(
        messageId = messageId,
        conversationId = conversationId,
        personaId = personaId,
        content = content,
        createdAt = createdAt,
        modelId = modelId,
        providerId = providerId
    )

    @Test
    fun persistsAcrossReopen() {
        val first = newInbox()
        assertTrue(first.offer(device, envelope(), 1_000L))
        first.close()

        val second = newInbox()
        assertEquals(listOf(envelope()), second.pending(device))
        assertFalse(second.finished(device, "m1"))
    }

    @Test
    fun exactDuplicateReturnsFalseAndConflictThrows() {
        val inbox = newInbox()
        assertTrue(inbox.offer(device, envelope(), 1_000L))
        assertFalse(inbox.offer(device, envelope(), 2_000L))

        try {
            inbox.offer(device, envelope(content = "altered"), 3_000L)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("message_id_conflict", e.message)
        }
        // Original row is never overwritten.
        assertEquals(listOf(envelope()), inbox.pending(device))
    }

    @Test
    fun finishIsIdempotentAndNeverDeletes() {
        val inbox = newInbox()
        inbox.offer(device, envelope(), 1_000L)

        assertTrue(inbox.finish(device, "m1"))
        assertTrue(inbox.finish(device, "m1"))
        assertTrue(inbox.finished(device, "m1"))
        assertTrue(inbox.pending(device).isEmpty())

        assertFalse(inbox.finish(device, "unknown"))
        assertFalse(inbox.finished(device, "unknown"))
    }

    @Test
    fun isolatesDevices() {
        val inbox = newInbox()
        inbox.offer(device, envelope(messageId = "shared"), 1_000L)
        inbox.offer(otherDevice, envelope(messageId = "shared"), 2_000L)

        assertEquals(listOf("shared"), inbox.pending(device).map { it.messageId })
        assertEquals(listOf("shared"), inbox.pending(otherDevice).map { it.messageId })

        assertTrue(inbox.finish(device, "shared"))
        assertTrue(inbox.finished(device, "shared"))
        assertFalse(inbox.finished(otherDevice, "shared"))
        assertEquals(1, inbox.pending(otherDevice).size)
    }

    @Test
    fun pendingOrdersByReceivedAtThenRowidAndHonoursLimit() {
        val inbox = newInbox()
        inbox.offer(device, envelope(messageId = "late"), 3_000L)
        inbox.offer(device, envelope(messageId = "early"), 1_000L)
        inbox.offer(device, envelope(messageId = "middle"), 2_000L)
        inbox.offer(device, envelope(messageId = "tie-a"), 2_000L)
        inbox.offer(device, envelope(messageId = "tie-b"), 2_000L)

        assertEquals(
            listOf("early", "middle", "tie-a", "tie-b", "late"),
            inbox.pending(device).map { it.messageId }
        )
        assertEquals(listOf("early", "middle"), inbox.pending(device, limit = 2).map { it.messageId })
    }

    @Test
    fun prunesOldFinishedRecordsButPreservesUnfinishedOnes() {
        val inbox = newInbox()
        val now = System.currentTimeMillis()

        inbox.offer(device, envelope(messageId = "old-finished"), 0L)
        inbox.finish(device, "old-finished")
        inbox.offer(device, envelope(messageId = "recent-finished"), now)
        inbox.finish(device, "recent-finished")
        inbox.offer(device, envelope(messageId = "old-unfinished"), 0L)

        // Any later offer performs the prune pass.
        inbox.offer(device, envelope(messageId = "trigger"), now)

        assertFalse(inbox.finished(device, "old-finished"))
        assertTrue(inbox.finished(device, "recent-finished"))
        assertEquals(
            listOf("old-unfinished", "trigger"),
            inbox.pending(device).map { it.messageId }
        )
    }

    @Test
    fun rejectsNewEnvelopeAtUnfinishedCapacityWithoutLosingExisting() {
        val inbox = newInbox()
        for (i in 0 until 1000) {
            assertTrue(inbox.offer(device, envelope(messageId = "m$i"), 1_000L + i))
        }

        try {
            inbox.offer(device, envelope(messageId = "overflow"), 5_000L)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("push_inbox_full", e.message)
        }

        // Existing rows survive, and exact duplicates still dedupe at capacity.
        assertEquals(100, inbox.pending(device, limit = 100).size)
        assertFalse(inbox.offer(device, envelope(messageId = "m0"), 9_999L))
    }

    private companion object {
        private const val DB_NAME = "remote_push_inbox.db"
    }
}
