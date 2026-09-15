package com.miniichat.tasks

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.nio.file.Files

class DownloadsTools(private val context: Context, private val scope: String) {
    fun permitted(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    fun root(): File {
        check(permitted()) { "需要下载目录访问权限" }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
        ToolPolicy.relative(scope, true)
        return (if (scope.isBlank()) downloads else ToolPolicy.resolve(downloads, scope)).also {
            check(it.isDirectory) { "授权的子目录不存在，请检查目录名称" }
        }
    }
    fun scan(): List<FileFact> {
        val base = root()
        PDFBoxResourceLoader.init(context)
        val results = mutableListOf<FileFact>()
        // Never follow symlinks, never inspect private app directories; bound depth and parser input.
        base.walkTopDown().maxDepth(8).onEnter {
            !Files.isSymbolicLink(it.toPath()) && (it == base || it.canonicalFile.toPath().startsWith(base.toPath()))
        }.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.extension.equals("pdf", true) }.forEach { file ->
            check(results.size < 200) { "目录中 PDF 超过 200 个，请选择更小的子目录后再整理" }
            val snippet = if (file.length() <= 16 * 1024 * 1024) runCatching {
                PDDocument.load(file).use { doc ->
                    if (doc.isEncrypted) "[加密 PDF，内容未读取]" else PDFTextStripper().apply { startPage = 1; endPage = 2 }
                        .getText(doc).take(450).ifBlank { "[扫描件或无文本，不确定时请询问用户]" }
                }
            }.getOrDefault("[无法提取正文，不确定时请询问用户]") else "[文件较大，仅按文件名判断；不确定请询问]"
            results += FileFact(file.relativeTo(base).invariantSeparatorsPath, file.length(), file.lastModified(), snippet)
        }
        return results
    }
    fun prepare(step: PhoneStep, inventory: List<FileFact>): PhoneStep = JournaledFiles(root()).prepare(step, inventory)
    fun execute(step: PhoneStep): String = JournaledFiles(root()).execute(step)
}

/** Native filesystem tool, deliberately independent of Android permissions and model providers. */
class JournaledFiles(private val base: File) {
    fun prepare(step: PhoneStep, inventory: List<FileFact>): PhoneStep {
        check(step.tool in ToolPolicy.fileTools) { "工具不在允许列表中" }
        if (step.tool == "mkdir") return step.copy(prepared = true, resolvedTarget = step.destination)
        val source = ToolPolicy.resolve(base, step.source)
        val fact = inventory.firstOrNull { it.path == step.source } ?: error("源文件不在本次扫描中")
        check(source.isFile && source.length() == fact.size && source.lastModified() == fact.modified) {
            "文件在扫描后发生变化，已暂停；请检查后跳过此步，避免移动错误文件"
        }
        var target = ToolPolicy.resolve(base, step.destination)
        check(source != target) { "源文件与目标相同，无需移动；可跳过此步" }
        val requested = target
        var n = 1
        while (target.exists()) {
            check(n < 1000) { "同名文件过多，请选择其他名称" }
            target = File(requested.parentFile, "${requested.nameWithoutExtension} ($n).pdf")
            n++
        }
        return step.copy(prepared = true, resolvedTarget = target.relativeTo(base).invariantSeparatorsPath,
            fingerprint = digest(source))
    }
    fun execute(step: PhoneStep): String {
        check(step.prepared && step.approved) { "操作未经程序授权" }
        check(step.tool in ToolPolicy.fileTools) { "工具不在允许列表中" }
        val target = ToolPolicy.resolve(base, step.resolvedTarget)
        if (step.tool == "mkdir") {
            check(target.isDirectory || target.mkdirs()) { "文件夹创建失败，请检查目录权限和存储空间" }
            return "文件夹：${step.resolvedTarget}"
        }
        val source = ToolPolicy.resolve(base, step.source)
        if (!source.exists()) {
            // Crash after an atomic move but before its receipt was committed: do not move again.
            check(target.isFile && digest(target) == step.fingerprint) { "源文件不存在，且目标无法核对；已停止，未覆盖任何文件" }
            return "已核对恢复：${step.source} → ${step.resolvedTarget}"
        }
        check(source.isFile && digest(source) == step.fingerprint) { "源文件内容已变化，已停止操作" }
        check(!target.exists()) { "执行前目标被其他程序占用，已暂停且未覆盖；可以跳过此步" }
        check(target.parentFile?.isDirectory == true) { "目标文件夹不存在，请先创建文件夹或跳过此步" }
        // Same Downloads filesystem; never REPLACE_EXISTING, never delete/copy fallback on failure.
        Files.move(source.toPath(), target.toPath())
        return "${step.source} → ${step.resolvedTarget}"
    }
    private fun digest(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(65536)
            while (true) { val n = input.read(bytes); if (n < 0) break; md.update(bytes, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
