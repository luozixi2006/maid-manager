package com.miniichat.tasks.agent

import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.miniichat.tasks.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class AppSearchTest {
    private val pkg = "example.video"
    private fun page(service: PhoneAccessibility, text: String = "搜索视频"): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo.obtain().apply { packageName = pkg; this.text = text; hintText = "搜索"; isEditable = true; isEnabled = true; isVisibleToUser = true }
        val window = AccessibilityWindowInfo.obtain()
        Shadows.shadowOf(window).apply { setType(AccessibilityWindowInfo.TYPE_APPLICATION); setLayer(1); setRoot(node) }
        Shadows.shadowOf(service).setWindows(listOf(window))
        Shadows.shadowOf(node).setOnPerformActionListener { _, _ -> true }
        return node
    }
    @Test fun observesTypesAndSubmitsSearchUsingActualAccessibilityActions() {
        val service = Robolectric.buildService(PhoneAccessibility::class.java).get()
        val node = page(service)
        TaskActions.preferences(service).edit().putStringSet("agent_apps", setOf(pkg)).commit()
        val task = PhoneTask(goal = "搜索作者", providerId = "p", providerEndpoint = "p", model = "m", allowedPackages = listOf(pkg))
        fun observedTask(): Pair<PhoneTask, Map<String, String>> {
            val step = PhoneStep("read_screen", arguments = mapOf("package" to pkg))
            val receipt = service.execute(task, step)
            val snapshot = taskJson.parseToJsonElement(receipt).jsonObject["snapshot"]!!.jsonPrimitive.content
            return task.copy(steps = listOf(step.copy(done = true, result = receipt)), cursor = 1) to mapOf("package" to pkg, "node" to "0", "snapshot" to snapshot)
        }
        val (readTask, args) = observedTask()
        val type = PhoneStep("type_text", arguments = args + ("text" to "某位作者"))
        assertTrue(service.canRunRoutine(readTask, type))
        service.execute(readTask, type)
        assertTrue(Shadows.shadowOf(node).performedActions.contains(AccessibilityNodeInfo.ACTION_SET_TEXT))
        val (fresh, freshArgs) = observedTask()
        service.execute(fresh, PhoneStep("submit_search", arguments = freshArgs))
        assertTrue(Shadows.shadowOf(node).performedActions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
        node.text = "页面变化"
        assertFalse(service.canRunRoutine(readTask, type))
    }
    @Test fun companionCannotReadUnselectedAppPasswordOrVerificationPage() {
        val service = Robolectric.buildService(PhoneAccessibility::class.java).get()
        val node = page(service, "这是一段可见资料").apply { isEditable = false }
        assertNull(service.companionContext(emptySet()))
        assertNotNull(service.companionContext(setOf(pkg)))
        node.isPassword = true; assertNull(service.companionContext(setOf(pkg)))
        node.isPassword = false; node.text = "验证码 123456"; assertNull(service.companionContext(setOf(pkg)))
    }
    @Test fun aSearchWordInAMessageOrPackageCannotAuthorizeSending() {
        val service = Robolectric.buildService(PhoneAccessibility::class.java).get()
        val node = page(service, "搜索某位作者").apply { hintText = ""; viewIdResourceName = "example.search:id/message_input" }
        TaskActions.preferences(service).edit().putStringSet("agent_apps", setOf(pkg)).commit()
        val task = PhoneTask(goal = "查找", providerId = "p", providerEndpoint = "p", model = "m", allowedPackages = listOf(pkg))
        val read = PhoneStep("read_screen", arguments = mapOf("package" to pkg))
        val receipt = service.execute(task, read)
        val hash = taskJson.parseToJsonElement(receipt).jsonObject["snapshot"]!!.jsonPrimitive.content
        assertFalse(service.canRunRoutine(task.copy(steps = listOf(read.copy(done = true, result = receipt)), cursor = 1),
            PhoneStep("type_text", arguments = mapOf("package" to pkg, "node" to "0", "snapshot" to hash, "text" to "不能发送"))))
        assertTrue(Shadows.shadowOf(node).performedActions.isEmpty())
    }
}
