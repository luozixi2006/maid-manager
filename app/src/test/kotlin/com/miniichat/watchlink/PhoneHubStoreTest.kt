package com.miniichat.watchlink

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PhoneHubStoreTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun body(text: String) = JSONObject().put("text", text)
    private fun ids(items: List<JSONObject>) = items.map { it.getString("id") }
    private fun page(db: PhoneHubStore, channel: String, after: Long) = db.page(channel, after, JSONArray(), JSONObject())

    @Test fun channelsAreIsolated() {
        PhoneHubStore(app).use { db ->
            db.put("phone-a", "event", "e1", body("A"))
            db.put("phone-a", "event", "e2", body("A2"))
            db.put("phone-b", "event", "e1", body("B"))
            assertEquals(listOf("e1", "e2"), ids(db.items("phone-a", "event")))
            assertEquals(listOf("e1"), ids(db.items("phone-b", "event")))
            assertEquals("B", db.items("phone-b", "event").single().getJSONObject("body").getString("text"))
            assertEquals(2, page(db, "phone-a", 0L).getJSONArray("changes").length())
            assertEquals(1, page(db, "phone-b", 0L).getJSONArray("changes").length())
            assertEquals(0, page(db, "phone-c", 0L).getJSONArray("changes").length())
            assertTrue(db.items("phone-a", "task").isEmpty())
        }
    }

    @Test fun paginationIsIncrementalAndBounded() {
        PhoneHubStore(app).use { db ->
            for (i in 0 until 35) db.put("p", "event", "r$i", body("v$i"))
            val first = page(db, "p", 0L)
            assertEquals(30, first.getJSONArray("changes").length())
            assertTrue(first.getBoolean("more"))
            val cursor = first.getLong("cursor")
            assertTrue(cursor > 0)
            assertEquals("r0", first.getJSONArray("changes").getJSONObject(0).getString("id"))

            val second = page(db, "p", cursor)
            assertEquals(5, second.getJSONArray("changes").length())
            assertFalse(second.getBoolean("more"))
            assertEquals("r34", second.getJSONArray("changes").getJSONObject(4).getString("id"))
            assertTrue(second.getLong("cursor") > cursor)

            // unchanged body is idempotent: same revision, no extra change row
            assertEquals(1, db.put("p", "event", "r34", body("v34")))
            val third = page(db, "p", second.getLong("cursor"))
            assertEquals(0, third.getJSONArray("changes").length())
            assertFalse(third.getBoolean("more"))
        }
    }

    @Test fun tombstonesPersistAndBlockRevival() {
        PhoneHubStore(app).use { db ->
            assertEquals(1, db.put("c", "event", "e1", body("原始")))
            assertEquals(2, db.put("c", "event", "e1", body("原始"), deleted = true))
        }
        PhoneHubStore(app).use { db ->
            val item = db.items("c", "event").single()
            assertTrue(item.getBoolean("deleted"))
            assertEquals(2, item.getInt("revision"))
            assertEquals(2, db.put("c", "event", "e1", body("复活尝试")))
            val after = db.items("c", "event").single()
            assertTrue(after.getBoolean("deleted"))
            assertEquals("原始", after.getJSONObject("body").getString("text"))
        }
    }

    @Test fun turnQueueAndOperationAcksAreIdempotent() {
        PhoneHubStore(app).use { db ->
            db.queue("ch", "t1", body("第一条"))
            db.queue("ch", "t1", body("重复"))
            db.queue("ch", "t2", body("第二条"))
            val pending = db.pending("ch")
            assertEquals(listOf("t1", "t2"), ids(pending))
            assertEquals("第一条", pending.first().getString("text"))
            assertTrue(db.pending("other").isEmpty())
            db.queue("other", "x1", body("别的通道"))
            assertEquals(listOf("t1", "t2"), ids(db.pending("ch")))
            db.finish("ch", "t1")
            assertEquals(listOf("t2"), ids(db.pending("ch")))
        }
        PhoneHubStore(app).use { db ->
            assertNull(db.acknowledged("ch", "op1"))
            db.ack("ch", "op1", 3)
            db.ack("ch", "op1", 4)
            db.ack("ch", "op1", 3)
            assertEquals(3, db.acknowledged("ch", "op1")!!)
        }
    }

    @Test fun repeatedContextRevisionsCompactToLatestButReachOldCursors() {
        PhoneHubStore(app).use { db ->
            assertEquals(1, db.put("phone-a", "context", "c1", body("v1")))
            val cursorAfterFirst = page(db, "phone-a", 0L).getLong("cursor")
            assertEquals(2, db.put("phone-a", "context", "c1", body("v2")))
            assertEquals(3, db.put("phone-a", "context", "c1", body("v3")))
            db.put("phone-a", "context", "c2", body("other"))

            // Live Context churn is compacted: one change row per (channel, kind, id).
            val fresh = page(db, "phone-a", 0L).getJSONArray("changes")
            assertEquals(2, fresh.length())
            val c1 = (0 until fresh.length()).map { fresh.getJSONObject(it) }.single { it.getString("id") == "c1" }
            assertEquals(3, c1.getInt("revision"))
            assertEquals("v3", c1.getJSONObject("body").getString("text"))
            assertFalse(c1.getBoolean("deleted"))

            // A client that only ever saw revision 1 still receives the newest value.
            val stale = page(db, "phone-a", cursorAfterFirst)
            val staleChanges = stale.getJSONArray("changes")
            assertEquals(2, staleChanges.length())
            val staleChange = (0 until staleChanges.length()).map { staleChanges.getJSONObject(it) }
                .single { it.getString("id") == "c1" }
            assertEquals("c1", staleChange.getString("id"))
            assertEquals(3, staleChange.getInt("revision"))
            assertEquals("v3", staleChange.getJSONObject("body").getString("text"))
            assertTrue(stale.getLong("cursor") > cursorAfterFirst)
            assertFalse(stale.getBoolean("more"))

            val items = db.items("phone-a", "context")
            assertEquals(listOf("c1", "c2"), ids(items))
            val item = items.first { it.getString("id") == "c1" }
            assertEquals(3, item.getInt("revision"))
            assertEquals("v3", item.getJSONObject("body").getString("text"))
            assertFalse(item.getBoolean("deleted"))
        }
    }

    @Test fun messageEditsKeepIdentityAndAdvanceRevision() {
        PhoneHubStore(app).use { db ->
            val first = JSONObject().put("message_id", "m1").put("text", "hello")
            assertEquals(1, db.put("phone-a", "message", "m1", first))
            val edited = JSONObject().put("message_id", "m1").put("text", "hello edited")
            assertEquals(2, db.put("phone-a", "message", "m1", edited))

            // An edit rewrites the same record instead of inserting a second message.
            val items = db.items("phone-a", "message")
            assertEquals(listOf("m1"), ids(items))
            val item = items.single()
            assertEquals(2, item.getInt("revision"))
            assertEquals("m1", item.getJSONObject("body").getString("message_id"))
            assertEquals("hello edited", item.getJSONObject("body").getString("text"))

            val changes = page(db, "phone-a", 0L).getJSONArray("changes")
            assertEquals(1, changes.length())
            val change = changes.getJSONObject(0)
            assertEquals("m1", change.getString("id"))
            assertEquals(2, change.getInt("revision"))
            assertEquals("hello edited", change.getJSONObject("body").getString("text"))

            db.put("phone-a", "message", "m2", JSONObject().put("message_id", "m2").put("text", "second"))
            val after = db.items("phone-a", "message")
            assertEquals(listOf("m1", "m2"), ids(after))
            assertEquals(2, after.first { it.getString("id") == "m1" }.getInt("revision"))
        }
    }

    @Test fun tombstoneSurvivesChangeCompaction() {
        PhoneHubStore(app).use { db ->
            db.put("c", "context", "k1", body("v1"))
            val cursorAfterFirst = page(db, "c", 0L).getLong("cursor")
            db.put("c", "context", "k1", body("v2"))
            assertEquals(3, db.put("c", "context", "k1", body("v3"), deleted = true))

            val changes = page(db, "c", 0L).getJSONArray("changes")
            assertEquals(1, changes.length())
            val tombstone = changes.getJSONObject(0)
            assertEquals("k1", tombstone.getString("id"))
            assertEquals(3, tombstone.getInt("revision"))
            assertTrue(tombstone.getBoolean("deleted"))
            assertEquals("v3", tombstone.getJSONObject("body").getString("text"))

            // Compaction must not hide the deletion from a client stuck on an old cursor.
            val stale = page(db, "c", cursorAfterFirst)
            assertEquals(1, stale.getJSONArray("changes").length())
            assertTrue(stale.getJSONArray("changes").getJSONObject(0).getBoolean("deleted"))
            assertTrue(stale.getLong("cursor") > cursorAfterFirst)

            // A tombstoned record stays deleted: later puts neither revive nor re-broadcast it.
            assertEquals(3, db.put("c", "context", "k1", body("复活尝试")))
            val after = page(db, "c", 0L).getJSONArray("changes")
            assertEquals(1, after.length())
            assertEquals(3, after.getJSONObject(0).getInt("revision"))
            assertTrue(after.getJSONObject(0).getBoolean("deleted"))
            val item = db.items("c", "context").single()
            assertTrue(item.getBoolean("deleted"))
            assertEquals(3, item.getInt("revision"))
            assertEquals("v3", item.getJSONObject("body").getString("text"))
        }
    }
}
