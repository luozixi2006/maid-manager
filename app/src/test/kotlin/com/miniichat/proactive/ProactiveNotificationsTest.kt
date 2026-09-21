package com.miniichat.proactive

import android.app.Application
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.miniichat.tasks.PetMessages
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ProactiveNotificationsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val destination = ProactiveDestination("persona", "conversation-1")
    private val id = "persona:conversation-1".hashCode()

    @After fun reset() {
        AppVisibility.isForeground = false
        ProactiveNavigation.clearForegroundNotice()
        PetMessages.notice.value = ""
        PetMessages.conversationId = ""
        manager.cancelAll()
    }

    @Test fun foregroundMessageAlsoAppearsInNotificationShade() {
        AppVisibility.isForeground = true
        ProactiveNotifications.publish(context, "小夏", "吃饭了吗？", null, destination)
        val notification = shadowOf(manager).getNotification(id)
        assertNotNull(notification)
        assertEquals("小夏：吃饭了吗？", ProactiveNavigation.foregroundNotice.value)
        assertEquals("conversation-1", PetMessages.conversationId)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals("conversation-1", intent.getStringExtra(ProactiveNotifications.EXTRA_CONVERSATION))
        assertEquals("persona", intent.getStringExtra(ProactiveNotifications.EXTRA_SOURCE))
    }

    @Test fun notificationUsesActualSenderNameAndRefreshesTheirAvatar() {
        AppVisibility.isForeground = false
        val avatar = File(context.cacheDir, "notification-persona.png")
        fun publish(name: String, color: Int) {
            val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            avatar.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            ProactiveNotifications.publish(context, name, "你好", avatar.path, destination)
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shadowOf(manager).getNotification(id))!!
            val person = style.messages.single().person!!
            assertEquals(name, person.name.toString())
            val receivedAvatar = person.icon!!.loadDrawable(context) as BitmapDrawable
            assertEquals(color, receivedAvatar.bitmap.getPixel(0, 0))
            assertEquals("你好", style.messages.single().text.toString())
        }
        publish("小夏", Color.RED)
        publish("夏夏", Color.BLUE)
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test fun blockedChannelIsReportedWithoutDiscardingOverlayMessageOrChangingPreference() {
        ProactiveNotifications.publish(context, "小夏", "第一条", null, destination)
        val channel = manager.getNotificationChannel("character_messages")
        channel.importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(channel)
        manager.cancelAll()
        assertEquals("角色消息通知已关闭", ProactiveNotifications.blockedReason(context))
        ProactiveNotifications.publish(context, "小夏", "仍然保存和展示", null, destination)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals("小夏：仍然保存和展示", PetMessages.notice.value)
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel("character_messages").importance)
        val settings = ProactiveNotifications.settingsIntent(context)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, settings.action)
        assertEquals("character_messages", settings.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test fun disabledAppNotificationsCanOpenSettingsRatherThanRequestGrantedPermissionAgain() {
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals("系统通知已关闭", ProactiveNotifications.blockedReason(context))
        val settings = ProactiveNotifications.settingsIntent(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.action)
        assertEquals(context.packageName, settings.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test fun missingAvatarDoesNotPreventDelivery() {
        ProactiveNotifications.publish(context, "小夏", "你好", "/missing/avatar.png", destination)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shadowOf(manager).getNotification(id))!!
        assertEquals("小夏", style.messages.single().person!!.name.toString())
        assertNotNull(style.messages.single().person!!.icon)
    }
}
