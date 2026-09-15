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
    fun show(text: String) { notice.value = text.take(1200) }
}

/** A visible, user-started companion. No accessibility, screen capture or invisible touch injection. */
class PetOverlayService : Service() {
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
    private var quickReply = ""
    private var chatting = false
    private val quickHistory = mutableListOf<ChatMessage>()
    private var draft = ""
    private var dark = false
    private var destroyed = false
    private var themeMode = "system"
    private val lookListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == CompanionAppearance.AVATAR || key == CompanionAppearance.POPUPS) serviceScope.launch { render() }
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
            launch {
                combine(SettingsRepository(this@PetOverlayService).settings, AssistantStore(this@PetOverlayService).assistantsFlow) { settings, assistants -> settings to assistants }
                    .collect { (latest, assistants) ->
                        themeMode = latest.themeMode
                        dark = themeMode == "dark" || (themeMode == "system" && resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
                        assistants.firstOrNull { it.id == latest.activeAssistantId }?.let { name = it.displayName; avatar = it.avatarPath.orEmpty() }
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
                    if (lastAlert != alert && CompanionAppearance.popups(this@PetOverlayService) && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                        lastAlert = alert; expanded = true
                    }
                }
                render()
            } }
            launch { PetMessages.notice.collect { if (it.isNotBlank() && CompanionAppearance.popups(this@PetOverlayService) && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                expanded = true; render()
            } } }
            launch { while (isActive) {
                withContext(Dispatchers.IO) { TriggerEngine.poll(this@PetOverlayService) }
                delay(15_000)
            } }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "close") stopSelf()
        return START_NOT_STICKY // Never secretly restart a user-dismissed overlay.
    }
    private fun render() {
        if (destroyed) return
        if (!::wm.isInitialized || !Settings.canDrawOverlays(this)) { stopSelf(); return }
        val old = view
        old?.findViewWithTag<EditText>("draft")?.let { draft = it.text.toString() }
        val ui = CompanionViews(this, dark)
        val panel = ui.column().apply { setPadding(dp(if (expanded) 16 else 6), dp(if (expanded) 16 else 6), dp(if (expanded) 16 else 6), dp(if (expanded) 16 else 6)) }
        val path = CompanionAppearance.resolve(CompanionAppearance.avatar(this), task?.avatarPath, avatar)
        val title = task?.characterName ?: name
        val state = if (chatting) "正在回复" else task?.statusLabel ?: "陪着你"
        if (!expanded) {
            val bubble = FrameLayout(this)
            val photo = ui.avatar(path, 52, title).apply { contentDescription = "$title，$state，点按展开，长按菜单" }
            bubble.addView(photo); attachDrag(photo)
            val dot = View(this).apply {
                background = GradientDrawable().apply { setColor(if (task?.state in setOf(TaskState.APPROVAL, TaskState.QUESTION, TaskState.FAILED)) Color.rgb(231, 155, 64) else ui.accent); shape = GradientDrawable.OVAL; setStroke(dp(2), ui.surface) }
            }
            bubble.addView(dot, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.BOTTOM or Gravity.END))
            panel.addView(bubble)
        } else {
            panel.background = ui.shape(ui.surface, 24); panel.elevation = dp(8).toFloat()
            panel.addView(ui.header(title, state, path,
                { expanded = false; shortcut = false; PetMessages.notice.value = ""; render() },
                { stopSelf() }, ::attachDrag))
            val body = ui.column().apply { setPadding(0, dp(16), 0, dp(4)) }
            fun action(text: String, primary: Boolean = false, click: () -> Unit) { body.addView(ui.action(text, primary, click)) }
            if (shortcut) {
                action("任务中心") { startActivity(TaskNotices.openIntent(this)) }
                if (task?.let { TaskActions.active(it.state) } == true) action("暂停任务") { task?.let { respond(it, "pause") } }
                action("陪伴设置") { TaskNavigation.companion.value = true; startActivity(TaskNotices.openIntent(this)) }
            } else {
                task?.let { t ->
                    body.addView(ui.label(t.goal, 15).apply { setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 2 })
                    if (t.state == TaskState.APPROVAL && t.steps.getOrNull(t.cursor) != null) {
                        val step = t.steps[t.cursor]
                        val description = buildString {
                            append(com.miniichat.tasks.agent.AgentPolicy.specs[step.tool]?.title ?: step.tool)
                            if (step.source.isNotBlank()) append("\n从：${step.source}")
                            if (step.destination.isNotBlank()) append("\n到：${step.destination}")
                            step.arguments.filterKeys { it !in setOf("snapshot", "node") }.forEach { (key, value) -> append("\n$key：$value") }
                            append("\n${step.reason}")
                        }
                        body.addView(ui.label(description, 13, true).apply { setPadding(0, dp(8), 0, dp(4)) })
                    } else if (t.detail.isNotBlank()) body.addView(ui.label(t.detail, 13, true).apply { setPadding(0, dp(8), 0, dp(4)) })
                    when (t.state) {
                        TaskState.APPROVAL -> {
                            action("允许一次", true) { respond(t, "approve") }
                            if (t.approvedSuggestion && (t.engineVersion < 2 || t.steps.getOrNull(t.cursor)?.let { com.miniichat.tasks.agent.AgentPolicy.canRemember(it) } == true))
                                action("此范围内以后允许") { respond(t, "always") }
                            action("取消任务") { respond(t, "cancel") }
                        }
                        TaskState.QUESTION -> {
                            if (t.neededPermission == "handoff") action("打开操作", true) { startActivity(TaskNotices.openIntent(this)) }
                            t.steps.getOrNull(t.cursor)?.options?.forEach { option -> action(option) { TaskActions.respond(this, t.id, "answer", option, t.approvalToken) } }
                        }
                        TaskState.PERMISSION -> action("去授权", true) { startActivity(TaskNotices.openIntent(this)) }
                        TaskState.FAILED, TaskState.PAUSED -> action("继续任务", true) { respond(t, "resume") }
                        else -> Unit
                    }
                }
                if (PetMessages.notice.value.isNotBlank()) body.addView(ui.label(PetMessages.notice.value).apply { setPadding(0, dp(12), 0, dp(12)) })
                if (quickReply.isNotBlank()) body.addView(ui.label(quickReply).apply { setPadding(0, dp(12), 0, dp(12)) })
                if (task == null && quickReply.isBlank() && PetMessages.notice.value.isBlank()) body.addView(ui.label("我在。有什么想说的？", 15).apply { setPadding(0, dp(8), 0, dp(16)) })
                val input = EditText(this).apply {
                    tag = "draft"; hint = "说点什么…"; setText(draft); textSize = 15f
                    setTextColor(ui.ink); setHintTextColor(ui.muted); maxLines = 3
                    background = ui.shape(ui.inset, 14); setPadding(dp(12), dp(12), dp(12), dp(12))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
                    setOnFocusChangeListener { _, focused -> if (focused) {
                        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        view?.let { wm.updateViewLayout(it, params) }
                    } }
                    setOnTouchListener { v, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            view?.let { wm.updateViewLayout(it, params) }; v.requestFocus()
                            v.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
                        }
                        if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                        false
                    }
                }
                body.addView(input)
                val actions = ui.row()
                actions.addView(ui.action("办件事") { TaskNavigation.chatDraft.value = input.text.toString(); startActivity(TaskNotices.openIntent(this)); expanded = false; render() },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(8); marginEnd = dp(8) })
                actions.addView(ui.action(if (chatting) "回复中…" else "发送", true) {
                    val text = input.text.toString().trim()
                    if (text.isNotEmpty() && !chatting) { draft = ""; input.setText(""); chat(text) }
                }.apply { isEnabled = !chatting }, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(8) })
                body.addView(actions)
            }
            panel.addView(ui.scroll(body, (resources.displayMetrics.heightPixels * 0.5).toInt()))
        }
        if (old != null) runCatching { wm.removeView(old) }
        view = panel
        params.width = if (expanded) minOf(dp(328), resources.displayMetrics.widthPixels - dp(24)) else dp(64)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        panel.measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST))
        params.x = params.x.coerceIn(0, (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (resources.displayMetrics.heightPixels - panel.measuredHeight - dp(32)).coerceAtLeast(0))
        runCatching { wm.addView(panel, params) }.onFailure { stopSelf() }
    }
    private fun attachDrag(handle: View) {
        var sx = 0f; var sy = 0f; var x = 0; var y = 0; var down = 0L; var dragged = false
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = event.rawX; sy = event.rawY; x = params.x; y = params.y; down = event.eventTime; dragged = false }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - sx) + kotlin.math.abs(event.rawY - sy) > dp(8)) dragged = true
                    if (dragged) { params.x = (x + event.rawX - sx).toInt(); params.y = (y + event.rawY - sy).toInt()
                        view?.let { runCatching { wm.updateViewLayout(it, params) } } }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) { handle.performClick(); shortcut = event.eventTime - down > 500; expanded = if (shortcut) true else !expanded }
                    else params.x = if (params.x < resources.displayMetrics.widthPixels / 2) 0 else resources.displayMetrics.widthPixels - params.width
                    TaskActions.preferences(this).edit().putInt("pet_x", params.x).putInt("pet_y", params.y).apply()
                    render()
                }
            }; true
        }
    }
    private fun chat(text: String) {
        chatting = true; quickReply = "正在回复…"; render()
        serviceScope.launch {
            try {
                val answer = withContext(Dispatchers.IO) {
                    val settings = SettingsRepository(this@PetOverlayService).settings.first()
                    val provider = ProviderStore(this@PetOverlayService).snapshot().firstOrNull { it.id == settings.activeProviderId && it.enabled }
                        ?: error("请先选择 AI 服务")
                    val assistant = AssistantStore(this@PetOverlayService).snapshot().firstOrNull { it.id == settings.activeAssistantId }
                    val client = LlmClient()
                    try { client.completeDetailed(provider, settings.activeModel,
                        listOf(ChatMessage("system", assistant?.systemPrompt.orEmpty() + "\n你正在手机悬浮窗快速聊天，没有执行工具。不要声称已经操作手机，需要办事请让用户点交给我做。")) + quickHistory.takeLast(12) + ChatMessage("user", text.take(4000)),
                        temperature = 0.7f, structuredJson = false, requestTimeoutMillis = 120000, maxOutputTokens = 1200).content
                    } finally { client.close() }
                }
                quickHistory += ChatMessage("user", text); quickHistory += ChatMessage("assistant", answer)
                quickReply = answer
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { quickReply = "暂时无法回复，请检查网络和当前 AI 服务。任务进度不受影响。"
            } finally { chatting = false; render() }
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
        TaskActions.preferences(this).unregisterOnSharedPreferenceChangeListener(lookListener)
        serviceScope.cancel(); view?.let { runCatching { wm.removeView(it) } }; view = null
        super.onDestroy()
    }
}
