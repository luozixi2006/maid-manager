package com.miniichat.proactive

import android.app.Application
import android.app.NotificationManager
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.miniichat.api.LocalHttpServer
import com.miniichat.api.MockResponse
import com.miniichat.data.*
import com.miniichat.tasks.PetMessages
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ProactivePipelineTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val calls=AtomicInteger()
    private lateinit var server:LocalHttpServer
    private var decision="""{"action":"SEND","message":"昨天说的事情，今天还顺利吗？","topic_summary":"接续话题"}"""
    @Before fun setup() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        server=LocalHttpServer {calls.incrementAndGet();MockResponse(200,buildJsonObject {
            put("choices",buildJsonArray {add(buildJsonObject {
                put("finish_reason","stop");put("message",buildJsonObject {put("role","assistant");put("content",decision)})
            })})
        }.toString())}
        ProviderStore(app).save(listOf(ProviderConfig("test","local","http://127.0.0.1:${server.port}","",authMode=ProviderAuthMode.NONE)))
        SettingsRepository(app).update{it.copy(activeProviderId="test",activeModel="test-model",proactiveMessagesEnabled=false,
            proactiveDndStartMinutes=0,proactiveDndEndMinutes=0,lastProactiveMessageAt=0,memoryEnabled=false)}
        AssistantStore(app).save(listOf(Assistant("persona","小夏",proactiveEnabled=true,proactiveConsentVersion=1,nextProactiveCheckAt=1)))
        ConversationStore(app).save(emptyList())
    }
    @After fun cleanup() {
        server.close();PetMessages.notice.value="";PetMessages.conversationId=""
        WorkManagerTestInitHelper.closeWorkDatabase()
    }
    private suspend fun history(age:Long,proactive:Boolean=false,streaming:Boolean=false) {
        val message=Message("m",if(proactive || streaming)"assistant" else "user","之前的话题",createdAt=System.currentTimeMillis()-age,
            isProactive=proactive,deliveryStatus=if(streaming)MessageDeliveryStatus.STREAMING else MessageDeliveryStatus.SUCCEEDED)
        ConversationStore(app).save(listOf(Conversation("chat","原聊天",listOf(message),assistantId="persona")))
    }
    private suspend fun run(manual:Boolean=false) {
        val data=if(manual)workDataOf("manual" to true,"assistant_id" to "persona") else workDataOf("recovery" to true)
        TestListenableWorkerBuilder<ProactiveMessageWorker>(app).setInputData(data).build().doWork()
    }
    @Test fun fiveMinuteOldChatCanReceiveRealAutomaticMessageAndNotification()=runBlocking {
        history(5*60000L);run()
        assertEquals(1,calls.get())
        val message=ConversationStore(app).snapshot().single().messages.last()
        assertTrue(message.isProactive);assertEquals("test",message.providerId)
        assertTrue(PetMessages.notice.value.contains("昨天说的事情"))
        assertNotNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification("normal:chat".hashCode()))
    }
    @Test fun recentConversationDefersToCooldownEndNotHoursLater()=runBlocking {
        history(2*60000L);val before=System.currentTimeMillis();run()
        assertEquals(0,calls.get())
        val next=AssistantStore(app).snapshot().single().nextProactiveCheckAt
        assertTrue(next-before in 59_000L..65_000L)
    }
    @Test fun unansweredNinetyMinuteOldMessageNoLongerLocksForADay()=runBlocking {
        history(90*60000L,true);run();assertEquals(1,calls.get())
        assertEquals(2,ConversationStore(app).snapshot().single().messages.size)
    }
    @Test fun explicitFifteenMinuteCadenceIsUsedForUnansweredGuard()=runBlocking {
        AssistantStore(app).update("persona"){it.copy(proactiveTiming="fixed",proactiveMinMinutes=15)}
        history(20*60000L,true);run();assertEquals(1,calls.get())
    }
    @Test fun manualTestUsesWorkerSaveAndNotificationEvenWithinAppQuietHours()=runBlocking {
        history(0,true)
        val minute=java.util.Calendar.getInstance().let{it.get(java.util.Calendar.HOUR_OF_DAY)*60+it.get(java.util.Calendar.MINUTE)}
        SettingsRepository(app).update{it.copy(lastProactiveMessageAt=System.currentTimeMillis(),proactiveDndStartMinutes=minute,proactiveDndEndMinutes=(minute+30)%1440)}
        AssistantStore(app).update("persona"){it.copy(nextProactiveCheckAt=System.currentTimeMillis()+86400000)}
        run(true);assertEquals(1,calls.get())
        assertTrue(ConversationStore(app).snapshot().single().messages.last().isProactive)
        assertNotNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification("normal:chat".hashCode()))
    }
    @Test fun disabledConsentPreventsEvenManualWorkerFromSending()=runBlocking {
        AssistantStore(app).update("persona"){it.copy(proactiveEnabled=false)}
        run(true);assertEquals(0,calls.get());assertTrue(ConversationStore(app).snapshot().isEmpty())
    }
    @Test fun streamingReplyPreventsManualAndAutomaticInterruption()=runBlocking {
        history(10*60000L,streaming=true);run();run(true)
        assertEquals(0,calls.get());assertEquals(1,ConversationStore(app).snapshot().single().messages.size)
    }
    @Test fun malformedDecisionIsRetriedSoonNotSilentlyTreatedAsSkip()=runBlocking {
        decision="{}";val before=System.currentTimeMillis();run()
        assertEquals(1,calls.get())
        val persona=AssistantStore(app).snapshot().single()
        assertEquals(1,persona.proactiveFailureCount)
        assertTrue(persona.nextProactiveCheckAt-before in 120_000L..135_000L)
        assertTrue(ConversationStore(app).snapshot().isEmpty())
    }
    @Test fun automaticCheckStillHonorsQuietHoursAndShowsNextCheck()=runBlocking {
        val minute=java.util.Calendar.getInstance().let{it.get(java.util.Calendar.HOUR_OF_DAY)*60+it.get(java.util.Calendar.MINUTE)}
        SettingsRepository(app).update{it.copy(proactiveDndStartMinutes=minute,proactiveDndEndMinutes=(minute+30)%1440)}
        val before=System.currentTimeMillis();run()
        assertEquals(0,calls.get())
        assertTrue(AssistantStore(app).snapshot().single().nextProactiveCheckAt>before)
        assertTrue(ProactiveDiagnostics.describe(app,"persona").contains("安静时段"))
    }
    @Test fun watchEventReachesSameModelHistoryAndNotificationPipeline()=runBlocking {
        history(5*60000L)
        com.miniichat.companion.LinkConfig(app).bindPhone("chat",org.json.JSONObject().put("persona_id","persona"))
        val channel=com.miniichat.watchlink.PhoneHub.channel(app)
        com.miniichat.watchlink.PhoneHubStore(app).use{db->
            db.put(channel,"event","e",org.json.JSONObject().put("at",System.currentTimeMillis()).put("summary","开始活动").put("source","watch"))
            db.queue(channel,"event-e",org.json.JSONObject().put("event_id","e"))
        }
        com.miniichat.watchlink.PhoneCompanion.process(app)
        assertEquals(1,calls.get())
        assertTrue(ConversationStore(app).snapshot().single().messages.last().isProactive)
        assertNotNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification("normal:chat".hashCode()))
        com.miniichat.watchlink.PhoneHubStore(app).use{assertTrue(it.pending(channel).isEmpty())}
    }
    @Test fun emptySharedWatchChatIsReusedRatherThanCreatingSecondConversation()=runBlocking {
        ConversationStore(app).save(listOf(Conversation("bound","共享聊天",assistantId="persona")))
        com.miniichat.companion.LinkConfig(app).bindPhone("bound",org.json.JSONObject().put("persona_id","persona"))
        run()
        val saved=ConversationStore(app).snapshot().single()
        assertEquals("bound",saved.id);assertTrue(saved.messages.single().isProactive)
    }
}
