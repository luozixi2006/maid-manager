package com.miniichat.tasks

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.*
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.miniichat.data.Conversation
import com.miniichat.data.Message
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "zh-rCN-w393dp-h852dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingWindowTest {
    @Test fun compactButtonsKeepLongLabelsAndClickActions() {
        val ui = CompanionViews(RuntimeEnvironment.getApplication(), false)
        var clicked = false
        val button = ui.action("允许本任务常规操作") { clicked = true }
        button.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(96), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        button.layout(0, 0, button.measuredWidth, button.measuredHeight)
        assertEquals(ui.dp(38), button.minHeight)
        assertEquals(ui.dp(6), button.paddingTop)
        assertTrue(button.lineCount > 1)
        assertTrue(button.height >= button.layout.height + button.paddingTop + button.paddingBottom)
        button.performClick()
        assertTrue(clicked)
        val close = ui.icon("×", "关闭陪伴") {}
        assertEquals(ui.dp(38), close.layoutParams.width)
        assertEquals(ui.dp(38), close.layoutParams.height)
    }

    private fun field(name: String) = PetOverlayService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    @Test fun actualFloatingChatKeepsFooterVisibleWithKeyboardAndCanSwitchToTasks() {
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildService(PetOverlayService::class.java)
        val service = controller.get()
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        field("wm").set(service, wm)
        field("params").set(service, WindowManager.LayoutParams(720, 1120, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; y = 40 })
        field("name").set(service, "小夏"); field("expanded").setBoolean(service, true)
        field("conversation").set(service, Conversation("chat", "陪伴", messages = listOf(
            Message("a", "assistant", "今天过得怎么样？我想听你说说。"), Message("b", "user", "今天有点累，陪我聊会吧。"),
            Message("c", "assistant", "好，我在。你想先说说今天的事，还是聊点轻松的？"))))
        val render = PetOverlayService::class.java.getDeclaredMethod("render").apply { isAccessible = true }
        render.invoke(service)
        val panel = field("view").get(service) as LinearLayout
        val params = field("params").get(service) as WindowManager.LayoutParams
        fun layout() { panel.measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY)); panel.layout(0, 0, params.width, params.height) }
        fun capture(name: String) {
            layout(); val image = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888); panel.draw(Canvas(image))
            val dir = File("build/reports/ui").apply { mkdirs() }; File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        try {
            capture("floating-chat")
            val tabs = panel.getChildAt(2) as LinearLayout
            assertEquals((38 * service.resources.displayMetrics.density).toInt(), tabs.getChildAt(0).height)
            for (i in 0 until tabs.childCount) assertTrue(tabs.getChildAt(i).bottom <= tabs.height)
            assertTrue(all(panel).filterIsInstance<TextView>().any { it.text.toString().contains("聊点轻松") })
            val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 48, 0, 48))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 760)).setVisible(WindowInsetsCompat.Type.ime(), true).build()
            ViewCompat.dispatchApplyWindowInsets(panel, insets)
            // Inspect the synthetic IME insets synchronously. Robolectric has no actual keyboard;
            // idling WindowManager would dispatch its real (empty) insets again.
            capture("floating-chat-keyboard")
            val footer = panel.getChildAt(panel.childCount - 2)
            assertTrue(footer.bottom <= panel.height)
            assertTrue(footer.height >= 44)
            assertTrue(params.height < 1120)
            val input = panel.findViewWithTag<EditText>("draft")
            assertTrue(input.width > 100 && input.height > 0)
            // Drag the expanded title surface, not just the avatar; don't rebuild the focused input.
            val titleHandle = all(panel).first { it.contentDescription == "拖动窗口标题" }
            val beforeX = params.x
            titleHandle.dispatchTouchEvent(MotionEvent.obtain(1, 1, MotionEvent.ACTION_DOWN, 5f, 5f, 0))
            titleHandle.dispatchTouchEvent(MotionEvent.obtain(1, 30, MotionEvent.ACTION_MOVE, 45f, 5f, 0))
            titleHandle.dispatchTouchEvent(MotionEvent.obtain(1, 40, MotionEvent.ACTION_UP, 45f, 5f, 0))
            assertTrue(params.x != beforeX)
            assertSame(panel, field("view").get(service))
            assertTrue(field("expanded").getBoolean(service))
            assertTrue(all(panel).any { it.contentDescription == "拖动悬浮窗口" })
            val resize = panel.findViewWithTag<View>("resize")
            val originalWidth = params.width
            input.setText("调整大小后保留输入")
            resize.dispatchTouchEvent(MotionEvent.obtain(2, 2, MotionEvent.ACTION_DOWN, 20f, 20f, 0))
            resize.dispatchTouchEvent(MotionEvent.obtain(2, 25, MotionEvent.ACTION_MOVE, -100f, -40f, 0))
            resize.dispatchTouchEvent(MotionEvent.obtain(2, 35, MotionEvent.ACTION_UP, -100f, -40f, 0))
            assertTrue(params.width < originalWidth)
            assertSame(panel, field("view").get(service))
            assertEquals("调整大小后保留输入", input.text.toString())
            capture("floating-resized")
            assertTrue(footer.bottom <= panel.height)
            assertTrue(TaskActions.preferences(service).getInt("pet_width", 0) > 0)
            all(panel).filterIsInstance<TextView>().first { it.text.toString() == "工作" }.performClick()
            assertTrue(field("workMode").getBoolean(service))
            val workPanel = field("view").get(service) as LinearLayout
            workPanel.measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY))
            workPanel.layout(0, 0, params.width, params.height)
            val workInput = workPanel.findViewWithTag<EditText>("draft")
            workInput.setText("收起后草稿还在")
            workInput.requestFocus()
            val collapse = workPanel.findViewWithTag<View>("collapse")
            val position = android.graphics.Rect()
            collapse.getDrawingRect(position)
            workPanel.offsetDescendantRectToMyCoords(collapse, position)
            val x = position.exactCenterX(); val y = position.exactCenterY()
            assertTrue(workPanel.dispatchTouchEvent(MotionEvent.obtain(3, 3, MotionEvent.ACTION_DOWN, x, y, 0)))
            // A simultaneous sync must not replace the window and swallow the UP/click.
            render.invoke(service)
            assertSame(workPanel, field("view").get(service))
            assertTrue(workPanel.dispatchTouchEvent(MotionEvent.obtain(3, 50, MotionEvent.ACTION_UP, x, y, 0)))
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(field("expanded").getBoolean(service))
            assertEquals("收起后草稿还在", field("draft").get(service))
            assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.height)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        } finally {
            field("destroyed").setBoolean(service, true)
            (field("view").get(service) as? View)?.let { wm.removeView(it) }
        }
    }
}
