package com.miniichat.tasks.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.FileProvider
import com.miniichat.tasks.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NativePhoneTools {
    fun apps(context: Context): Map<String, String> {
        @Suppress("DEPRECATION")
        return context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .associate { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
    }
    fun intent(context: Context, task: PhoneTask, step: PhoneStep): Intent = when (step.tool) {
        "open_app" -> {
            val pkg = step.arguments["package"].orEmpty()
            require(AgentPolicy.validPackage(pkg)) { "不能由模型打开系统权限或安装界面" }
            require(pkg in apps(context)) { "应用不存在或不可启动" }
            context.packageManager.getLaunchIntentForPackage(pkg) ?: error("无法打开应用")
        }
        "open_url" -> {
            val uri = Uri.parse(step.arguments["url"].orEmpty())
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "仅打开有效 HTTPS 网页，不执行任意Intent或脚本" }
            Intent(Intent.ACTION_VIEW, uri)
        }
        "share_file" -> {
            val source = ToolPolicy.resolve(AgentFiles(context, task).root(), step.source)
            check(source.isFile && source.length() <= 100 * 1024 * 1024) { "只能分享100MB以内的普通文件" }
            val dir = File(context.cacheDir, "agent-share").apply { mkdirs() }
            val target = File(dir, "${task.id}-${task.cursor}-${source.name}")
            source.copyTo(target, overwrite = true)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
            val send = Intent(Intent.ACTION_SEND).setType(context.contentResolver.getType(uri) ?: "application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Intent.createChooser(send, "确认发送给谁").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        "calendar_event" -> {
            val start = step.arguments["start"]?.toLongOrNull() ?: error("日程开始时间无效")
            val end = step.arguments["end"]?.toLongOrNull() ?: error("日程结束时间无效")
            require(end > start && end - start <= 366L * 86400000) { "日程时间范围无效" }
            Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, step.arguments["title"].orEmpty())
                .putExtra(CalendarContract.Events.DESCRIPTION, step.arguments["description"].orEmpty())
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        }
        else -> error("不支持的系统操作")
    }
    suspend fun execute(context: Context, task: PhoneTask, step: PhoneStep): String = when (step.tool) {
        "list_apps" -> apps(context).entries.joinToString("\n") { "${it.key}: ${it.value}" }
        "read_notifications" -> {
            val pkg = step.arguments["package"].orEmpty()
            check(pkg in task.allowedPackages && pkg in TaskActions.preferences(context).getStringSet("agent_apps", emptySet()).orEmpty()) { "应用没有获得任务授权" }
            NotificationAccess.read(pkg)
        }
        "open_app", "open_url", "share_file", "calendar_event" -> throw HandOff("请点“打开系统操作”在前台执行；分享收件人、日程保存由你确认")
        "read_screen", "tap", "type_text", "scroll", "back", "home" -> withContext(Dispatchers.Main) {
            (PhoneAccessibility.current ?: throw NeedsPermission("accessibility", "需要开启无障碍服务；仅控制任务授权的应用" )).execute(task, step)
        }
        else -> error("原生工具未实现")
    }
}

object NotificationAccess {
    @Volatile var listener: android.service.notification.NotificationListenerService? = null
    fun read(packageName: String): String {
        val service = listener ?: throw NeedsPermission("notifications", "需要开启系统通知访问")
        return service.activeNotifications.orEmpty().filter { it.packageName == packageName }.take(20).joinToString("\n") {
            val extras = it.notification.extras
            val text = "${extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()} ${extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()}"
            if (Regex("验证码|校验码|动态口令|one.time|verification|\\bOTP\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) "[验证通知已屏蔽]" else text.replace(Regex("\\b\\d{4,8}\\b"), "[数字已隐藏]").take(700)
        }.ifBlank { "当前没有该应用的通知" }
    }
}
