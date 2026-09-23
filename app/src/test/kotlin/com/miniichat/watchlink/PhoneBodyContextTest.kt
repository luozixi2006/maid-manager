package com.miniichat.watchlink

import android.app.Application
import com.miniichat.companion.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class PhoneBodyContextTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun bind() { LinkConfig(app).bindPhone("chat-a",JSONObject().put("persona_id","persona-a")) }
    private fun deliver(body:JSONObject) {
        // Exercise the same persisted outbox body and ack/page protocol as the Bluetooth route,
        // without a device, health readings, credentials or real network.
        LinkStore(app).use { watch ->
            watch.enqueue("context","watch",body)
            val outgoing=watch.outgoing();watch.meta("inflight",outgoing.toString())
            val op=outgoing.getJSONObject(0)
            PhoneHubStore(app).use { phone ->
                val channel=PhoneHub.channel(app)
                val revision=phone.put(channel,"context","watch",op.getJSONObject("body"))
                phone.meta("watch-received:$channel",System.currentTimeMillis().toString())
                watch.apply(phone.page(channel,0,JSONArray().put(op.getString("op_id")),JSONObject().put(op.getString("op_id"),revision)))
            }
            assertEquals(0,watch.outgoing().length())
            assertEquals(body.toString(),watch.items("context").single().getJSONObject("body").toString())
        }
    }
    @Test fun sensorCapabilitiesAloneNeverBecomeMeasuredValues() {
        bind()
        deliver(JSONObject().put("source","watch").put("at",System.currentTimeMillis())
            .put("available_sensors",JSONArray().put(21)).put("heart_rate_status","no_reading"))
        val prompt=PhoneLink.contextText(app,"chat-a")
        assertTrue(prompt.contains("采样窗口内没有收到心率读数"))
        assertTrue(prompt.contains("当前心率：未知"))
        assertFalse(prompt.contains("次/分"))
        assertTrue(PhoneLink.deliveryStatus(app).contains("该上下文带入的手表采集时间"))
    }
    @Test fun actualReducerReadingSurvivesSerializationOutboxPhoneStoreAndPrompt() {
        bind()
        val now=System.currentTimeMillis()
        val reducer=ActivityReducer()
        reducer.accept(Sample(time=now-20_000,steps=100,day="test",worn=true))
        reducer.accept(Sample(time=now,steps=112,day="test",worn=true,heartRate=72f))
        val payload=JSONObject(Json.encodeToString(reducer.state)).put("at",now).put("source","watch")
        deliver(payload)
        val prompt=PhoneLink.contextText(app,"chat-a")
        assertTrue(prompt.contains("最近测得心率：72.0 次/分"))
        assertTrue(prompt.contains("记录步数：12"))
        assertFalse(prompt.contains("当前心率：未知"))
        assertTrue(PhoneLink.deliveryStatus(app).contains("最近测得心率：72.0"))
    }
    @Test fun disabledAndOtherConversationsDoNotGetHealthData() {
        bind()
        deliver(JSONObject().put("source","watch").put("at",System.currentTimeMillis()))
        assertEquals("",PhoneLink.contextText(app,"other-chat"))
        LinkConfig(app).enabled(false)
        assertEquals("",PhoneLink.contextText(app,"chat-a"))
        assertEquals("身体数据共享已暂停。",PhoneLink.deliveryStatus(app))
    }
    @Test fun oldSampleIsExplainedNotSilentlyDroppedOrClaimedCurrent() {
        bind()
        val old=System.currentTimeMillis()-30*60_000
        deliver(JSONObject().put("source","watch").put("at",old).put("heartRateAt",old).put("heartRate",72))
        val prompt=PhoneLink.contextText(app,"chat-a")
        assertTrue(prompt.contains("记录已过期"))
        assertFalse(prompt.contains("72.0 次/分"))
        assertFalse(prompt.contains("手机尚未收到"))
    }
    @Test fun noRecordAndNoRequestHaveDistinctDiagnostics() {
        bind()
        val before=PhoneLink.deliveryStatus(app)
        assertTrue(before.contains("手机收到身体数据：尚未收到"))
        assertTrue(before.contains("共享聊天最近组装模型上下文：无记录"))
        PhoneLink.contextText(app,"chat-a")
        val after=PhoneLink.deliveryStatus(app)
        assertTrue(after.contains("手机收到身体数据：尚未收到"))
        assertFalse(after.contains("共享聊天最近组装模型上下文：无记录"))
    }
    @Test fun personaChannelsCannotSeePreviouslyBoundPersonaReadings() {
        bind()
        val now=System.currentTimeMillis()
        deliver(JSONObject().put("source","watch").put("at",now).put("heartRateAt",now).put("heartRate",72))
        LinkConfig(app).bindPhone("chat-b",JSONObject().put("persona_id","persona-b"))
        val prompt=PhoneLink.contextText(app,"chat-b")
        assertTrue(prompt.contains("手机尚未收到"))
        assertFalse(prompt.contains("72.0 次/分"))
    }
}
