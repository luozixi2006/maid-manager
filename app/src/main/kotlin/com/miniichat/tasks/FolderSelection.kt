package com.miniichat.tasks

import android.content.Context
import android.os.Environment
import java.io.File
import java.nio.file.Files

/** Browsing is local only. Selecting a folder never broadens an existing task's authority. */
object FolderSelection {
    val common = linkedMapOf("Download" to "下载", "Documents" to "文档", "DCIM" to "相机照片", "Pictures" to "图片", "Music" to "音乐", "Movies" to "视频")
    fun label(path: String): String = path.split('/').mapIndexed { i, part -> if (i == 0) common[part] ?: part else part }.joinToString(" / ")
    fun saved(context: Context): String = TaskActions.preferences(context).getString("task_folder", "Download").orEmpty()
        .takeIf { runCatching { ToolPolicy.relative(it) }.isSuccess } ?: "Download"
    fun remember(context: Context, path: String) {
        ToolPolicy.relative(path)
        val prefs = TaskActions.preferences(context)
        val recent = (listOf(path) + prefs.getString("recent_folders", "").orEmpty().lines()).filter { it.isNotBlank() }.distinct().take(8)
        prefs.edit().putString("task_folder", path).putString("recent_folders", recent.joinToString("\n")).apply()
    }
    fun storage(): File = Environment.getExternalStorageDirectory().canonicalFile
    fun safeDirectory(storage: File, path: String): File {
        val target = ToolPolicy.resolve(storage, path)
        var segment = storage
        path.split('/').forEach { segment = File(segment, it); require(!Files.isSymbolicLink(segment.toPath())) { "不选择链接目录" } }
        check(target.isDirectory) { "这个文件夹已移动或不存在，请重新选择" }
        return target
    }
    fun children(storage: File, path: String, query: String = ""): List<String> {
        val base = if (path.isEmpty()) storage.canonicalFile else safeDirectory(storage, path)
        val result = mutableListOf<String>()
        var visited = 0
        // Search names locally, including unfamiliar English directories. Never search private app data.
        base.walkTopDown().maxDepth(if (query.isBlank()) 1 else 6).onEnter { dir ->
            visited++
            visited <= 4000 && (dir == base || (!dir.name.startsWith('.') && dir.name != "Android" && !Files.isSymbolicLink(dir.toPath())))
        }.filter { it != base && it.isDirectory && !it.name.startsWith('.') && it.name != "Android" && !Files.isSymbolicLink(it.toPath()) }
            .takeWhile { visited <= 4000 }.forEach { dir ->
                val relative = dir.relativeTo(storage).invariantSeparatorsPath
                if (result.size < 200 && (query.isBlank() || label(relative).contains(query, true)) && runCatching { ToolPolicy.relative(relative) }.isSuccess) result += relative
            }
        return result.sortedWith(compareByDescending<String> { File(storage, it).lastModified() }.thenBy { it })
    }
}
