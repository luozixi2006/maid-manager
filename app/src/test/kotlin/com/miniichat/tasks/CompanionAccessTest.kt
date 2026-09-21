package com.miniichat.tasks

import android.app.Application
import android.content.ComponentName
import android.provider.Settings
import com.miniichat.api.ChatMessage
import com.miniichat.tasks.agent.AccessibilityConnection
import com.miniichat.tasks.agent.PhoneAccessibility
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CompanionAccessTest {
    @After fun reset() {
        ScreenCompanion.enabled.value = false
        CompanionRuntime.running.value = false
        ScreenShare.running.value = false
    }
    @Test fun systemGrantIsNotConfusedWithDisconnectedBinding() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "other.app/other.Service")
        assertFalse(AccessibilityConnection.enabled(app))
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(app, PhoneAccessibility::class.java).flattenToString())
        assertTrue(AccessibilityConnection.enabled(app))
        assertTrue(AccessibilityConnection.description(app).contains("系统已允许"))
        assertNull(PhoneAccessibility.current)
    }
    @Test fun screenshotCannotBeReadWithoutUserStartedSession() = runBlocking {
        CompanionRuntime.running.value = true
        assertNull(ScreenCompanion.observe(RuntimeEnvironment.getApplication()))
        assertTrue(runCatching { ScreenShare.capture() }.isFailure)
    }
    @Test fun stoppedImageSessionCannotPublishOldObservationEvenAfterRestart() {
        CompanionRuntime.running.value = true; ScreenShare.running.value = true
        val observation = ScreenCompanion.Observation("screen", listOf("data:image/jpeg;base64,aGVsbG8="), ScreenShare.generation)
        assertTrue(observation.stillAllowed())
        ScreenShare.stop(RuntimeEnvironment.getApplication())
        assertFalse(observation.stillAllowed())
        ScreenShare.running.value = true
        assertFalse(observation.stillAllowed())
    }
    @Test fun stopRevokesRememberedTextAndWorkDefaultIsSeparateFromScopes() {
        val app = RuntimeEnvironment.getApplication()
        TaskActions.preferences(app).edit().putBoolean(ScreenCompanion.REMEMBER, true).commit()
        ScreenCompanion.enabled.value = true
        WorkDefaults.remember(app, true)
        assertTrue(WorkDefaults.routine(app))
        assertTrue(TaskActions.preferences(app).getStringSet("agent_apps", emptySet()).orEmpty().isEmpty())
        ScreenCompanion.stop(app)
        assertFalse(ScreenCompanion.enabled.value)
        assertFalse(TaskActions.preferences(app).getBoolean(ScreenCompanion.REMEMBER, true))
        assertTrue(WorkDefaults.routine(app))
    }
}
