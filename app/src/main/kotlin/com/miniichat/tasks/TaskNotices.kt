package com.miniichat.tasks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.miniichat.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow

object TaskNavigation {
    val open = MutableStateFlow(false)
    val chatDraft = MutableStateFlow("")
}

object TaskNotices {
    fun openIntent(context: Context) = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra("phone_tasks", true)
    fun notification(context: Context, task: PhoneTask, ongoing: Boolean = false): Notification {
        val channel = "phone_tasks"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "后台任务与确认", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, task.id.hashCode(), openIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_menu_agenda).setContentTitle("${task.characterName} · ${task.state.label}")
            .setContentText(task.detail.take(140)).setStyle(NotificationCompat.BigTextStyle().bigText(task.detail.take(1000)))
            .setContentIntent(open).setAutoCancel(!ongoing).setOngoing(ongoing)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(ongoing)
        if (!ongoing) {
            when (task.state) {
                TaskState.APPROVAL -> { addAction(context, builder, task, "允许一次", "approve"); addAction(context, builder, task, "取消任务", "cancel") }
                TaskState.QUESTION -> task.steps.getOrNull(task.cursor)?.options?.take(3)?.forEach {
                    addAction(context, builder, task, it, "answer", it)
                }
                TaskState.FAILED, TaskState.PAUSED -> addAction(context, builder, task, "继续", "resume")
                else -> Unit
            }
        } else addAction(context, builder, task, "暂停", "pause")
        return builder.build()
    }
    private fun addAction(context: Context, builder: NotificationCompat.Builder, task: PhoneTask, label: String, action: String, answer: String = "") {
        val intent = Intent(context, TaskActionReceiver::class.java)
            .setData(Uri.parse("maid-task://${task.id}/${task.approvalToken}/${Uri.encode(action + answer)}"))
            .putExtra("id", task.id).putExtra("action", action).putExtra("answer", answer).putExtra("token", task.approvalToken)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(0, label, pending)
    }
    fun publish(context: Context, task: PhoneTask) {
        if (task.state in setOf(TaskState.QUEUED, TaskState.PLANNING, TaskState.RUNNING)) return
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try { NotificationManagerCompat.from(context).notify(task.id.hashCode(), notification(context, task)) }
        catch (_: SecurityException) { /* Permission revoked between check and notify. State remains in SQLite. */ }
    }
}

class TaskActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        TaskActions.respond(context, intent.getStringExtra("id").orEmpty(), intent.getStringExtra("action").orEmpty(),
            intent.getStringExtra("answer").orEmpty(), intent.getStringExtra("token")).invokeOnCompletion { pending.finish() }
    }
}
