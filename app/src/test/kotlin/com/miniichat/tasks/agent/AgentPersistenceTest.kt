package com.miniichat.tasks.agent

import com.miniichat.tasks.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import com.miniichat.data.ProviderStore
import com.miniichat.data.ProviderConfig
import com.miniichat.data.ProviderAuthMode
import com.miniichat.api.LocalHttpServer
import com.miniichat.api.MockResponse
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AgentPersistenceTest {
    @After fun closeSandboxDatabase() {
        // Robolectric recreates Android between tests; production has one process.
        TaskStore.of(RuntimeEnvironment.getApplication()).close()
        TaskStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    @Test fun actualHttpPlanningObservesToolOutputBeforeNextRound() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val requests = CopyOnWriteArrayList<String>()
        val server = LocalHttpServer { request ->
            requests += request
            val turn = if (requests.size == 1) AgentTurn("先观察", listOf(PhoneStep("list_apps")))
                else AgentTurn("实际观察后结束", listOf(PhoneStep("finish", arguments = mapOf("text" to "已检查可打开应用"))))
            val body = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                put("finish_reason", "stop"); put("message", buildJsonObject {
                    put("role", "assistant"); put("content", taskJson.encodeToString(turn))
                })
            }) }) }.toString()
            MockResponse(200, body)
        }
        try {
            val base = "http://127.0.0.1:${server.port}"
            ProviderStore(app).save(listOf(ProviderConfig("test", "local test", base, "", authMode = ProviderAuthMode.NONE)))
            val task = PhoneTask(goal = "检查应用", providerId = "test", providerEndpoint = base, model = "test-model", engineVersion = 2)
            val store = TaskStore.of(app); store.put(task)
            AgentEngine(app).run(task.id) { false }
            val result = store.get(task.id)!!
            assertEquals(TaskState.DONE, result.state); assertEquals(2, result.rounds)
            assertEquals(1, result.cursor); assertTrue(result.steps.first().done)
            assertEquals(2, requests.size); assertTrue(requests[1].contains("查看可打开的应用"))
        } finally { server.close() }
    }
    @Test fun requestForPermissionKeepsCurrentStep() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val task = PhoneTask(goal = "读取页面", providerId = "p", providerEndpoint = "p", model = "m", engineVersion = 2,
            steps = listOf(PhoneStep("read_screen", arguments = mapOf("package" to "example.app"))))
        val store = TaskStore.of(app); store.put(task)
        AgentEngine(app).run(task.id) { false }
        assertEquals(TaskState.PERMISSION, store.get(task.id)!!.state)
        assertEquals("accessibility", store.get(task.id)!!.neededPermission)
        assertEquals(0, store.get(task.id)!!.cursor)
    }
    @Test fun interruptedNativeActionAsksInsteadOfReplaying() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val task = PhoneTask(goal = "打开网页", providerId = "p", providerEndpoint = "p", model = "m", engineVersion = 2,
            steps = listOf(PhoneStep("open_url", approved = true, prepared = true, arguments = mapOf("url" to "https://example.test"))))
        val store = TaskStore.of(app); store.put(task)
        AgentEngine(app).run(task.id) { false }
        assertEquals(TaskState.QUESTION, store.get(task.id)!!.state)
        assertEquals("verify", store.get(task.id)!!.neededPermission)
    }
    @Test fun sqliteSurvivesClosingAndReopeningWithApprovalAndCheckpoints() {
        val store = TaskStore.of(RuntimeEnvironment.getApplication())
        val task = PhoneTask(goal = "测试持久化", providerId = "p", providerEndpoint = "https://example.test", model = "m",
            engineVersion = 2, state = TaskState.APPROVAL, cursor = 1, rootDirectory = "Documents",
            allowedPackages = listOf("org.example.notes"), observations = listOf("真实读取结果"),
            steps = listOf(PhoneStep("list_apps", done = true), PhoneStep("open_app", arguments = mapOf("package" to "org.example.notes"))))
        store.put(task); store.close()
        assertEquals(task, store.get(task.id))
        AgentActions.respond(RuntimeEnvironment.getApplication(), task, "approve", "", "stale-token")
        assertEquals(TaskState.APPROVAL, store.get(task.id)!!.state)
        assertFalse(store.get(task.id)!!.steps[1].approved)
    }
}
