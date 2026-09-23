package com.miniichat.watch

import android.app.Application
import com.miniichat.companion.LinkStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regression coverage that switching watch bindings never loses locally queued records. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class ConnectionRecordsTest {
    @Test fun interruptedCredentialSwitchCannotMatchOldRecords() {
        LinkStore(context).use { store->
            val a=WatchBinding("bluetooth://AA:BB:CC:DD:EE:FF","chat","a","A")
            val b=a.copy(persona="b",name="B")
            store.meta("bound",a.conversation)
            val records=ConnectionRecords(store)
            records.current(a)
            store.enqueue("message","old",message("只能给 A"))
            assertFalse(records.matches(b))
            assertTrue(records.matches(a))
            assertTrue(store.hasPending("message","old"))
        }
    }
    @Test fun corruptDestinationBackupRollsBackWithoutClearingCurrentQueue() {
        LinkStore(context).use { store->
            val a=WatchBinding("bluetooth://AA:BB:CC:DD:EE:FF","a","p","A")
            val b=a.copy(conversation="b",name="B")
            store.meta("bound",a.conversation)
            val records=ConnectionRecords(store)
            records.current(a)
            store.enqueue("message","old",message("保留我"))
            val operation=store.outgoing().getJSONObject(0).getString("op_id")
            store.writableDatabase.execSQL("INSERT INTO watch_connection_backups(binding,info,snapshot,saved_at) VALUES(?,?,?,?)",arrayOf(b.key,b.json().toString(),"{}",1L))
            org.junit.Assert.assertThrows(org.json.JSONException::class.java){records.activate(a,b)}
            assertTrue(records.matches(a))
            assertEquals(operation,store.outgoing().getJSONObject(0).getString("op_id"))
            assertFalse(records.backups().any{it.binding.key==a.key})
        }
    }
    private val context get()=RuntimeEnvironment.getApplication()
    private fun binding(base:String,conversation:String,persona:String,name:String)=WatchBinding(base,conversation,persona,name)
    private fun message(text:String,role:String="assistant")=JSONObject().put("role",role).put("content",text)
    private fun thoughtBody(id:String)=JSONObject().put("id",id).put("content","思考 $id")
    private fun thoughts(store:LinkStore):List<String> = store.writableDatabase
        .rawQuery("SELECT id FROM pending_thought ORDER BY id",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    private fun outboxIds(store:LinkStore):List<String> = store.outgoing().let{(0 until it.length()).map{i->it.getJSONObject(i).getString("id")}}
    private fun outboxOps(store:LinkStore):List<String> = store.outgoing().let{(0 until it.length()).map{i->it.getJSONObject(i).getString("op_id")}}
    /** Synthetic server page: every queued op is explicitly acknowledged at [revision]. */
    private fun acknowledge(store:LinkStore,outgoing:JSONArray,revision:Int=1) {
        val acked=JSONArray();val changes=JSONArray()
        for(i in 0 until outgoing.length()) {
            val op=outgoing.getJSONObject(i)
            acked.put(op.getString("op_id"))
            changes.put(JSONObject().put("kind",op.getString("kind")).put("id",op.getString("id")).put("body",op.getJSONObject("body")).put("revision",revision).put("deleted",op.optBoolean("deleted")))
        }
        store.meta("inflight",outgoing.toString())
        store.apply(JSONObject().put("cursor",revision.toLong()).put("acked",acked).put("changes",changes))
    }

    @Test fun sameBindingReactivationKeepsQueueAndThoughtsAndOnlyResetsCursor() {
        LinkStore(context).use{store->
            val records=ConnectionRecords(store)
            val a=binding("https://phone.example","conv-a","persona-a","会话 A")
            assertEquals(a.key,records.current(a).key)
            store.enqueue("message","a1",message("你好"));store.enqueue("message","a2",message("在吗"))
            store.enqueue("event","a3",JSONObject().put("steps",10))
            val ops=outboxOps(store)
            store.pending("t1",thoughtBody("t1"));store.pending("t2",thoughtBody("t2"))
            store.meta("cursor","42");store.meta("inflight","[{\"op_id\":\"stale\"}]")
            records.activate(a,a)
            assertEquals("",store.meta("cursor"));assertEquals("[]",store.meta("inflight"))
            assertEquals(a.conversation,store.meta("bound"))
            assertTrue(records.matches(a))
            assertEquals(ops,outboxOps(store))
            assertEquals(listOf("t1","t2"),thoughts(store))
            assertTrue(records.backups().isEmpty())
        }
    }

    @Test fun pendingCountsExceedBatchReadLimits() {
        LinkStore(context).use{store->
            val records=ConnectionRecords(store)
            for(i in 0 until 25) store.enqueue("message","m$i",message("m$i"))
            for(i in 0 until 6) store.enqueue("event","e$i",JSONObject().put("i",i))
            for(i in 0 until 3) store.enqueue("context","c$i",JSONObject().put("i",i))
            for(i in 0 until 7) store.pending("t$i",thoughtBody("t$i"))
            assertEquals("outgoing() is a page, not the whole queue",20,store.outgoing().length())
            assertEquals("thoughts() is a batch, not the whole store",5,store.thoughts().size)
            val counts=records.pending()
            assertEquals(25,counts.messages);assertEquals(6,counts.events)
            assertEquals(3,counts.contexts);assertEquals(7,counts.replies);assertEquals(41,counts.total)
        }
    }

    @Test fun switchingArchivesPreviousBindingAndSurvivesReopen() {
        val a=binding("https://phone.example","conv-a","persona-a","会话 A")
        val b=binding("https://phone.example","conv-b","persona-b","会话 B")
        LinkStore(context).use{store->
            val records=ConnectionRecords(store)
            records.current(a)
            store.enqueue("message","a1",message("A 消息"));store.enqueue("event","a2",JSONObject().put("i",1))
            store.pending("a-t",thoughtBody("a-t"))
            records.activate(a,b)
            assertEquals("A records must not leak into B",0,store.outgoing().length())
            assertTrue(store.items("message").isEmpty())
            assertTrue(store.items("event").isEmpty())
            assertTrue(store.thoughts().isEmpty())
            assertEquals(b.conversation,store.meta("bound"))
            val backup=records.backups().single()
            assertEquals(a.key,backup.binding.key)
            assertEquals(1,backup.pending.messages);assertEquals(1,backup.pending.events);assertEquals(1,backup.pending.replies)
        }
        LinkStore(context).use{reopened->
            val records=ConnectionRecords(reopened)
            assertEquals(a.key,records.backups().single().binding.key)
            assertTrue(records.preview(a).contains("A 消息"))
            assertEquals("没有保留记录",records.preview(b))
        }
    }

    @Test fun switchingBackRestoresAllRecordsAndKeepsOtherBindingAsBackup() {
        val a=binding("https://phone.example","conv-a","persona-a","会话 A")
        val b=binding("https://phone.example","conv-b","persona-b","会话 B")
        LinkStore(context).use{store->
            val records=ConnectionRecords(store)
            records.current(a)
            store.enqueue("message","a1",message("A 消息"));store.enqueue("event","a2",JSONObject().put("i",1))
            store.enqueue("context","a3",JSONObject().put("i",2))
            for(i in 0 until 7) store.pending("a-t$i",thoughtBody("a-t$i"))
            store.meta("sensor_state","{\"steps\":1234}")
            records.activate(a,b)
            store.enqueue("message","b1",message("B 消息"));store.pending("b-t",thoughtBody("b-t"))
            store.meta("sensor_state","{\"steps\":9}")
            records.activate(b,a)
            assertEquals(a.conversation,store.meta("bound"));assertTrue(records.matches(a))
            assertEquals("{\"steps\":1234}",store.meta("sensor_state"))
            assertEquals(listOf("a1","a2","a3"),outboxIds(store))
            assertEquals((0 until 7).map{"a-t$it"},thoughts(store))
            assertTrue(records.preview().contains("A 消息"))
            val backup=records.backups().single()
            assertEquals("B keeps its own backup",b.key,backup.binding.key)
            assertEquals(1,backup.pending.messages);assertEquals(1,backup.pending.replies)
            assertTrue(records.preview(b).contains("B 消息"))
            assertEquals("restored A backup is dropped: the active copy is authoritative","没有保留记录",records.preview(a))
        }
    }

    @Test fun differentDeviceOrPersonaStaysIsolated() {
        val a=binding("bluetooth://AA:BB:CC","conv-1","persona-1","A")
        val otherDevice=binding("bluetooth://dd:ee:ff","conv-1","persona-1","设备 B")
        val otherPersona=binding("bluetooth://aa:bb:cc","conv-1","persona-2","人格 B")
        assertNotEquals(a.key,otherDevice.key);assertNotEquals(a.key,otherPersona.key)
        LinkStore(context).use{store->
            val records=ConnectionRecords(store)
            records.current(a)
            store.enqueue("message","a1",message("A 消息"))
            records.activate(a,otherDevice)
            assertEquals("same conversation id on another device must not reuse A's queue",0,store.outgoing().length())
            store.enqueue("message","d1",message("设备 B 消息"))
            records.activate(otherDevice,otherPersona)
            assertEquals("same device with another persona must not reuse the previous queue",0,store.outgoing().length())
            records.activate(otherPersona,otherDevice)
            assertEquals(listOf("d1"),outboxIds(store))
            assertEquals("A's own archive is untouched",a.key,records.backups().single().binding.key)
            assertTrue(records.preview(a).contains("A 消息"))
            assertFalse(records.preview(a).contains("设备 B 消息"))
        }
    }

    @Test fun rearchivingAfterRestoreUsesLatestStateWithoutResurrectingAckedOps() {
        val a=binding("https://phone.example","conv-a","persona-a","会话 A")
        val b=binding("https://phone.example","conv-b","persona-b","会话 B")
        LinkStore(context).use{store->
            val records=ConnectionRecords(store)
            records.current(a)
            store.enqueue("message","a1",message("已确认"))
            val confirmedOp=store.outgoing().getJSONObject(0).getString("op_id")
            acknowledge(store,store.outgoing())
            assertFalse(store.hasPending("message","a1"));assertEquals(1,store.items("message").size)
            records.activate(a,b)
            records.activate(b,a)
            assertFalse("acknowledged write must not come back as pending",store.hasPending("message","a1"))
            assertEquals(1,store.items("message").size);assertEquals(1,store.items("message").single().getInt("revision"))
            store.enqueue("message","a2",message("新的待同步"))
            val pendingOp=store.outgoing().getJSONObject(0).getString("op_id")
            records.activate(a,b)
            records.activate(b,a)
            assertEquals(listOf("a2"),outboxIds(store))
            assertEquals(pendingOp,store.outgoing().getJSONObject(0).getString("op_id"))
            assertFalse(outboxOps(store).contains(confirmedOp))
        }
    }

    @Test fun previewMergesQueuedAndConfirmedMessagesAndUsesArchivedData() {
        val a=binding("https://phone.example","conv-a","persona-a","会话 A")
        val b=binding("https://phone.example","conv-b","persona-b","会话 B")
        LinkStore(context).use{store->
            val records=ConnectionRecords(store)
            records.current(a)
            store.enqueue("message","confirmed",message("已确认消息","user"))
            acknowledge(store,store.outgoing())
            store.enqueue("message","queued",message("待同步消息"))
            val active=records.preview()
            assertTrue(active.contains("你：已确认消息"))
            assertTrue(active.contains("角色：待同步消息"))
            records.activate(a,b)
            store.enqueue("message","b1",message("B 的消息","user"))
            val current=records.preview()
            assertTrue(current.contains("你：B 的消息"))
            assertFalse(current.contains("待同步消息"))
            val archived=records.preview(a)
            assertTrue(archived.contains("你：已确认消息"))
            assertTrue(archived.contains("角色：待同步消息"))
            assertFalse("archive preview must not read current binding data",archived.contains("B 的消息"))
        }
    }
}
