package com.miniichat.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.work.testing.WorkManagerTestInitHelper
import com.miniichat.data.*
import com.miniichat.tasks.*
import com.miniichat.ui.theme.MaidManagerTheme
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
class UiVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun workManager() { WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication()) }
    private fun settings(dark: Boolean) {
        compose.setContent { MaidManagerTheme(if (dark) "dark" else "light", false) {
            SettingsScreen(AppSettings(), emptyList(), emptyList(), 0, 0,
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        } }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        lateinit var image: Bitmap
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
        }
        Assert.assertTrue(image.width >= 300 && image.height >= 600)
        val folder = File("build/reports/ui").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun settingsLight() { settings(false); capture("settings-light") }
    @Test fun settingsDark() { settings(true); capture("settings-dark") }
    @Test fun providerEditorLight() {
        compose.setContent { MaidManagerTheme("light", false) { ProviderEditorScreen(null, {}, {}) } }
        capture("service-editor")
    }
    @Test fun companionDark() {
        TaskNavigation.companion.value = true
        compose.setContent { MaidManagerTheme("dark", false) { TasksScreen {} } }
        compose.onNodeWithText("更换头像").assertExists()
        capture("companion-dark")
    }
    @Test fun tasksAndCompanion() {
        compose.setContent { MaidManagerTheme("light", false) { TasksScreen {} } }
        capture("tasks-light")
        compose.onNodeWithText("陪伴", useUnmergedTree = true).performClick()
        compose.onNodeWithText("更换头像").assertExists()
        compose.onNodeWithText("显示悬浮头像").assertExists()
        capture("companion-settings")
    }
    @After fun closeDatabase() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        TaskStore.of(RuntimeEnvironment.getApplication()).close()
        TaskStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
}
