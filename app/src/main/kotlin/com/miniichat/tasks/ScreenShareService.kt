package com.miniichat.tasks

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Base64
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

/** No token, screenshot or screen contents are persisted. Each session needs OS consent. */
object ScreenShare {
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("画面共享未开启")
    internal var service: ScreenShareService? = null
    private val captureLock = Mutex()
    @Volatile var generation = 0L; private set
    internal fun changed() { generation++ }
    fun start(context: Context) {
        context.startActivity(Intent(context, ScreenShareConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun stop(context: Context) {
        changed(); running.value = false
        context.stopService(Intent(context, ScreenShareService::class.java))
        status.value = "画面共享已停止"
    }
    suspend fun capture(): String = captureLock.withLock {
        check(running.value && CompanionRuntime.running.value) { "请先开启画面共享，不需要无障碍权限" }
        withContext(Dispatchers.Main) {
            val active = service ?: error("请先开启画面共享，不需要无障碍权限")
            check(running.value && CompanionRuntime.running.value) { "画面共享已停止" }
            val epoch = generation
            PetOverlayService.captureHidden.value = true
            try {
                delay(350) // Exclude our own floating chat rather than showing it to the model.
                val image = active.capture()
                check(epoch == generation && running.value) { "画面共享已停止，未使用画面" }
                image
            } finally { PetOverlayService.captureHidden.value = false }
        }
    }
}

class ScreenShareConsentActivity : ComponentActivity() {
    private var awaitingSystem = false
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null && CompanionRuntime.running.value) {
            runCatching {
                startForegroundService(Intent(this, ScreenShareService::class.java)
                    .putExtra("result", result.resultCode).putExtra("consent", result.data))
            }.onFailure { ScreenShare.status.value = "系统未允许启动共享，请保持应用在前台后重试" }
        } else ScreenShare.status.value = "未共享画面；普通陪伴仍可使用"
        finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingSystem = savedInstanceState?.getBoolean("awaiting_system") ?: false
        if (awaitingSystem) return
        if (ScreenShare.running.value || !CompanionRuntime.running.value) { finish(); return }
        AlertDialog.Builder(this).setTitle("让她看到你分享的画面")
            .setMessage("点击查看或到人设的主动问候检查时间时，会截取一张当前共享画面，发送给当前人设的模型。需要支持图片的模型；不录制视频、不保存截图。\n\n建议在系统中只分享一个应用。若选择整个屏幕，切换应用后的内容也可能被发送，请避开聊天隐私、支付和密码页面。系统保护的画面不能读取。停止共享即可撤销。")
            .setPositiveButton("选择共享范围") { _, _ ->
                awaitingSystem = true
                consent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            }.setNegativeButton("取消") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("awaiting_system", awaitingSystem)
        super.onSaveInstanceState(outState)
    }
}

class ScreenShareService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var waiting: CompletableDeferred<String>? = null
    private var contentVisible = true
    private var closing = false
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf(); release() }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (display != null && width > 0 && height > 0) runCatching { resize(width, height) }.onFailure { stopSelf(); release() }
        }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) { contentVisible = isVisible }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); release(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        try {
            check(CompanionRuntime.running.value) { "请先开启悬浮陪伴" }
            @Suppress("DEPRECATION")
            val token = intent?.getParcelableExtra<Intent>("consent") ?: error("请重新授权画面共享")
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("screen_companion", "画面共享", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 4104, Intent(this, javaClass).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
            val notice = NotificationCompat.Builder(this, "screen_companion").setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("画面陪伴已开启").setContentText("查看或问候检查时发送单张画面给当前模型")
                .setOngoing(true).addAction(0, "停止共享", stop).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(4104, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(4104, notice)
            val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra("result", Activity.RESULT_CANCELED), token)
            projection = p
            p.registerCallback(callback, handler)
            val wm = getSystemService(WindowManager::class.java)
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            val bounds = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds else android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
            val (width, height) = dimensions(bounds.width(), bounds.height())
            reader = makeReader(width, height)
            display = p.createVirtualDisplay("Companion shared view", width, height, resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
            ScreenShare.service = this; ScreenShare.changed(); ScreenShare.running.value = true
            ScreenShare.status.value = "已共享画面 · 等待查看或主动问候检查"
        } catch (e: Exception) {
            ScreenShare.status.value = "画面共享启动失败，请重新在系统中授权"
            stopSelf(); release()
        }
        return START_NOT_STICKY
    }
    private fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1.0, 1280.0 / maxOf(width, height).coerceAtLeast(1))
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }
    private fun resize(width: Int, height: Int) {
        val (w, h) = dimensions(width, height)
        if (reader?.width == w && reader?.height == h) return
        val old = reader
        reader = makeReader(w, h)
        display?.resize(w, h, resources.configuration.densityDpi)
        display?.surface = reader!!.surface
        old?.close()
    }
    private fun makeReader(width: Int, height: Int): ImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { source ->
        source.setOnImageAvailableListener({ frames ->
            val frame = runCatching { frames.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            frame.use { image ->
                val pending = waiting ?: return@use // Drain, but do not keep/encode unsolicited frames.
                waiting = null
                if (getSystemService(KeyguardManager::class.java).isKeyguardLocked || !contentVisible || closing) {
                    pending.completeExceptionally(IllegalStateException("锁屏或共享应用不可见，未读取画面")); return@use
                }
                var padded: Bitmap? = null
                var cropped: Bitmap? = null
                try {
                    val plane = image.planes[0]
                    padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    val bytes = ByteArrayOutputStream().use { out ->
                        check(cropped.compress(Bitmap.CompressFormat.JPEG, 80, out))
                        out.toByteArray()
                    }
                    pending.complete("data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
                    ScreenShare.status.value = "已取得画面 · ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
                } catch (e: Exception) { pending.completeExceptionally(IllegalStateException("当前画面无法读取", e)) }
                finally { if (cropped !== padded) cropped?.recycle(); padded?.recycle() }
            }
        }, handler)
    }
    internal suspend fun capture(): String {
        check(!getSystemService(KeyguardManager::class.java).isKeyguardLocked && contentVisible) { "请解锁并显示共享应用" }
        check(projection != null && !closing) { "画面共享已停止" }
        val pending = CompletableDeferred<String>()
        waiting = pending
        return try {
            // A static page may emit no further frames. A new surface requests a fresh frame
            // without reusing the projection consent or creating another virtual display.
            val old = reader ?: error("画面共享已停止")
            val fresh = makeReader(old.width, old.height)
            reader = fresh
            display?.surface = fresh.surface
            old.close()
            withTimeout(5000) { pending.await() }
        }
        catch (e: TimeoutCancellationException) { error("没有新的共享画面，请切回所选应用后重试") }
        finally { if (waiting === pending) waiting = null }
    }
    private fun release() {
        if (closing) return
        closing = true
        waiting?.completeExceptionally(IllegalStateException("画面共享已停止")); waiting = null
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.unregisterCallback(callback); projection?.stop(); projection = null
        if (ScreenShare.service === this) {
            ScreenShare.service = null; ScreenShare.changed(); ScreenShare.running.value = false
            ScreenShare.status.value = "画面共享已停止"
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onDestroy() { release(); super.onDestroy() }
}
