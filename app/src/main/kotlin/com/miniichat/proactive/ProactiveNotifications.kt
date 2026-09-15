package com.miniichat.proactive

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
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

    fun publish(
        context: Context,
        characterName: String,
        message: String,
        avatarPath: String?,
        destination: ProactiveDestination
    ) {
        com.miniichat.tasks.PetMessages.show("$characterName：$message", destination.conversationId)
        if (AppVisibility.isForeground) {
            ProactiveNavigation.showForegroundNotice("$characterName：$message")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "角色消息",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "人物主动发来的消息" }
            )
        }
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
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(characterName)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setLargeIcon(loadAvatar(context, avatarPath))
            .build()
        NotificationManagerCompat.from(context).notify(requestCode, notification)
    }

    private fun loadAvatar(context: Context, avatarPath: String?): Bitmap? {
        avatarPath?.takeIf { it.isNotBlank() && File(it).isFile }?.let { path ->
            BitmapFactory.decodeFile(path)?.let { return it }
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
