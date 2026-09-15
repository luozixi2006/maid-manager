package com.miniichat.tasks.agent

import android.content.Context
import android.os.Environment
import com.miniichat.tasks.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.serialization.encodeToString

class AgentFiles(private val context: Context, private val task: PhoneTask) {
    fun root(): File {
        if (!DownloadsTools(context, "").permitted()) throw NeedsPermission("files", "需要文件访问权限；授权后从原步骤继续")
        val storage = Environment.getExternalStorageDirectory().canonicalFile
        val selected = ToolPolicy.resolve(storage, task.rootDirectory)
        val base = if (task.scope.isBlank()) selected else ToolPolicy.resolve(selected, task.scope)
        check(base.isDirectory) { "授权目录不存在，请在新任务中选择存在的目录" }
        return base
    }
    fun read(step: PhoneStep): String {
        val root = root()
        val source = if (step.source.isBlank()) root else ToolPolicy.resolve(root, step.source)
        val offset = step.arguments["offset"]?.toIntOrNull()?.coerceIn(0, 1000000) ?: 0
        if (step.tool in setOf("list_files", "find_files")) {
            check(source.isDirectory) { "不是文件夹" }
            val candidates = if (step.tool == "find_files") source.walkTopDown().maxDepth(8).onEnter {
                !it.name.startsWith(".maid-") && it.name != "Android" && !Files.isSymbolicLink(it.toPath())
            }.filter { it != source }.take(10000).toList() else source.listFiles()?.toList() ?: error("目录不可读")
            val list = candidates.filter { !it.name.startsWith(".maid-") && it.name != "Android" && !Files.isSymbolicLink(it.toPath()) &&
                it.name.contains(step.arguments["query"].orEmpty(), ignoreCase = true) }.sortedBy { it.name }
            return "本次范围内匹配${list.size}项，本页起点$offset，下一页offset=${offset + 100}；递归最多10000项，较大目录请分目录查找\n" + list.drop(offset).take(100).joinToString("\n") {
                taskJson.encodeToString(mapOf("path" to it.relativeTo(root).invariantSeparatorsPath, "kind" to if (it.isDirectory) "directory" else "file", "bytes" to it.length().toString()))
            }
        }
        check(source.isFile && source.length() <= 20 * 1024 * 1024) { "只能读取20MB以内的文档；其他文件仍可移动和复制" }
        val text = when (source.extension.lowercase()) {
            "pdf" -> { PDFBoxResourceLoader.init(context)
                PDDocument.load(source).use { PDFTextStripper().apply { startPage = 1; endPage = 10 }.getText(it) }
            }
            in AgentPolicy.textExtensions -> source.inputStream().bufferedReader().use { it.readText() }
            in OfficeText.extensions -> OfficeText.read(source)
            else -> error("此格式不能提取正文，但可按名称和类型移动、复制、改名；无法确定内容时询问用户")
        }
        return "文件 ${step.source}，提取字符${text.length}，offset=$offset${if (source.extension.equals("pdf", true)) "（仅前10页，无OCR）" else ""}\n" + text.drop(offset).take(12000)
    }
    fun prepare(step: PhoneStep): PhoneStep = ManagedFiles(root()).prepare(step, "${task.id}-${task.cursor}")
    fun execute(step: PhoneStep): String = ManagedFiles(root()).execute(step)
}

