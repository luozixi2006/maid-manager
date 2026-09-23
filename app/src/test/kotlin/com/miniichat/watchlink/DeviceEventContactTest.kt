package com.miniichat.watchlink

import android.app.Application
import com.miniichat.data.*
import com.miniichat.proactive.ProactivePolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class DeviceEventContactTest {
    private val now=System.currentTimeMillis()
    private val persona=Assistant("p","角色",proactiveEnabled=true,proactiveConsentVersion=1)
    private val settings=AppSettings(proactiveMessagesEnabled=false,proactiveDndStartMinutes=0,proactiveDndEndMinutes=0)
    private val event=JSONObject().put("at",now).put("summary","开始活动")
    @Test fun eventDuringRecentChatGetsExactDeferredDeadline() {
        val chat=Conversation("c","chat",listOf(Message("u","user","你好",createdAt=now-60000)),assistantId="p")
        assertEquals(now+120000,PhoneCompanion.contactRetryAt(persona,settings,chat,event,now))
        assertFalse(PhoneCompanion.shouldContact(persona,settings,chat,event,now))
        assertTrue(PhoneCompanion.shouldContact(persona,settings,chat,event,now+120000))
    }
    @Test fun globalThrottleAlsoAppliesToDeviceEvents() {
        val chat=Conversation("c","chat",assistantId="p")
        val throttled=settings.copy(lastProactiveMessageAt=now-60000)
        assertEquals(now-60000+ProactivePolicy.GLOBAL_THROTTLE_MILLIS,PhoneCompanion.contactRetryAt(persona,throttled,chat,event,now))
    }
    @Test fun disabledOrExpiredEventNeverTriggers() {
        val chat=Conversation("c","chat",assistantId="p")
        assertNull(PhoneCompanion.contactRetryAt(persona.copy(proactiveEnabled=false),settings,chat,event,now))
        assertNull(PhoneCompanion.contactRetryAt(persona,settings,chat,event,now+1800001))
    }
    @Test fun deferringDoesNotFinishOrConsumeRetryAttemptsAndSurvivesReopen() {
        val app=RuntimeEnvironment.getApplication()
        PhoneHubStore(app).use {db->
            db.queue("ch","event-one",JSONObject().put("event_id","one"))
            db.defer("ch","event-one",now+60000)
            assertTrue(db.pending("ch").isEmpty())
        }
        PhoneHubStore(app).use {db->
            db.defer("ch","event-one",now-1)
            assertEquals("event-one",db.pending("ch").single().getString("id"))
            db.finish("ch","event-one");db.defer("ch","event-one",now-1)
            assertTrue(db.pending("ch").isEmpty())
        }
    }
}
