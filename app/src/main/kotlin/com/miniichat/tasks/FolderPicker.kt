package com.miniichat.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.miniichat.ui.AppTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FolderPicker(initial: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    var path by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<String>()) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var valid by remember { mutableStateOf(false) }
    val storage = remember { FolderSelection.storage() }
    LaunchedEffect(path, query) {
        busy = true; error = ""; valid = false
        try {
            entries = withContext(Dispatchers.IO) { FolderSelection.children(storage, path, query.trim()) }
            valid = path.isNotBlank()
        } catch (e: Exception) { entries = emptyList(); error = e.message ?: "无法读取目录" }
        finally { busy = false }
    }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("选择文件夹", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onDismiss) { Text("返回") }
            }
            Text(FolderSelection.label(path).ifBlank { "手机共享存储" }, style = MaterialTheme.typography.titleSmall)
            if (path.isNotBlank()) Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ path = path.substringBeforeLast('/', ""); query = "" }, enabled = path.isNotBlank()) { Text("上一级 / 常用目录") }
            AppTextField(query, { query = it }, label = { Text("搜索文件夹名称") }, singleLine = true)
            Text("按最近修改排序 · 点文件夹进入，再确认选择", style = MaterialTheme.typography.bodySmall)
            if (busy) Text("正在读取文件夹…", style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                if (query.isBlank()) {
                    val shortcuts = if (path.isBlank()) FolderSelection.common.keys.toList() else TaskActions.preferences(context).getString("recent_folders", "").orEmpty().lines().filter { it.isNotBlank() }
                    items(shortcuts.distinct().filter { it != path }, key = { "recent:$it" }) { item ->
                        TextButton({ path = item; query = "" }) { Text("${if (path.isBlank()) "常用" else "最近"} · ${FolderSelection.label(item)}") }
                    }
                }
                items(entries, key = { it }) { item ->
                    Column(Modifier.fillMaxWidth().clickable { path = item; query = "" }.padding(vertical = 12.dp)) {
                        Text("▸ ${FolderSelection.label(item)}")
                        if (FolderSelection.label(item) != item) Text(item, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!busy && entries.isEmpty() && error.isBlank()) item { Text(if (query.isBlank()) "没有子文件夹，可以选择当前目录" else "未找到，试试上一级目录或其他关键词") }
            }
            Button(modifier = Modifier.fillMaxWidth(), enabled = valid && !busy, onClick = {
        runCatching { FolderSelection.safeDirectory(storage, path) }.onSuccess { onSelect(path) }.onFailure { error = it.message.orEmpty(); valid = false }
            }) { Text("使用此文件夹") }
        }
    }
}
