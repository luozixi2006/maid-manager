package com.miniichat.tasks

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w393dp-h852dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionUiTest {
    @Test fun customAvatarSurvivesPersonaAndTaskChanges() {
        val context = RuntimeEnvironment.getApplication()
        CompanionAppearance.setAvatar(context, "local-avatar.jpg")
        Assert.assertEquals("local-avatar.jpg", CompanionAppearance.avatar(context))
        Assert.assertEquals("local-avatar.jpg", CompanionAppearance.resolve(CompanionAppearance.avatar(context), "old-task.jpg", "new-persona.jpg"))
        CompanionAppearance.setAvatar(context, "")
        Assert.assertEquals("persona.jpg", CompanionAppearance.resolve("", null, "persona.jpg"))
    }
    @Test fun overlayControlsAndNativeRendering() {
        for (dark in listOf(false, true)) {
            val ui = CompanionViews(RuntimeEnvironment.getApplication(), dark)
            var closed = false; var collapsed = false
            val panel = ui.column().apply { setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)); background = ui.shape(ui.surface, 24) }
            val header = ui.header("小夏", "等你确认", "", { collapsed = true }, { closed = true }, {})
            panel.addView(header)
            header.getChildAt(2).performClick(); header.getChildAt(3).performClick()
            Assert.assertTrue(closed && collapsed)
            panel.addView(ui.label("两个文件同名，要保留哪一份？", 15).apply { setPadding(0, ui.dp(24), 0, ui.dp(12)) })
            panel.addView(ui.action("保留两份", true) {})
            panel.addView(ui.action("先跳过") {})
            panel.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(328), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ui.dp(700), View.MeasureSpec.AT_MOST))
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            Assert.assertTrue(panel.height < ui.dp(400))
            val image = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
            panel.draw(Canvas(image))
            val folder = File("build/reports/ui").apply { mkdirs() }
            File(folder, "overlay-${if (dark) "dark" else "light"}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
