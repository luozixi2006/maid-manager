package com.miniichat.tasks

import android.app.Application
import com.miniichat.data.*
import com.miniichat.proactive.ProactivePolicy
import com.miniichat.tasks.agent.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WorkPolicyTest {
    @After fun closeStore() { TaskStore.of(RuntimeEnvironment.getApplication()).close(); TaskStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null) }
    @Test fun noImplicitProviderButSavedConfigurationIsPreserved() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        // DataStore's singleton is shared by Robolectric SDK sandboxes. Exercise truly absent prefs.
        assertTrue(ProviderStore(app).read(androidx.datastore.preferences.core.emptyPreferences()).isEmpty())
        assertEquals("", AppSettings().activeModel)
        val old = ProviderConfig("deepseek", "已有服务", "https://api.deepseek.com", "", models = listOf("user-model"))
        ProviderStore(app).save(listOf(old))
        assertEquals(listOf(old), ProviderStore(app).snapshot())
    }
    @Test fun routineAllowanceCannotBecomeAnUnlimitedPermission() {
        listOf("发送", "付款", "删除", "发布", "搜索并付款", "登录", "确认").forEach { assertFalse(RoutineApproval.safeNavigation(it)) }
        assertTrue(RoutineApproval.safeNavigation("搜索")); assertTrue(RoutineApproval.searchField("Search videos"))
        listOf("trash", "share_file", "calendar_event", "read_notifications", "tap", "type_text").forEach { assertFalse(RoutineApproval.ordinary(PhoneStep(it))) }
        assertTrue(RoutineApproval.ordinary(PhoneStep("read_screen")))
        assertFalse(AgentPolicy.validPackage("com.android.settings"))
    }
    @Test fun fixedAndRandomContactIntervalsAreRealAndBounded() {
        val fixed = Assistant("p", "人设", proactiveTiming = "fixed", proactiveMinMinutes = 30)
        assertEquals(30 * 60000L, ProactivePolicy.personaDelay(fixed, 0.99))
        val random = fixed.copy(proactiveTiming = "random", proactiveMaxMinutes = 90)
        assertEquals(30 * 60000L, ProactivePolicy.personaDelay(random, 0.0))
        assertEquals(90 * 60000L, ProactivePolicy.personaDelay(random, 1.0))
        assertEquals(15 * 60000L, ProactivePolicy.personaDelay(fixed.copy(proactiveMinMinutes = 0), 0.0))
    }
    @Test fun bulkApprovalAndFileConsentSurviveDatabaseReopen() {
        val app = RuntimeEnvironment.getApplication(); val store = TaskStore.of(app)
        val task = PhoneTask(goal = "搜索应用", providerId = "p", providerEndpoint = "p", model = "m", engineVersion = 2, autoAllowRoutine = true, fileConsent = false)
        store.put(task); store.close()
        assertTrue(store.get(task.id)!!.autoAllowRoutine); assertFalse(store.get(task.id)!!.fileConsent)
        assertFalse(taskJson.decodeFromString<PhoneTask>("""{"goal":"旧任务","providerId":"p","providerEndpoint":"p","model":"m"}""").autoAllowRoutine)
    }
    @Test fun fileReadStillWaitsForConsentEvenWithBulkApproval() = runBlocking {
        val app = RuntimeEnvironment.getApplication(); val store = TaskStore.of(app)
        val task = PhoneTask(goal = "读取资料", providerId = "p", providerEndpoint = "p", model = "m", engineVersion = 2, autoAllowRoutine = true, fileConsent = false,
            steps = listOf(PhoneStep("read_file", source = "file.txt")))
        store.put(task); AgentEngine(app).run(task.id) { false }
        assertEquals("file_consent", store.get(task.id)!!.neededPermission)
        assertEquals(0, store.get(task.id)!!.cursor)
    }
    @Test fun resizeClampsToSmallScreenWithoutNegativeBounds() {
        assertEquals(250, OverlaySizing.fit(360, 280, 250))
        assertEquals(280, OverlaySizing.fit(200, 280, 400))
        assertEquals(1, OverlaySizing.fit(560, 330, -20))
    }
}
