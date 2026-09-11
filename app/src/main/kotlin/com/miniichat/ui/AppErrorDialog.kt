package com.miniichat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miniichat.error.AppError

/** A concise error dialog that always explains the reason, not just the symptom. */
@Composable
fun AppErrorDialog(
    error: AppError,
    onDismiss: () -> Unit,
    onOpenDetails: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(error.title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(error.userMessage)
                ErrorExplanation(error)
                Text(
                    "错误编号：${error.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
        dismissButton = onOpenDetails?.let { openDetails ->
            {
                TextButton(onClick = openDetails) { Text("查看详情") }
            }
        }
    )
}

@Composable
internal fun ErrorExplanation(error: AppError, showDiagnostics: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("为什么会发生", fontWeight = FontWeight.SemiBold)
        Text(
            error.explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (error.suggestions.isNotEmpty()) {
            Text("可以怎么做", fontWeight = FontWeight.SemiBold)
            error.suggestions.forEach { suggestion ->
                Row(Modifier.fillMaxWidth()) {
                    Text("• ", color = MaterialTheme.colorScheme.primary)
                    Text(suggestion, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (showDiagnostics) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("诊断信息", fontWeight = FontWeight.SemiBold)
            DiagnosticLine("错误类型", "${error.type.label}（${error.code}）")
            DiagnosticLine("发生位置", "${error.area.label} · ${error.operation.label}")
            error.httpStatus?.let { DiagnosticLine("HTTP 状态", it.toString()) }
            error.providerHost?.let { DiagnosticLine("服务主机", it) }
            error.modelId?.let { DiagnosticLine("模型", it) }
            if (error.causeChain.isNotEmpty()) {
                DiagnosticLine("异常链", error.causeChain.joinToString(" → ") { it.substringAfterLast('.') })
            }
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
