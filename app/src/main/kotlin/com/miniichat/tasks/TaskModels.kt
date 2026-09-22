package com.miniichat.tasks

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

internal val taskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
enum class TaskState(val label: String) {
    QUEUED("等待执行"), PLANNING("正在思考"), RUNNING("正在执行"),
    PERMISSION("需要权限"), APPROVAL("需要确认"), QUESTION("等你回答"),
    PAUSED("已暂停"), FAILED("需要处理"), DONE("任务完成"), CANCELLED("已取消")
}

@Serializable
data class FileFact(val path: String, val size: Long, val modified: Long, val excerpt: String = "")

@Serializable
data class PhoneStep(
    val tool: String, val source: String = "", val destination: String = "",
    val reason: String = "", val question: String = "", val options: List<String> = emptyList(),
    val approved: Boolean = false, val done: Boolean = false,
    // Write-ahead receipt: persist the exact target and fingerprint BEFORE moving anything.
    val prepared: Boolean = false, val resolvedTarget: String = "", val fingerprint: String = "",
    val result: String = "", val arguments: Map<String, String> = emptyMap()
)

@Serializable
data class PhonePlan(val steps: List<PhoneStep> = emptyList(), val note: String = "")

@Serializable
data class PhoneTask(
    val id: String = UUID.randomUUID().toString(), val goal: String,
    val scope: String = "", val providerId: String, val providerEndpoint: String, val model: String,
    val characterName: String = "女仆", val avatarPath: String = "", val personaPrompt: String = "",
    val personaId: String = "",
    val state: TaskState = TaskState.QUEUED, val detail: String = "等待扫描已授权目录",
    val inventory: List<FileFact> = emptyList(), val scanned: Boolean = false,
    val steps: List<PhoneStep> = emptyList(), val cursor: Int = 0, val planNote: String = "",
    val answers: List<String> = emptyList(), val history: List<String> = emptyList(),
    val approvedSuggestion: Boolean = true, val sourceRule: String = "",
    val approvalToken: String = UUID.randomUUID().toString(),
    val noticeOnly: Boolean = false,
    val engineVersion: Int = 1, val rootDirectory: String = "Download",
    val allowedPackages: List<String> = emptyList(), val neededPermission: String = "",
    val autoAllowRoutine: Boolean = false,
    val fileConsent: Boolean = true,
    val observations: List<String> = emptyList(), val rounds: Int = 0,
    val goalSummary: String = "", val consecutiveErrors: Int = 0,
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt
)

val PhoneTask.statusLabel: String get() = if (noticeOnly) "主动消息" else state.label

object ToolPolicy {
    val fileTools = setOf("mkdir", "move", "rename")
    val tools = fileTools + "ask"
    fun relative(path: String, allowEmpty: Boolean = false): String {
        require((allowEmpty && path.isEmpty()) || (path.isNotBlank() && path.length <= 230)) { "路径为空或过长" }
        require(!path.startsWith('/') && !path.contains('\\') && !path.contains(':') &&
            path.none { it.code < 32 } && path.split('/').none { it == ".." || it == "." || it == "Android" }) {
            "操作超出授权目录，已阻止"
        }
        require(path.isEmpty() || path.split('/').none { it.isBlank() }) { "路径无效" }
        return path
    }
    fun resolve(root: File, path: String): File {
        relative(path)
        val base = root.canonicalFile
        val candidate = File(base, path).canonicalFile
        require(candidate.toPath().startsWith(base.toPath()) && candidate != base) { "操作超出授权目录，已阻止" }
        return candidate
    }
    fun validate(plan: PhonePlan, inventory: List<FileFact>): PhonePlan {
        require(plan.steps.size <= 200) { "计划过长，请缩小本次目录范围" }
        val sources = mutableSetOf<String>()
        val safe = plan.steps.map { s ->
            require(s.tool in tools) { "模型提出了本版本不支持的工具，未执行" }
            if (s.tool == "ask") {
                require(s.question.isNotBlank() && s.question.length <= 500 && s.options.size in 2..4 &&
                    s.options.all { it.isNotBlank() && it.length <= 100 }) { "模型返回的确认问题格式错误" }
            } else {
                relative(s.destination)
                if (s.tool != "mkdir") {
                    relative(s.source)
                    require(s.source in inventory.map { it.path } && sources.add(s.source)) { "计划包含未扫描或重复处理的文件" }
                    require(s.source.endsWith(".pdf", true) && s.destination.endsWith(".pdf", true)) { "当前工具只整理 PDF，不改变文件格式" }
                }
            }
            // The model cannot grant approvals, forge completed operations, or supply recovery receipts.
            PhoneStep(s.tool, s.source, s.destination, s.reason.take(300), s.question, s.options)
        }
        return PhonePlan(safe, plan.note.take(1000))
    }
}
