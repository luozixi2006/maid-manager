package com.miniichat.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniichat.error.AppError
import com.miniichat.error.AppErrorExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ErrorCenterScreen(
    errors: List<AppError>,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<AppError?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var exportPayload by remember { mutableStateOf<String?>(null) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val content = exportPayload
        if (uri != null && content != null) {
            scope.launch {
                localMessage = writeErrorReport(context.contentResolver, uri, content)
                    ?.let { "导出失败：$it" }
                    ?: "错误报告已导出"
            }
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar("诊断与错误", onBack)
        if (errors.isEmpty()) {
            EmptyErrorCenter()
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        exportPayload = AppErrorExport.encode(errors)
                        exportLauncher.launch(errorReportFileName())
                    }
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null, Modifier.size(18.dp))
                    Text("  导出报告")
                }
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    onClick = { confirmClear = true }
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, Modifier.size(18.dp))
                    Text("  清空记录")
                }
            }
            Text(
                "仅保留最近 100 条。本报告不包含密钥、请求正文、回复正文或聊天内容。",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            localMessage?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("导出失败")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(errors, key = { it.id }) { error ->
                    ErrorHistoryItem(
                        error = error,
                        onClick = { selected = error },
                        onDelete = { onDelete(error.id) }
                    )
                }
            }
        }
    }

    selected?.let { error ->
        ErrorDetailDialog(error = error, onDismiss = { selected = null })
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空错误记录？") },
            text = { Text("这会删除当前设备保存的全部错误记录，不会影响聊天数据。") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun EmptyErrorCenter() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.ReportProblem, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text("暂无错误记录", style = MaterialTheme.typography.titleMedium)
            Text(
                "应用遇到可诊断的问题后，会在这里保存不含隐私内容的说明。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorHistoryItem(error: AppError, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        error.httpStatus?.let { "HTTP $it" } ?: error.area.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    error.userMessage,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${formatErrorTime(error.occurredAt)}  ·  ${error.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除这条记录")
            }
        }
    }
}

@Composable
private fun ErrorDetailDialog(error: AppError, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(error.title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(error.userMessage)
                ErrorExplanation(error, showDiagnostics = true)
                HorizontalDivider()
                Text(
                    "错误编号：${error.id}\n发生时间：${formatErrorTime(error.occurredAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "诊断记录不会保存请求正文、回复正文、聊天内容或 API 密钥。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

private suspend fun writeErrorReport(
    resolver: ContentResolver,
    uri: Uri,
    content: String
): String? = withContext(Dispatchers.IO) {
    runCatching {
        resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
            ?: error("无法打开所选文件")
    }.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName }
}

private fun errorReportFileName(now: Date = Date()): String =
    "maid-manager-errors-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)}.json"

private fun formatErrorTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
