package com.miniichat.tasks.agent

import com.miniichat.tasks.PhoneStep

/** Task-local permission is never a wildcard for irreversible or external actions. */
object RoutineApproval {
    const val explanation = "本任务内自动允许文件创建/整理、打开已选应用、查看页面、搜索与浏览。必要页面文字会交给本任务模型。删除、付款、发送、发布及不明按钮仍逐次确认；系统权限仍由你开启。"
    private val navigation = Regex("^(搜索|搜一搜|搜一下|查找|查询|取消搜索|返回|下一页|上一页|首页|视频|用户|综合|动态|番剧|直播|筛选|排序|展开|收起|search|back|next|previous|home|videos|users)$", RegexOption.IGNORE_CASE)
    private val sensitive = Regex("支付|付款|转账|购买|下单|删除|清空|发送|发布|提交订单|确认订单|订阅|登录|验证码|密码|授权|pay|purchase|delete|send|post|subscribe|password|login|otp", RegexOption.IGNORE_CASE)
    fun safeNavigation(label: String) = !sensitive.containsMatchIn(label) && navigation.matches(label.trim())
    fun searchField(label: String) = !sensitive.containsMatchIn(label) && Regex("搜索|搜一搜|查找|查询|search", RegexOption.IGNORE_CASE).containsMatchIn(label)
    fun ordinary(step: PhoneStep): Boolean = step.tool in setOf("mkdir", "copy", "move", "rename", "write_text", "open_app", "read_screen", "scroll", "back", "home")
}
