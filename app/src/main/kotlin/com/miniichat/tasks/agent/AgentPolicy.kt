package com.miniichat.tasks.agent

import com.miniichat.tasks.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class Risk { READ, CHANGE, CONFIRM }
data class ToolSpec(val name: String, val title: String, val risk: Risk, val permission: String = "", val usage: String)

object AgentPolicy {
    val specs = listOf(
        ToolSpec("list_files", "查看文件", Risk.READ, "files", "source=子目录(空为根); query=可选名称关键词; offset=分页起点。返回每页100条。"),
        ToolSpec("read_file", "读取文件", Risk.READ, "files", "source=文件相对路径; offset=文本字符起点。支持PDF/TXT/MD/CSV/JSON/XML/源码等文本。"),
        ToolSpec("mkdir", "创建文件夹", Risk.CHANGE, "files", "destination=相对路径"),
        ToolSpec("copy", "复制文件", Risk.CHANGE, "files", "source,destination=相对文件路径; 不覆盖"),
        ToolSpec("move", "移动文件", Risk.CHANGE, "files", "source,destination=相对文件路径; 不覆盖。先mkdir目标目录"),
        ToolSpec("rename", "重命名", Risk.CHANGE, "files", "同move，保持原文件扩展名"),
        ToolSpec("write_text", "创建文本文件", Risk.CHANGE, "files", "destination=新TXT/MD/CSV/JSON等文本文件; text=内容。不覆盖原文件，改文件时另存新文件。"),
        ToolSpec("trash", "移到可恢复区", Risk.CONFIRM, "files", "source=单个文件。没有永久删除工具"),
        ToolSpec("restore", "恢复文件", Risk.CONFIRM, "files", "source=回收记录返回的恢复ID; destination=恢复相对路径。不覆盖"),
        ToolSpec("list_apps", "查看可打开的应用", Risk.READ, usage = "无参数，返回真实应用包名，禁止猜包名"),
        ToolSpec("open_app", "打开应用", Risk.CHANGE, usage = "package=真实可启动包名；使用原生Intent"),
        ToolSpec("open_url", "打开网页", Risk.CONFIRM, usage = "url=https网址；打开浏览器，不代表读取了网页"),
        ToolSpec("web_search", "联网搜索", Risk.READ, usage = "query=搜索词；沿用用户搜索配置"),
        ToolSpec("share_file", "分享文件", Risk.CONFIRM, "files", "source=相对文件路径；系统分享面板交给用户确认收件人，不自动发送"),
        ToolSpec("calendar_event", "添加日程", Risk.CONFIRM, usage = "title,description,start,end(毫秒时间戳)；打开系统日历编辑器，最终保存由用户确认"),
        ToolSpec("read_notifications", "读取选定应用通知", Risk.CONFIRM, "notifications", "package=任务授权应用；仅当前通知，敏感验证通知屏蔽"),
        ToolSpec("read_screen", "观察当前页面", Risk.CONFIRM, "accessibility", "package=任务授权应用；返回带屏幕快照ID和节点ID的可见结构，密码界面屏蔽"),
        ToolSpec("tap", "点击页面元素", Risk.CONFIRM, "accessibility", "package,node,snapshot=最近观察的精确标识；页面变化则拒绝点击"),
        ToolSpec("type_text", "输入文本", Risk.CONFIRM, "accessibility", "package,node,snapshot,text；不能操作密码字段"),
        ToolSpec("scroll", "滚动页面", Risk.CHANGE, "accessibility", "package,node,snapshot,direction=forward/backward"),
        ToolSpec("back", "返回上页", Risk.CHANGE, "accessibility", "package=任务授权的当前应用"),
        ToolSpec("home", "返回桌面", Risk.CHANGE, "accessibility", "package=任务授权的当前应用"),
        ToolSpec("ask", "询问你", Risk.READ, usage = "question,options数组(2到4项)；等待用户回答"),
        ToolSpec("finish", "报告结果", Risk.READ, usage = "text=根据真实工具结果报告完成、未完成和限制；禁止虚构成功")
    ).associateBy { it.name }
    val fileKinds = specs.filterValues { it.permission == "files" }.keys
    val replaySafe = setOf("mkdir", "copy", "move", "rename", "write_text", "trash", "restore")
    val repeatableReads = setOf("list_files", "read_file", "list_apps", "web_search", "read_screen", "read_notifications")
    val textExtensions = setOf("txt", "md", "csv", "json", "xml", "html", "css", "js", "ts", "kt", "java", "py", "yaml", "yml", "log", "ini")
    fun spec(name: String) = specs[name] ?: error("工具未开放：$name")
    fun validate(step: PhoneStep): PhoneStep {
        spec(step.tool)
        require(step.arguments.size <= 12 && step.arguments.values.all { it.length <= 24000 }) { "工具参数过长" }
        if (step.tool in fileKinds) {
            if (step.source.isNotBlank()) ToolPolicy.relative(step.source)
            if (step.destination.isNotBlank()) ToolPolicy.relative(step.destination)
            require((step.source + "/" + step.destination).split('/').none { it.startsWith(".maid-") }) { "内部恢复区和暂存区不能直接访问" }
        }
        if (step.tool == "ask") require(step.question.isNotBlank() && step.options.size in 2..4) { "问题格式不正确" }
        if (step.tool == "finish") require(step.arguments["text"].orEmpty().isNotBlank()) { "完成报告为空" }
        return PhoneStep(tool = step.tool, source = step.source, destination = step.destination,
            reason = step.reason.take(500), question = step.question.take(1000), options = step.options.map { it.take(150) }, arguments = step.arguments)
    }
    fun validPackage(name: String): Boolean = name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) &&
        name != "com.maidmanager.debug" && name != "com.android.settings" && name != "com.android.systemui" &&
        !name.contains("permissioncontroller") && !name.contains("packageinstaller")
    fun approvalKey(task: PhoneTask, step: PhoneStep) = "agent:${task.rootDirectory}/${task.scope}:${step.tool}:${step.arguments["package"].orEmpty()}"
    fun canRemember(step: PhoneStep): Boolean = step.tool in setOf("mkdir", "copy", "move", "rename", "write_text", "open_app", "scroll", "back", "home")
    fun requireFreshObservation(task: PhoneTask, step: PhoneStep) {
        if (step.tool !in setOf("tap", "type_text", "scroll")) return
        val last = task.steps.take(task.cursor).lastOrNull { it.done && spec(it.tool).permission == "accessibility" }
        check(last?.tool == "read_screen") { "每次页面操作前必须重新观察，不复用旧页面" }
        val receipt = taskJson.parseToJsonElement(last.result).jsonObject
        check(receipt["snapshot"]?.jsonPrimitive?.content == step.arguments["snapshot"] &&
            receipt["package"]?.jsonPrimitive?.content == step.arguments["package"]) { "页面标识不是最近一次真实观察，已拒绝" }
    }
    fun targetDescription(task: PhoneTask, step: PhoneStep): String {
        if (step.tool !in setOf("tap", "type_text", "scroll")) return ""
        val read = task.steps.take(task.cursor).lastOrNull { it.done && it.tool == "read_screen" } ?: return "尚无页面观察"
        return runCatching {
            val rows = taskJson.parseToJsonElement(read.result).jsonObject["nodes"]?.jsonPrimitive?.content.orEmpty()
            "目标控件：" + (rows.lineSequence().firstOrNull { it.startsWith("${step.arguments["node"]}: ") } ?: "未找到可见控件，需要重新观察")
        }.getOrDefault("观察记录不完整，需要重新观察")
    }
}

@Serializable data class AgentTurn(val summary: String = "", val steps: List<PhoneStep> = emptyList())
class NeedsPermission(val kind: String, message: String) : IllegalStateException(message)
class HandOff(message: String) : IllegalStateException(message)
