package com.miniichat.proactive

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import com.miniichat.MainActivity
import com.miniichat.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class ProactiveDestination(
    val sourceType: String,
    val conversationId: String = ""
)

object ProactiveNavigation {
    private val _destination = MutableStateFlow<ProactiveDestination?>(null)
    val destination = _destination.asStateFlow()
    private val _foregroundNotice = MutableStateFlow<String?>(null)
    val foregroundNotice = _foregroundNotice.asStateFlow()

    fun open(destination: ProactiveDestination) { _destination.value = destination }
    fun clearDestination() { _destination.value = null }
    fun showForegroundNotice(text: String) { _foregroundNotice.value = text }
    fun clearForegroundNotice() { _foregroundNotice.value = null }
}

object AppVisibility {
    @Volatile var isForeground: Boolean = false
}

object ProactiveNotifications {
    const val EXTRA_SOURCE = "proactive_source"
    const val EXTRA_CONVERSATION = "proactive_conversation_id"
    private const val CHANNEL_ID = "character_messages"
    private const val PUBLIC_TITLE = "女仆管理器"
    private const val PUBLIC_TEXT = "你收到一条角色消息"

    fun blockedReason(context: Context): String? {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return "通知权限未开启"
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return "系统通知已关闭"
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID)?.importance ==
            NotificationManager.IMPORTANCE_NONE) return "角色消息通知已关闭"
        return null
    }

    fun settingsIntent(context: Context): Intent {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelSettings = Build.VERSION.SDK_INT >= 26 &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID) != null
        return Intent(if (channelSettings) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
            else Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            if (channelSettings) putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
        }
    }

    /**
     * Creates the character message channel when it does not exist yet. An existing channel is
     * left untouched so user choices (importance, sound, vibration) are never overridden.
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(CHANNEL_ID, "角色消息", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "人物主动发来的消息"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 180, 100, 180)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audioAttributes)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun publish(
        context: Context,
        characterName: String,
        message: String,
        avatarPath: String?,
        destination: ProactiveDestination,
        deliveryId: String? = null
    ): Boolean {
        com.miniichat.tasks.PetMessages.show("$characterName：$message", destination.conversationId)
        if (AppVisibility.isForeground) {
            ProactiveNavigation.showForegroundNotice("$characterName：$message")
        }
        if (blockedReason(context) != null) return false

        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SOURCE, destination.sourceType)
            putExtra(EXTRA_CONVERSATION, destination.conversationId)
        }
        val requestCode = listOf(
            destination.sourceType,
            destination.conversationId
        ).joinToString(":").hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val avatar = loadAvatar(context, avatarPath)
        val sender = Person.Builder().setName(characterName).apply {
            avatar?.let { setIcon(IconCompat.createWithBitmap(it)) }
        }.build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_push_message)
            .setContentTitle(characterName)
            .setContentText(message)
            .setStyle(NotificationCompat.MessagingStyle(Person.Builder().setName("我").build())
                .setGroupConversation(false)
                .addMessage(message, System.currentTimeMillis(), sender))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setLocalOnly(false)
            .setOnlyAlertOnce(deliveryId != null)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setLargeIcon(avatar)
            .setPublicVersion(NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_push_message)
                .setContentTitle(PUBLIC_TITLE)
                .setContentText(PUBLIC_TEXT)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .build())
            .build()
        return try {
            if (deliveryId != null) NotificationManagerCompat.from(context).notify(
                com.miniichat.proactive.remote.PushTrace.notificationTag(deliveryId), 0, notification)
            else NotificationManagerCompat.from(context).notify(requestCode, notification)
            true
        } catch (_: SecurityException) {
            // Permission can be revoked between checking it and posting; the conversation is already saved.
            Log.w("ProactiveNotifications", "Notification permission changed before delivery")
            false
        }
    }

    private fun loadAvatar(context: Context, avatarPath: String?): Bitmap? {
        avatarPath?.takeIf { it.isNotBlank() && File(it).isFile }?.let { path ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 256) options.inSampleSize *= 2
            BitmapFactory.decodeFile(path, options)?.let { return it }
        }
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher) ?: return null
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 128
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 128
        return createBitmap(width, height).also { bitmap ->
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
        }
    }
}