/** A write-ahead, non-overwriting tool set. Recovery stays on the same filesystem. */
class ManagedFiles(private val root: File) {
    private fun resolve(path: String) = ToolPolicy.resolve(root, path)
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(65536)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun textSha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun prepare(step: PhoneStep, receiptId: String): PhoneStep {
        require(step.tool in AgentPolicy.replaySafe)
        require(receiptId.matches(Regex("[A-Za-z0-9-]+")))
        val recovery = File(root, ".maid-recovery")
        check(!Files.isSymbolicLink(recovery.toPath())) { "恢复区路径异常" }
        if (step.tool == "mkdir") return step.copy(prepared = true, resolvedTarget = step.destination)
        val src = when (step.tool) {
            "write_text" -> null
            "restore" -> { require(step.source.matches(Regex("[A-Za-z0-9-]+"))) { "恢复ID不正确" }; File(recovery, step.source) }
            else -> resolve(step.source)
        }
        if (src != null) check(src.isFile && !Files.isSymbolicLink(src.toPath())) { "源文件不存在或不是普通文件" }
        if (step.tool == "write_text") require(File(step.destination).extension.lowercase() in AgentPolicy.textExtensions) { "只允许创建文本格式，不创建可安装或二进制文件" }
        if (step.tool in setOf("move", "rename")) require(src!!.extension.equals(File(step.destination).extension, true)) { "改名不改变文件格式，请保留原扩展名" }
        var target = if (step.tool == "trash") File(recovery, receiptId) else resolve(step.destination)
        if (src != null) check(src.canonicalFile != target.canonicalFile) { "源和目标相同，不必重复操作" }
        val proposed = target; var n = 1
        while (target.exists()) {
            check(n < 1000 && step.tool != "trash") { "目标已存在，不能覆盖" }
            target = File(proposed.parentFile, "${proposed.nameWithoutExtension} (${n++})${if (proposed.extension.isBlank()) "" else ".${proposed.extension}"}")
        }
        return step.copy(prepared = true, resolvedTarget = target.relativeTo(root).invariantSeparatorsPath,
            fingerprint = src?.let(::sha) ?: textSha(step.arguments["text"].orEmpty()))
    }
    fun execute(step: PhoneStep): String {
        check(step.prepared && step.approved && step.tool in AgentPolicy.replaySafe) { "执行缺少批准或凭据" }
        val dst = resolve(step.resolvedTarget)
        if (step.tool == "mkdir") { check(dst.isDirectory || dst.mkdirs()) { "创建目录失败" }; return "已创建 ${step.resolvedTarget}" }
        val src = when (step.tool) {
            "write_text" -> null
            "restore" -> File(File(root, ".maid-recovery"), step.source).also {
                require(step.source.matches(Regex("[A-Za-z0-9-]+"))); check(it.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
            }
            else -> resolve(step.source)
        }
        if (dst.exists()) {
            check(dst.isFile && sha(dst) == step.fingerprint &&
                (step.tool in setOf("copy", "write_text") || src?.exists() == false)) { "目标被其他内容占用；未覆盖" }
            return "已核对上次结果：${step.resolvedTarget}"
        }
        if (src != null) check(src.isFile && sha(src) == step.fingerprint) { "源文件已变化，未执行" }
        if (step.tool == "trash") check(dst.parentFile!!.isDirectory || dst.parentFile!!.mkdir()) { "无法建立恢复区" }
        check(dst.parentFile!!.isDirectory) { "请先创建目标文件夹" }
        when (step.tool) {
            "move", "rename", "trash", "restore" -> Files.move(src!!.toPath(), dst.toPath())
            "copy", "write_text" -> {
                // Stage in an app-owned per-receipt directory. A partial file is never treated as finished.
                val stageDir = File(root, ".maid-staging")
                check(!Files.isSymbolicLink(stageDir.toPath()))
                check(stageDir.isDirectory || stageDir.mkdir())
                val temp = Files.createTempFile(stageDir.toPath(), "transfer-", ".part").toFile()
                try {
                    if (step.tool == "copy") src!!.inputStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
                    else temp.writeText(step.arguments["text"].orEmpty(), Charsets.UTF_8)
                    check(sha(temp) == step.fingerprint) { "写入校验失败" }
                    Files.move(temp.toPath(), dst.toPath())
                } finally { if (temp.exists()) temp.delete() }
            }
        }
        return if (step.tool == "trash") "已移到恢复区，恢复ID=${dst.name}，原路径=${step.source}；没有永久删除" else "已完成 ${step.tool}：${step.source} → ${step.resolvedTarget}"
    }
}
