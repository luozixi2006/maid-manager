package com.miniichat.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.work.testing.WorkManagerTestInitHelper
import com.miniichat.tasks.*
import com.miniichat.ui.theme.MaidManagerTheme
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Dedicated device configuration, avoiding changing window density inside a shared Compose rule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w320dp-h640dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactWorkUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun setup() { WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication()) }
    @Test fun compactWorkShowsDecisionButCollapsesRawResults() {
        val app = RuntimeEnvironment.getApplication()
        val task = PhoneTask(goal = "在视频应用中搜索教程，并整理结果", providerId = "p", providerEndpoint = "p", model = "已选模型",
            state = TaskState.APPROVAL, detail = "即将打开你授权的视频应用", cursor = 1,
            steps = listOf(PhoneStep("list_apps", done = true, result = "技术记录默认不展开"), PhoneStep("open_app")))
        TaskStore.of(app).put(task); TaskNavigation.selectedTask.value = task.id
        compose.setContent { MaidManagerTheme("dark", false) { WorkScreen({}, {}, "已选模型") } }
        compose.onNodeWithText("需要你决定").assertExists()
        compose.onNodeWithText("已完成 1 步").assertExists()
        compose.onNodeWithText("技术记录默认不展开").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        compose.onNodeWithContentDescription("发送").assertIsDisplayed()
        compose.onNodeWithText("工作记录").assertIsNotDisplayed()
        compose.onNodeWithText("允许这一步").assertIsDisplayed()
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            val directory = File("build/reports/ui").apply { mkdirs() }
            File(directory, "work-compact-decision.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
    @After fun close() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        TaskStore.of(RuntimeEnvironment.getApplication()).close()
        TaskStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
}
