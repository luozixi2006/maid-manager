package com.miniichat.companion

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class LinkStoreTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private fun response(op:JSONObject,revision:Int=1)=JSONObject().put("cursor",revision).put("acked",JSONArray().put(op.getString("op_id")))
        .put("changes",JSONArray().put(JSONObject().put("id",op.getString("id")).put("kind",op.getString("kind")).put("body",op.getJSONObject("body")).put("revision",revision).put("deleted",op.optBoolean("deleted"))))
    @Test fun outboxAndCursorSurviveProcessRecreation() {
        val store=LinkStore(context);store.enqueue("message","a",JSONObject().put("content","你好"));val op=store.outgoing().getJSONObject(0);store.close()
        LinkStore(context).use{reopened->assertEquals(op.toString(),reopened.outgoing().getJSONObject(0).toString());reopened.apply(response(op));assertEquals(0,reopened.outgoing().length())}
        LinkStore(context).use{assertEquals("1",it.meta("cursor"));assertEquals("你好",it.items("message").single().getJSONObject("body").getString("content"))}
    }
    @Test fun editWhileOlderValueIsInFlightIsPreservedAndRebased() {
        LinkStore(context).use{store->
            store.enqueue("message","a",JSONObject().put("content","旧内容"));val outgoing=store.outgoing();val old=outgoing.getJSONObject(0)
            store.meta("inflight",outgoing.toString());store.enqueue("message","a",JSONObject().put("content","新内容"))
            store.apply(response(old))
            val pending=store.outgoing().getJSONObject(0)
            assertEquals("新内容",pending.getJSONObject("body").getString("content"));assertEquals(1,pending.getInt("revision"))
            assertTrue(store.hasPending("message","a"))
        }
    }
    @Test fun unrelatedRemoteEditDoesNotSilentlyRebaseLocalConflict() {
        LinkStore(context).use{store->
            store.enqueue("message","a",JSONObject().put("content","本地"));val op=store.outgoing().getJSONObject(0)
            store.apply(response(op).put("acked",JSONArray()))
            assertEquals(0,store.outgoing().getJSONObject(0).getInt("revision"))
        }
    }
    @Test fun deletedRecordCannotBeRevivedAndPendingThoughtIsDurable() {
        LinkStore(context).use{store->
            store.enqueue("message","a",JSONObject(),true);store.apply(response(store.outgoing().getJSONObject(0)))
            store.enqueue("message","a",JSONObject().put("content","不要复活"));assertEquals(0,store.outgoing().length())
            store.pending("t",JSONObject().put("id","t"))
        }
        LinkStore(context).use{assertEquals("t",it.thoughts().single().first);it.done("t");assertTrue(it.thoughts().isEmpty())}
    }
    @Test fun tokenTransportRejectsPublicCleartextAndCredentialBearingUrls() {
        CompanionHttp.validate("http://100.123.123.23:7862")
        CompanionHttp.validate("https://example.com")
        for(url in listOf("http://8.8.8.8","http://100.1.1.1","https://u:p@example.com","https://example.com/?key=secret"))
            assertThrows(IllegalArgumentException::class.java){CompanionHttp.validate(url)}
    }
}
