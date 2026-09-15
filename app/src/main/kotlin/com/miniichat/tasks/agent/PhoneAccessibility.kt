package com.miniichat.tasks.agent

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.miniichat.tasks.*
import java.security.MessageDigest
import kotlinx.serialization.encodeToString

class PhoneAccessibility : AccessibilityService() {
    override fun onServiceConnected() { current = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }
    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (result.size >= 160 || depth > 20) return
            if (node.isVisibleToUser) result += node
            for (i in 0 until node.childCount.coerceAtMost(100)) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0); return result
    }
    private fun snapshot(packageName: String, task: PhoneTask): Pair<String, List<AccessibilityNodeInfo>> {
        check(!getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "手机已锁屏，请解锁后继续" }
        check(AgentPolicy.validPackage(packageName) && packageName in task.allowedPackages &&
            packageName in TaskActions.preferences(this).getStringSet("agent_apps", emptySet()).orEmpty()) { "应用不在你授权的名单中" }
        val root = visibleRoot() ?: throw NeedsPermission("screen", "没有可读取页面，请把目标应用切到前台再继续")
        check(root.packageName?.toString() == packageName) { "前台应用已变化，请返回目标应用再继续" }
        val nodes = nodes(root)
        check(nodes.none { it.isPassword }) { "密码页面不读取、不输入；请自行完成后继续" }
        val serialized = nodes.mapIndexed { index, node ->
            val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
            "$index|${node.viewIdResourceName}|${node.text}|${node.contentDescription}|$bounds|${node.isEnabled}"
        }.joinToString("\n")
        val hash = MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray()).joinToString("") { "%02x".format(it) }
        return hash to nodes
    }
    private fun visibleRoot(): AccessibilityNodeInfo? = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        .maxByOrNull { it.layer }?.root ?: rootInActiveWindow

    private fun label(node: AccessibilityNodeInfo): String = listOfNotNull(node.contentDescription, node.hintText,
        node.viewIdResourceName?.substringAfterLast('/')).joinToString(" ")
    private fun navigationLabel(node: AccessibilityNodeInfo): String = node.text?.toString().orEmpty()
        .ifBlank { node.contentDescription?.toString().orEmpty() }
        .ifBlank { (0 until node.childCount.coerceAtMost(8)).mapNotNull { node.getChild(it)?.takeIf { n -> n.isVisibleToUser && !n.isEditable }?.text?.toString() }.distinct().joinToString(" ") }

    fun canRunRoutine(task: PhoneTask, step: PhoneStep): Boolean {
        if (step.tool !in setOf("tap", "type_text", "submit_search")) return false
        return runCatching {
            AgentPolicy.requireFreshObservation(task, step)
            val (hash, all) = snapshot(step.arguments["package"].orEmpty(), task)
            check(hash == step.arguments["snapshot"])
            val target = all.getOrNull(step.arguments["node"]?.toIntOrNull() ?: -1) ?: return false
            if (step.tool == "tap") RoutineApproval.safeNavigation(navigationLabel(target))
            else target.isEditable && RoutineApproval.searchField(label(target))
        }.getOrDefault(false)
    }

    /** Read-only, separately opted-in companionship. No task tools and no screenshots are issued. */
    fun companionContext(allowed: Set<String>): Pair<String, String>? {
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        val root = visibleRoot() ?: return null
        val pkg = root.packageName?.toString() ?: return null
        if (pkg !in allowed || !AgentPolicy.validPackage(pkg)) return null
        val all = nodes(root)
        if (all.any { it.isPassword }) return null
        val text = all.filterNot { it.isEditable }.mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
            .distinct().joinToString("\n").take(4000)
        if (text.isBlank() || Regex("验证码|动态口令|支付密码|身份证|银行卡|one.time|verification code|\\bOTP\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        return pkg to text
    }
    fun execute(task: PhoneTask, step: PhoneStep): String {
        AgentPolicy.requireFreshObservation(task, step)
        val pkg = step.arguments["package"].orEmpty()
        val (hash, nodes) = snapshot(pkg, task)
        if (step.tool == "read_screen") {
            val rows = buildString {
                nodes.forEachIndexed { i, n ->
                    val row = "$i: text=${n.text?.take(100)} desc=${n.contentDescription?.take(60)} hint=${n.hintText?.take(80)} id=${n.viewIdResourceName} clickable=${n.isClickable} editable=${n.isEditable} scrollable=${n.isScrollable}\n"
                    if (length + row.length <= 8500) append(row)
                }
            }
            return taskJson.encodeToString(mapOf("package" to pkg, "snapshot" to hash, "nodes" to rows))
        }
        if (step.tool in setOf("back", "home")) {
            check(performGlobalAction(if (step.tool == "back") GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME)) { "系统拒绝此操作" }
            return "已发出系统导航动作；下一步需要重新观察页面"
        }
        check(step.arguments["snapshot"] == hash) { "页面与批准时的快照不同，已拒绝操作；请重新观察" }
        val target = nodes.getOrNull(step.arguments["node"]?.toIntOrNull() ?: -1) ?: error("节点不存在，请重新观察")
        check(target.isEnabled && !target.isPassword) { "控件不可操作" }
        val success = when (step.tool) {
            "tap" -> { check(target.isClickable) { "控件不可点击" }; target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            "type_text" -> { check(target.isEditable) { "控件不是输入框" }
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step.arguments["text"].orEmpty()) }) }
            "submit_search" -> {
                check(target.isEditable && RoutineApproval.searchField(label(target))) { "只能提交已识别的搜索框；其他提交需要手动确认" }
                if (android.os.Build.VERSION.SDK_INT >= 30) target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                else error("此系统不支持输入框搜索动作，请观察并点击搜索按钮")
            }
            "scroll" -> target.performAction(if (step.arguments["direction"] == "backward") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            else -> error("不支持的页面操作")
        }
        check(success) { "应用拒绝操作；不能视为成功" }
        return "控件接受了${step.tool}动作；必须重新观察以确认实际效果，不能据此推断发送或付款成功"
    }
    companion object { @Volatile var current: PhoneAccessibility? = null; private set }
}
