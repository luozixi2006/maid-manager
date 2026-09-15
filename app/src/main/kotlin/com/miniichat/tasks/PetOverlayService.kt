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
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val channel = "phone_companion"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "屏幕边缘陪伴", NotificationManager.IMPORTANCE_LOW))
        val close = PendingIntent.getService(this, 42, Intent(this, javaClass).setAction("close"), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channel).setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("屏幕边缘陪伴已开启").setContentText("可拖动头像；长按快捷操作。触发检测会消耗少量电量。")
            .setOngoing(true).addAction(0, "关闭悬浮头像", close).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(4102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(4102, notification)
        wm = getSystemService(WindowManager::class.java)
        val prefs = TaskActions.preferences(this)
        params = WindowManager.LayoutParams(dp(80), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt("pet_x", 0); y = prefs.getInt("pet_y", dp(180))
            }
        serviceScope.launch {
            val settings = SettingsRepository(this@PetOverlayService).settings.first()
            AssistantStore(this@PetOverlayService).snapshot().firstOrNull { it.id == settings.activeAssistantId }?.let {
                name = it.displayName; avatar = it.avatarPath.orEmpty()
            }
            render()
            launch { TaskStore.of(this@PetOverlayService).revision.collect {
                val tasks = withContext(Dispatchers.IO) { TaskStore.of(this@PetOverlayService).all() }
                val next = tasks.firstOrNull { it.state in setOf(TaskState.APPROVAL, TaskState.PERMISSION, TaskState.QUESTION, TaskState.FAILED) }
                    ?: tasks.firstOrNull { TaskActions.active(it.state) } ?: tasks.firstOrNull()
                if (next == task) return@collect
                task = next
                val t = task
                if (t != null && t.state in setOf(TaskState.QUESTION, TaskState.APPROVAL, TaskState.PERMISSION, TaskState.DONE, TaskState.FAILED)) {
                    val alert = "${t.id}:${t.state}:${t.cursor}"
                    if (lastAlert != alert && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                        lastAlert = alert; expanded = true
                    }
                }
                render()
            } }
            launch { PetMessages.notice.collect { if (it.isNotBlank() && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
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
        if (!::wm.isInitialized || !Settings.canDrawOverlays(this)) { stopSelf(); return }
        val old = view
        // Retain unsent text across live status refreshes.
        old?.findViewWithTag<EditText>("draft")?.let { draft = it.text.toString() }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(Color.rgb(249, 248, 246)); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.LTGRAY) }
            elevation = dp(6).toFloat()
        }
        val avatarView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            scaleType = ImageView.ScaleType.CENTER_CROP
            val path = task?.avatarPath?.ifBlank { avatar } ?: avatar
            if (path.isNotBlank() && File(path).isFile) setImageBitmap(BitmapFactory.decodeFile(path))
            else setImageResource(com.miniichat.R.mipmap.ic_launcher)
            contentDescription = "${task?.characterName ?: name}，点击快速聊天，长按快捷操作"
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.LTGRAY) }
        }
        panel.addView(avatarView)
        panel.addView(label(if (chatting) "正在思考" else task?.statusLabel ?: "空闲", 11).apply { gravity = Gravity.CENTER })
        attachDrag(avatarView)
        if (expanded) {
            if (shortcut) {
                button(panel, "打开任务中心") { startActivity(TaskNotices.openIntent(this)) }
                button(panel, "暂停当前任务") { task?.let { TaskActions.respond(this, it.id, "pause") } }
                button(panel, "关闭悬浮头像") { stopSelf() }
            } else {
                task?.let { t ->
                    panel.addView(label(t.detail.take(380), 13))
                    when (t.state) {
                        TaskState.APPROVAL -> {
                            button(panel, "允许一次") { respond(t, "approve") }
                            if (t.approvedSuggestion && (t.engineVersion < 2 || t.steps.getOrNull(t.cursor)?.let { com.miniichat.tasks.agent.AgentPolicy.canRemember(it) } == true))
                                button(panel, "该范围内同类操作以后允许") { respond(t, "always") }
                            button(panel, "取消任务") { respond(t, "cancel") }
                        }
                        TaskState.QUESTION -> {
                            if (t.neededPermission == "handoff") button(panel, "打开任务页完成系统操作") { startActivity(TaskNotices.openIntent(this)) }
                            t.steps.getOrNull(t.cursor)?.options?.forEach { option -> button(panel, option) { TaskActions.respond(this, t.id, "answer", option, t.approvalToken) } }
                        }
                        TaskState.PERMISSION -> button(panel, "去开启权限") { startActivity(TaskNotices.openIntent(this)) }
                        TaskState.FAILED, TaskState.PAUSED -> button(panel, "从原步骤继续") { respond(t, "resume") }
                        else -> Unit
                    }
                }
                if (PetMessages.notice.value.isNotBlank()) panel.addView(label(PetMessages.notice.value.take(400), 13))
                if (quickReply.isNotBlank()) panel.addView(label(quickReply.takeLast(700), 13))
                val input = EditText(this).apply {
                    tag = "draft"; hint = "聊一句，或交给我一件事"; setText(draft)
                    setTextColor(Color.BLACK); setHintTextColor(Color.DKGRAY); textSize = 14f; maxLines = 3
                    setOnFocusChangeListener { _, focused -> if (focused) {
                        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        view?.let { wm.updateViewLayout(it, params) }
                    } }
                    setOnTouchListener { v, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            view?.let { wm.updateViewLayout(it, params) }
                            v.requestFocus()
                            v.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                                .showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
                        }
                        false
                    }
                }
                panel.addView(input)
                button(panel, "发送聊天") {
                    val text = input.text.toString().trim()
                    if (text.isNotEmpty() && !chatting) { draft = ""; input.setText(""); chat(text) }
                }
                button(panel, "交给我做 · 先确认范围") {
                    TaskNavigation.chatDraft.value = input.text.toString()
                    startActivity(TaskNotices.openIntent(this)); expanded = false; render()
                }
            }
            button(panel, "收起") { expanded = false; shortcut = false; PetMessages.notice.value = ""; render() }
        }
        if (old != null) runCatching { wm.removeView(old) }
        view = panel
        params.width = dp(if (expanded) 288 else 80)
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        params.x = params.x.coerceIn(0, (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
        val scroll = panel // Keep drag handle at the top; cap expanded view to fit screen.
        params.height = if (expanded) (resources.displayMetrics.heightPixels * 0.75).toInt() else WindowManager.LayoutParams.WRAP_CONTENT
        // Scroll entire content when confirmation/text makes the panel taller than the display.
        if (expanded) {
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            while (panel.childCount > 0) { val child = panel.getChildAt(0); panel.removeViewAt(0); content.addView(child) }
            panel.addView(ScrollView(this).apply { addView(content); isFillViewport = false })
        }
        params.y = params.y.coerceIn(0, (resources.displayMetrics.heightPixels - if (expanded) params.height else dp(100)).coerceAtLeast(0))
        runCatching { wm.addView(scroll, params) }.onFailure { stopSelf() }
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
                    if (!dragged) { shortcut = event.eventTime - down > 500; expanded = if (shortcut) true else !expanded }
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
    private fun label(text: String, size: Int) = TextView(this).apply { this.text = text; setTextColor(Color.rgb(28, 28, 30)); textSize = size.toFloat(); setPadding(0, dp(5), 0, dp(5)) }
    private fun button(parent: LinearLayout, title: String, onClick: () -> Unit) {
        parent.addView(Button(this).apply { text = title; isAllCaps = false; textSize = 12f; setTextColor(Color.BLACK)
            filterTouchesWhenObscured = true; setOnClickListener { onClick() } })
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        serviceScope.cancel(); view?.let { runCatching { wm.removeView(it) } }; view = null
        super.onDestroy()
    }
}
