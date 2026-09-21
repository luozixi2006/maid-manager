package com.miniichat.tasks

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.miniichat.api.ChatMessage
import com.miniichat.api.LlmClient
import com.miniichat.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import java.io.File

object PetMessages {
    val notice = MutableStateFlow("")
    var conversationId: String = ""
    fun show(text: String, conversation: String) { conversationId = conversation; show(text) }
    fun show(text: String) { notice.value = text.take(1200) }
}

/** Visible, revocable companion. Screen sharing needs a separate system-approved session. */
class PetOverlayService : Service() {
    companion object { val captureHidden = kotlinx.coroutines.flow.MutableStateFlow(false) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private var view: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private var expanded = false
    private var shortcut = false
    private var task: PhoneTask? = null
    private var name = "女仆"
    private var avatar = ""
    private var lastAlert = ""
    private val openedAt = System.currentTimeMillis()
    private var quickReply = ""
    private var chatting = false
    private var conversation: Conversation? = null
    private var conversationId = ""
    private var activePersona = ""
    private var workMode = false
    private var submitting = false
    private var pendingGoal = ""
    private var pendingFolder = ""
    private var bulkTarget: PhoneTask? = null
    private var pendingRoutine = false
    private var availableBottom = 0
    private var draft = ""
    private var dark = false
    private var destroyed = false
    private var themeMode = "system"
    private val lookListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == WorkDefaults.ROUTINE) pendingRoutine = WorkDefaults.routine(this)
        if (key == CompanionAppearance.AVATAR || key == CompanionAppearance.POPUPS || key == WorkDefaults.ROUTINE) serviceScope.launch { render() }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val channel = "phone_companion"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "屏幕边缘陪伴", NotificationManager.IMPORTANCE_LOW))
        val close = PendingIntent.getService(this, 42, Intent(this, javaClass).setAction("close"), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channel).setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("陪伴已开启").setContentText("点按聊天 · 随时关闭")
            .setOngoing(true).addAction(0, "关闭悬浮头像", close).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(4102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(4102, notification)
        wm = getSystemService(WindowManager::class.java)
        val prefs = TaskActions.preferences(this)
        ScreenCompanion.enabled.value = prefs.getBoolean(ScreenCompanion.REMEMBER, false) && prefs.getStringSet(ScreenCompanion.APPS, emptySet()).orEmpty().isNotEmpty()
        pendingRoutine = WorkDefaults.routine(this)
        prefs.registerOnSharedPreferenceChangeListener(lookListener)
        CompanionRuntime.running.value = true
        params = WindowManager.LayoutParams(dp(80), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt("pet_x", 0); y = prefs.getInt("pet_y", dp(180))
            }
        serviceScope.launch {
            val settings = SettingsRepository(this@PetOverlayService).settings.first()
            dark = settings.themeMode == "dark" || (settings.themeMode == "system" && resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            AssistantStore(this@PetOverlayService).snapshot().firstOrNull { it.id == settings.activeAssistantId }?.let {
                name = it.displayName; avatar = it.avatarPath.orEmpty()
            }
            render()
            launch { captureHidden.collect { hidden -> view?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE } }
            launch { ScreenShare.running.collect { render() } }
            launch { ScreenCompanion.enabled.collect { observing ->
                val pause = PendingIntent.getService(this@PetOverlayService, 43, Intent(this@PetOverlayService, PetOverlayService::class.java).setAction("pause_screen"), PendingIntent.FLAG_IMMUTABLE)
                val notice = NotificationCompat.Builder(this@PetOverlayService, channel).setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle(if (observing) "页面陪伴已开启" else "陪伴已开启")
                    .setContentText(if (observing) "按问候间隔读取已选应用的可见文字 · 可随时暂停" else "点按聊天 · 随时关闭")
                    .setOngoing(true).addAction(0, "关闭悬浮头像", close)
                if (observing) notice.addAction(0, "暂停页面陪伴", pause)
                getSystemService(NotificationManager::class.java).notify(4102, notice.build())
                render()
            } }
            launch {
                combine(SettingsRepository(this@PetOverlayService).settings, AssistantStore(this@PetOverlayService).assistantsFlow,
                    ConversationStore(this@PetOverlayService).conversationsFlow) { settings, assistants, chats -> Triple(settings, assistants, chats) }
                    .collect { (latest, assistants, chats) ->
                        themeMode = latest.themeMode
                        dark = themeMode == "dark" || (themeMode == "system" && resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
                        if (activePersona != latest.activeAssistantId) { activePersona = latest.activeAssistantId; conversationId = ""; quickReply = "" }
                        conversation = chats.firstOrNull { it.id == conversationId } ?: chats.filter { it.assistantId == activePersona }.maxByOrNull { it.updatedAt }
                        conversationId = conversation?.id.orEmpty()
                        assistants.firstOrNull { it.id == (conversation?.assistantId ?: activePersona) }?.let { name = it.displayName; avatar = it.avatarPath.orEmpty() }
                        render()
                    }
            }
            launch { TaskStore.of(this@PetOverlayService).revision.collect {
                val tasks = withContext(Dispatchers.IO) { TaskStore.of(this@PetOverlayService).all() }
                val next = tasks.firstOrNull { it.state in setOf(TaskState.APPROVAL, TaskState.PERMISSION, TaskState.QUESTION, TaskState.FAILED) }
                    ?: tasks.firstOrNull { TaskActions.active(it.state) } ?: tasks.firstOrNull()
                if (next == task) return@collect
                task = next
                val t = task
                if (t != null && t.state in setOf(TaskState.QUESTION, TaskState.APPROVAL, TaskState.PERMISSION, TaskState.DONE, TaskState.FAILED)) {
                    val alert = "${t.id}:${t.state}:${t.cursor}"
                    if (t.updatedAt >= openedAt && lastAlert != alert && CompanionAppearance.popups(this@PetOverlayService) && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                        lastAlert = alert; expanded = true; workMode = true
                    }
                }
                render()
            } }
            launch { PetMessages.notice.collect { notice ->
                if (notice.isNotBlank()) {
                    conversationId = PetMessages.conversationId
                    conversation = withContext(Dispatchers.IO) { ConversationStore(this@PetOverlayService).snapshot().firstOrNull { it.id == conversationId } }
                    AssistantStore(this@PetOverlayService).snapshot().firstOrNull { it.id == conversation?.assistantId }?.let { name = it.displayName; avatar = it.avatarPath.orEmpty() }
                    workMode = false
                    if (CompanionAppearance.popups(this@PetOverlayService) && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) expanded = true
                    render()
                }
            } }
            launch { while (isActive) {
                withContext(Dispatchers.IO) { TriggerEngine.poll(this@PetOverlayService) }
                delay(15_000)
            } }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "close") stopSelf()
        if (intent?.action == "pause_screen") ScreenCompanion.stop(this)
        return START_NOT_STICKY // Never secretly restart a user-dismissed overlay.
    }
    private fun render() {
        if (destroyed) return
        if (!::wm.isInitialized || !Settings.canDrawOverlays(this)) { stopSelf(); return }
        val old = view
        val focused = old?.findViewWithTag<EditText>("draft")?.hasFocus() == true
        val previousHeight = params.height
        old?.findViewWithTag<EditText>("draft")?.let { draft = it.text.toString() }
        val ui = CompanionViews(this, dark)
        val panel = ui.column().apply { setPadding(dp(if (expanded) 12 else 6), dp(if (expanded) 12 else 6), dp(if (expanded) 12 else 6), dp(if (expanded) 12 else 6)) }
        val path = CompanionAppearance.resolve(CompanionAppearance.avatar(this), if (workMode) task?.avatarPath else null, avatar)
        val title = if (workMode) task?.characterName ?: name else name
        val state = if (chatting) "正在回复" else if (workMode) task?.statusLabel ?: "可以交代一件事" else if (ScreenShare.running.value) "画面共享中" else if (ScreenCompanion.enabled.value) "文字感知已开启" else if (PetMessages.notice.value.isNotBlank()) "有话想和你说" else "陪着你"
        if (!expanded) {
            val bubble = FrameLayout(this)
            val photo = ui.avatar(path, 52, title).apply { contentDescription = title + "，" + state + "，点按展开，长按菜单" }
            bubble.addView(photo); attachDrag(photo)
            bubble.addView(View(this).apply { background = GradientDrawable().apply { setColor(ui.accent); shape = GradientDrawable.OVAL; setStroke(dp(2), ui.surface) } },
                FrameLayout.LayoutParams(dp(12), dp(12), Gravity.BOTTOM or Gravity.END))
            panel.addView(bubble)
        } else {
            panel.background = ui.shape(ui.surface, 24); panel.elevation = dp(8).toFloat()
            val grip = FrameLayout(this).apply { contentDescription = "拖动悬浮窗口" }
            grip.addView(View(this).apply { background = ui.shape(ui.muted, 3) }, FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER))
            attachDrag(grip, false)
            panel.addView(grip, LinearLayout.LayoutParams(-1, dp(24)))
            panel.addView(ui.header(title, state, path,
                { expanded = false; shortcut = false; PetMessages.notice.value = ""; render() }, { stopSelf() }, { attachDrag(it, false) }))
            val tabs = ui.row().apply { gravity = Gravity.TOP }
            listOf("聊天", "工作").forEachIndexed { i, label ->
                tabs.addView(ui.action(label, (i == 1) == workMode) { workMode = i == 1; shortcut = false; quickReply = ""; render() },
                    LinearLayout.LayoutParams(0, dp(38), 1f).apply { topMargin = dp(4); marginEnd = dp(4) })
            }
            panel.addView(tabs)
            if (!workMode) {
                val controls = ui.row()
                if (ScreenCompanion.enabled.value || ScreenShare.running.value) {
                    controls.addView(ui.action(if (chatting) "正在看…" else "看一眼", true) { lookAtScreen() }, LinearLayout.LayoutParams(0, dp(38), 1f))
                    controls.addView(ui.action("停止感知", false) { ScreenCompanion.stop(this); render() }, LinearLayout.LayoutParams(0, dp(38), 1f))
                    if (!ScreenShare.running.value) controls.addView(ui.action("共享画面", false) { ScreenShare.start(this) }, LinearLayout.LayoutParams(0, dp(38), 1f))
                } else controls.addView(ui.action("共享画面给她看", false) { ScreenShare.start(this) }, LinearLayout.LayoutParams(-1, dp(38)))
                panel.addView(controls)
            }
            val body = ui.column().apply { setPadding(0, dp(10), 0, dp(8)) }
            fun action(text: String, primary: Boolean = false, click: () -> Unit) { body.addView(ui.action(text, primary, click)) }
            if (shortcut) {
                action("打开任务列表") { startActivity(TaskNotices.openIntent(this)) }
                action("头像与权限设置") { TaskNavigation.companion.value = true; startActivity(TaskNotices.openIntent(this)) }
                action("恢复窗口大小") { TaskActions.preferences(this).edit().remove("pet_width").remove("pet_height").apply(); render() }
            } else if (workMode) {
                if (bulkTarget != null) {
                    val target = bulkTarget!!
                    body.addView(ui.label("任务：${target.goal}", 13))
                    body.addView(ui.label(com.miniichat.tasks.agent.RoutineApproval.explanation, 13))
                    action("允许本任务常规操作", true) { respond(target, "allow_routine"); bulkTarget = null; render() }
                    action("取消") { bulkTarget = null; render() }
                } else if (pendingGoal.isNotBlank()) {
                    body.addView(ui.label(pendingGoal))
                    body.addView(ui.label("文件范围：" + FolderSelection.label(pendingFolder) + "；需要读取文件时再问你。", 13, true))
                    action(if (submitting) "正在准备…" else "重试提交", true) { if (!submitting) submitTask() }
                    body.addView(Switch(this).apply {
                        text = "本任务常规操作自动允许"; setTextColor(ui.ink); isChecked = pendingRoutine
                        setOnCheckedChangeListener { _, enabled -> pendingRoutine = enabled }
                    })
                    body.addView(ui.label(com.miniichat.tasks.agent.RoutineApproval.explanation, 11, true))
                    action("先不做") { if (!submitting) { pendingGoal = ""; render() } }
                } else task?.let { t ->
                    body.addView(ui.label(t.goal, 15))
                    body.addView(ui.label(t.detail, 13, true).apply { setPadding(0, dp(8), 0, dp(8)) })
                    when (t.state) {
                        TaskState.APPROVAL -> {
                            action("允许一次", true) { respond(t, "approve") }
                            if (t.engineVersion >= 2) action("本任务内自动允许") { bulkTarget = t; render() }
                            if (t.approvedSuggestion && (t.engineVersion < 2 || t.steps.getOrNull(t.cursor)?.let { com.miniichat.tasks.agent.AgentPolicy.canRemember(it) } == true))
                                action("此范围内同类操作以后允许") { respond(t, "always") }
                            action("取消任务") { respond(t, "cancel") }
                        }
                        TaskState.QUESTION -> {
                            if (t.neededPermission == "handoff") action("打开系统操作", true) { startActivity(TaskNotices.openIntent(this)) }
                            t.steps.getOrNull(t.cursor)?.options?.forEach { option -> action(option) { TaskActions.respond(this, t.id, "answer", option, t.approvalToken) } }
                        }
                        TaskState.PERMISSION -> if (t.neededPermission == "file_consent") action("允许读取此目录并继续", true) { respond(t, "allow_files") }
                            else action("去系统授权", true) { TaskNavigation.permission.value = t.neededPermission; startActivity(TaskNotices.openIntent(this)) }
                        TaskState.FAILED, TaskState.PAUSED -> action("继续", true) { respond(t, "resume") }
                        else -> if (TaskActions.active(t.state)) action("暂停") { respond(t, "pause") }
                    }
                } ?: body.addView(ui.label("整理各种文件、查资料、写清单……直接告诉我。重要操作会先问你。"))
                action("工作权限设置") { TaskNavigation.permission.value = "apps"; startActivity(TaskNotices.openIntent(this)) }
            } else {
                if (ScreenCompanion.enabled.value || ScreenShare.running.value) {
                    body.addView(ui.label(if (ScreenShare.running.value) ScreenShare.status.value else ScreenCompanion.status.value, 11, true))
                }
                val messages = conversation?.messages.orEmpty().takeLast(30)
                if (messages.isEmpty()) body.addView(ui.label(PetMessages.notice.value.ifBlank { "我在，想聊点什么？" }))
                messages.forEach { message ->
                    body.addView(ui.label(if (message.role == "user") "你" else name, 11, true).apply { setPadding(0, dp(8), 0, dp(4)) })
                    body.addView(ui.label(message.content.ifBlank { "[图片消息，请在应用内查看]" }).apply {
                        background = ui.shape(ui.inset, 14); setPadding(dp(12), dp(10), dp(12), dp(10)); setTextIsSelectable(true)
                    })
                }
                if (messages.isNotEmpty()) action("在应用内查看完整聊天") {
                    val intent = Intent(this, com.miniichat.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(com.miniichat.proactive.ProactiveNotifications.EXTRA_SOURCE, "normal")
                        .putExtra(com.miniichat.proactive.ProactiveNotifications.EXTRA_CONVERSATION, conversationId)
                    startActivity(intent)
                }
            }
            if (quickReply.isNotBlank()) body.addView(ui.label(quickReply, 13, true).apply { setPadding(0, dp(8), 0, dp(8)) })
            val scroll = ScrollView(this).apply { addView(body); isFillViewport = false; isVerticalScrollBarEnabled = true }
            panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
            if (!workMode) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            val footer = ui.row()
            val input = EditText(this).apply {
                tag = "draft"; hint = if (workMode) "交代事情，或回答当前问题…" else "和" + name + "说句话…"
                setText(draft); textSize = 14f; maxLines = 3; setTextColor(ui.ink); setHintTextColor(ui.muted)
                background = ui.shape(ui.inset, 12); setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnTouchListener { v, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        view?.let { wm.updateViewLayout(it, params) }; v.requestFocus()
                        v.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    false
                }
            }
            footer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            footer.addView(ui.action(if (chatting || submitting) "稍候" else if (workMode) "提交" else "发送", true) {
                val text = input.text.toString().trim()
                if (text.isNotEmpty() && !chatting && !submitting) {
                    draft = ""; input.setText("")
                    if (!workMode) chat(text)
                    else if (task?.state == TaskState.QUESTION) { val t = task!!; TaskActions.respond(this, t.id, "answer", text, t.approvalToken) }
                    else { pendingGoal = text; pendingFolder = FolderSelection.saved(this); submitTask() }
                }
            }.apply { isEnabled = !chatting && !submitting }, LinearLayout.LayoutParams(dp(64), -2).apply { marginStart = dp(8) })
            panel.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            val resize = ui.label("↘", 20, true).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; contentDescription = "调整悬浮窗大小"; tag = "resize" }
            attachResize(resize)
            panel.addView(resize, LinearLayout.LayoutParams(dp(48), dp(28)).apply { gravity = Gravity.END })
            // Keep the fixed input footer above the IME; the conversation alone scrolls.
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(panel) { _, insets ->
                val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                availableBottom = resources.displayMetrics.heightPixels - maxOf(ime, bars.bottom)
                val available = resources.displayMetrics.heightPixels - maxOf(ime, bars.bottom) - bars.top - dp(16)
                val height = OverlaySizing.fit(dp(TaskActions.preferences(this).getInt("pet_height", 560)), dp(330), available)
                if (view === panel && expanded && params.height != height) {
                    params.height = height
                    params.y = params.y.coerceIn(bars.top, maxOf(bars.top, available - height + bars.top))
                    panel.post { if (view === panel && !destroyed) runCatching { wm.updateViewLayout(panel, params) } }
                }
                insets
            }
        }
        if (old != null) runCatching { wm.removeView(old) }
        view = panel
        panel.visibility = if (captureHidden.value) View.INVISIBLE else View.VISIBLE
        params.width = if (expanded) OverlaySizing.fit(dp(TaskActions.preferences(this).getInt("pet_width", 360)), dp(280), resources.displayMetrics.widthPixels - dp(24)) else dp(64)
        params.height = if (expanded) { if (focused && previousHeight > 0) previousHeight else OverlaySizing.fit(dp(TaskActions.preferences(this).getInt("pet_height", 560)), dp(330), resources.displayMetrics.heightPixels - dp(64)) } else WindowManager.LayoutParams.WRAP_CONTENT
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or if (expanded && focused) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        val estimatedHeight = if (expanded) params.height else dp(64)
        params.x = params.x.coerceIn(0, (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(dp(24), (resources.displayMetrics.heightPixels - estimatedHeight - dp(32)).coerceAtLeast(dp(24)))
        runCatching { wm.addView(panel, params) }.onFailure { stopSelf() }
        if (focused && expanded) panel.findViewWithTag<EditText>("draft")?.requestFocus()
    }
    private fun submitTask() {
        val goal = pendingGoal; val folder = pendingFolder
        submitting = true; render()
        serviceScope.launch {
            try {
                val created = withContext(Dispatchers.IO) { TaskActions.create(this@PetOverlayService, goal, rootDirectory = folder, autoAllowRoutine = pendingRoutine, fileConsent = false) }
                task = created; pendingGoal = ""; quickReply = "已保存，会在后台继续；需要确认时来问你。"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { quickReply = e.message?.take(150) ?: "暂时无法创建任务" }
            finally { submitting = false; render() }
        }
    }
    private fun lookAtScreen() {
        if (chatting) return
        chatting = true; render()
        serviceScope.launch {
            try { quickReply = withContext(Dispatchers.IO) { ScreenCompanion.test(this@PetOverlayService, observe = true) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { ScreenCompanion.reportFailure(e); quickReply = e.message?.take(180) ?: "当前页面暂时无法读取" }
            finally { chatting = false; render() }
        }
    }
    private fun attachDrag(handle: View, toggleOnTap: Boolean = true) {
        var sx = 0f; var sy = 0f; var x = 0; var y = 0; var down = 0L; var dragged = false
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = event.rawX; sy = event.rawY; x = params.x; y = params.y; down = event.eventTime; dragged = false }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - sx) + kotlin.math.abs(event.rawY - sy) > dp(8)) dragged = true
                    if (dragged) {
                        params.x = (x + event.rawX - sx).toInt().coerceIn(0, (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
                        val bottom = if (expanded && availableBottom > 0) availableBottom else resources.displayMetrics.heightPixels
                        params.y = (y + event.rawY - sy).toInt().coerceIn(dp(24), (bottom - (if (expanded) params.height else dp(64)) - dp(16)).coerceAtLeast(dp(24)))
                        view?.let { runCatching { wm.updateViewLayout(it, params) } } }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged && toggleOnTap) { handle.performClick(); shortcut = event.eventTime - down > 500; expanded = if (shortcut) true else !expanded }
                    else if (dragged && !expanded) params.x = if (params.x < resources.displayMetrics.widthPixels / 2) 0 else resources.displayMetrics.widthPixels - params.width
                    TaskActions.preferences(this).edit().putInt("pet_x", params.x).putInt("pet_y", params.y).apply()
                    if (!dragged && toggleOnTap) render()
                    else view?.let { runCatching { wm.updateViewLayout(it, params) } }
                }
            }; true
        }
    }
    private fun attachResize(handle: View) {
        var startX = 0f; var startY = 0f; var width = 0; var height = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; width = params.width; height = params.height }
                MotionEvent.ACTION_MOVE -> {
                    val bottom = if (availableBottom > 0) availableBottom else resources.displayMetrics.heightPixels - dp(24)
                    params.width = OverlaySizing.fit(width + (event.rawX - startX).toInt(), dp(280), resources.displayMetrics.widthPixels - params.x - dp(12))
                    params.height = OverlaySizing.fit(height + (event.rawY - startY).toInt(), dp(330), bottom - params.y - dp(12))
                    view?.let { runCatching { wm.updateViewLayout(it, params) } }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val density = resources.displayMetrics.density
                    TaskActions.preferences(this).edit().putInt("pet_width", (params.width / density).toInt())
                        .putInt("pet_height", (params.height / density).toInt()).apply()
                    handle.performClick()
                }
            }
            true
        }
    }
    private fun chat(text: String) {
        chatting = true; quickReply = "正在回复…"; render()
        serviceScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { CompanionConversation.send(this@PetOverlayService, text, conversationId) }
                conversation = saved; conversationId = saved.id; quickReply = ""; PetMessages.notice.value = ""
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { runCatching { com.miniichat.error.AppErrorStore(this@PetOverlayService).record(e,
                    com.miniichat.error.AppErrorContext(area = com.miniichat.error.ErrorArea.CHAT, operation = com.miniichat.error.ErrorOperation.SEND_MESSAGE)) } }
                quickReply = "暂时没有收到回复。已发送的消息保留在聊天记录；详情可在错误报告查看。"
            }
            finally { chatting = false; render() }
        }
    }
    private fun respond(task: PhoneTask, action: String) { TaskActions.respond(this, task.id, action, token = task.approvalToken) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        dark = themeMode == "dark" || (themeMode == "system" && newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        render()
    }
    override fun onDestroy() {
        destroyed = true
        CompanionRuntime.running.value = false
        ScreenCompanion.enabled.value = false
        ScreenShare.stop(this)
        TaskActions.preferences(this).unregisterOnSharedPreferenceChangeListener(lookListener)
        serviceScope.cancel(); view?.let { runCatching { wm.removeView(it) } }; view = null
        super.onDestroy()
    }
}
