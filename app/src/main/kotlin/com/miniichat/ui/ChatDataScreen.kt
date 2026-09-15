package com.miniichat.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.miniichat.R
import com.miniichat.chatdata.ChatDataCodec
import com.miniichat.chatdata.HandoffMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatDataScreen(
    activeConversationTitle: String?,
    busy: Boolean,
    phase: String?,
    onBack: () -> Unit,
    onPrepareArchive: ((String, String) -> Unit) -> Unit,
    onImportArchive: (String) -> Unit,
    onGenerateHandoff: (HandoffMode, (String, String) -> Unit) -> Unit,
    onImportHandoff: (String, () -> Unit) -> Unit,
    onOpenEditor: (String, String) -> Unit,
    onImportedHandoff: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(HandoffMode.STANDARD) }
    var pendingArchive by remember { mutableStateOf<String?>(null) }
    var ioError by remember { mutableStateOf<String?>(null) }
    val archiveWriter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val content = pendingArchive
        if (uri != null && content != null) scope.launch {
            ioError = writeText(context.contentResolver, uri, content)
        }
    }
    val archiveReader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = readText(context.contentResolver, uri)
            result.onSuccess(onImportArchive).onFailure { ioError = it.message ?: "文件读取失败" }
        }
    }
    val handoffReader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = readText(context.contentResolver, uri)
            result.onSuccess { raw -> onImportHandoff(raw, onImportedHandoff) }
                .onFailure { ioError = it.message ?: "文件读取失败" }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(stringResource(R.string.chat_data), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.chat_data_summary), color = MaterialTheme.colorScheme.onSurfaceVariant)
            DataAction(
                title = stringResource(R.string.export_full_chat),
                subtitle = stringResource(R.string.export_full_chat_hint),
                enabled = !busy
            ) {
                onPrepareArchive { fileName, content ->
                    pendingArchive = content
                    archiveWriter.launch(fileName)
                }
            }
            DataAction(
                title = stringResource(R.string.import_full_chat),
                subtitle = stringResource(R.string.import_full_chat_hint),
                enabled = !busy
            ) { archiveReader.launch(arrayOf("application/json", "text/json", "text/plain")) }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.handoff_size), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HandoffMode.entries.forEach { item ->
                    FilterChip(
                        selected = mode == item,
                        onClick = { mode = item },
                        label = { Text(stringResource(item.labelResource())) }
                    )
                }
            }
            Text(
                activeConversationTitle?.let { stringResource(R.string.handoff_current_chat, it) }
                    ?: stringResource(R.string.handoff_no_chat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DataAction(
                title = stringResource(R.string.export_handoff),
                subtitle = stringResource(R.string.export_handoff_hint),
                enabled = !busy && activeConversationTitle != null
            ) {
                onGenerateHandoff(mode) { fileName, content -> onOpenEditor(fileName, content) }
            }
            DataAction(
                title = stringResource(R.string.import_handoff),
                subtitle = stringResource(R.string.import_handoff_hint),
                enabled = !busy
            ) { handoffReader.launch(arrayOf("application/json", "text/json", "text/plain")) }

            if (busy) {
                Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.height(22.dp))
                        Text(phase ?: stringResource(R.string.processing))
                    }
                }
            }
            ioError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                stringResource(R.string.handoff_privacy_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun HandoffEditorScreen(
    initialFileName: String,
    initialJson: String,
    onBack: () -> Unit,
    onUseInNewChat: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var json by remember(initialJson) { mutableStateOf(initialJson) }
    var pending by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val writer = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val content = pending
        if (uri != null && content != null) scope.launch {
            error = writeText(context.contentResolver, uri, content)
        }
    }
    fun validated(): String? = runCatching {
        ChatDataCodec.encodeHandoff(ChatDataCodec.decodeHandoff(json))
    }.onFailure { error = it.message ?: "会话交接包格式错误" }.getOrNull()

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(stringResource(R.string.handoff_preview), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.handoff_edit_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppTextField(
                value = json,
                onValueChange = { json = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                minLines = 18,
                label = { Text("JSON") }
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    validated()?.let { content ->
                        pending = content
                        writer.launch(initialFileName)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.save_handoff_file)) }
            FilledTonalButton(
                onClick = {
                    validated()?.let { content ->
                        clipboard.setText(AnnotatedString(ChatDataCodec.handoffPrompt(content)))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.copy_for_other_ai)) }
            FilledTonalButton(
                onClick = { validated()?.let(onUseInNewChat) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.continue_with_handoff)) }
            Text(
                stringResource(R.string.handoff_privacy_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DataAction(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun HandoffMode.labelResource(): Int = when (this) {
    HandoffMode.COMPACT -> R.string.handoff_compact
    HandoffMode.STANDARD -> R.string.handoff_standard
    HandoffMode.FULL -> R.string.handoff_full
}

private suspend fun readText(resolver: android.content.ContentResolver, uri: Uri): Result<String> =
    withContext(Dispatchers.IO) {
        runCatching {
            resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("无法打开文件")
        }
    }

private suspend fun writeText(
    resolver: android.content.ContentResolver,
    uri: Uri,
    content: String
): String? = withContext(Dispatchers.IO) {
    runCatching {
        resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
            ?: error("无法写入文件")
    }.exceptionOrNull()?.message
}
