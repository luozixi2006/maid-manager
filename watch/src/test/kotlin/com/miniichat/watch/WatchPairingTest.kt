package com.miniichat.watch

import android.app.Application
import com.miniichat.companion.LinkConfig
import com.miniichat.companion.LinkStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class WatchPairingTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val base="bluetooth://AA:BB:CC:DD:EE:FF"
    private fun prepareOldQueue():String {
        context.getSharedPreferences("companion_link",0).edit().putString("base",base).putString("conversation","chat-a")
            .putString("profile",JSONObject().put("persona_id","p-a").put("name","A").toString()).putBoolean("enabled",false).commit()
        return LinkStore(context).use{store->
            store.meta("bound","chat-a")
            store.enqueue("message","pending-message",JSONObject().put("id","pending-message").put("role","user").put("content","还没同步"))
            store.pending("reply-pending-message",JSONObject().put("message_id","pending-message"))
            store.outgoing().getJSONObject(0).getString("op_id")
        }
    }
    @Test fun expiredOldCredentialsDoNotBlockNewPairCodeWithPendingData()=runBlocking {
        val operation=prepareOldQueue()
        val paths=mutableListOf<String>()
        val candidate=WatchConnection.prepare(context,base,"12345678") {host,token,path,body->
            assertEquals(base,host);paths+=path
            if(path=="/v1/pair") {
                assertEquals("",token);assertEquals("12345678",body!!.getString("code"))
                JSONObject().put("token","synthetic-token").put("device_id","new-device").put("conversation_id","chat-a")
            } else {
                assertEquals("/v1/profile",path);assertEquals("synthetic-token",token)
                JSONObject().put("persona_id","p-a").put("name","A")
            }
        }
        assertEquals(listOf("/v1/pair","/v1/profile"),paths)
        assertTrue(candidate.sameConnection)
        assertFalse(LinkConfig(context).enabled)
        LinkStore(context).use{assertEquals(operation,it.outgoing().getJSONObject(0).getString("op_id"));assertEquals(1,it.thoughts().size)}
    }
    @Test fun otherPersonaNeedsConfirmationBeforeAnyDataOrConfigChanges()=runBlocking {
        val operation=prepareOldQueue()
        val candidate=WatchConnection.prepare(context,base,"12345678") {_,_,path,_->
            if(path=="/v1/pair")JSONObject().put("token","synthetic-token").put("device_id","new-device").put("conversation_id","chat-a")
            else JSONObject().put("persona_id","p-b").put("name","B")
        }
        assertFalse(candidate.sameConnection)
        assertEquals("p-a",LinkConfig(context).profile.getString("persona_id"))
        LinkStore(context).use{assertEquals(operation,it.outgoing().getJSONObject(0).getString("op_id"));assertTrue(ConnectionRecords(it).backups().isEmpty())}
    }
    @Test fun invalidCodeDoesNotContactPhoneOrClearQueue() {
        val operation=prepareOldQueue()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {WatchConnection.prepare(context,base,"bad-code"){_,_,_,_->error("Must not send invalid code")}}
        }
        LinkStore(context).use{assertEquals(operation,it.outgoing().getJSONObject(0).getString("op_id"))}
    }
}
