package com.miniichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniichat.R
import com.miniichat.data.Assistant
import com.miniichat.memory.LongTermMemory
import com.miniichat.memory.MemoryCategories
import com.miniichat.util.newId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val SAVE_FAILED = "保存失败，请重试"
private const val MAX_CONTENT = 4000

/** Owner for a memory: the initial persona when valid, otherwise the first persona, otherwise none. */
internal fun resolvePersonaId(personaIds: List<String>, initialPersonaId: String): String =
    if (initialPersonaId.isNotBlank() && personaIds.contains(initialPersonaId)) initialPersonaId
    else personaIds.firstOrNull() ?: ""

/** Only the selected persona's active memories are listed; unassigned legacy memories stay hidden. */
internal fun memoriesForPersona(memories: List<LongTermMemory>, personaId: String): List<LongTermMemory> =
    if (personaId.isBlank()) emptyList()
    else memories.filter { it.personaId == personaId && it.status == "active" }

/** Legacy memories without an owner. They are never assigned automatically. */
internal fun legacyMemories(memories: List<LongTermMemory>): List<LongTermMemory> =
    memories.filter { it.personaId.isBlank() && it.status == "active" }

internal fun memoryContentError(content: String): String? {
    val length = content.trim().length
    return when {
        length == 0 -> "内容不能为空"
        length > MAX_CONTENT -> "内容最多 $MAX_CONTENT 字"
        else -> null
    }
}

@Composable
fun MemoryScreen(
    memories: List<LongTermMemory>,
    personas: List<Assistant> = emptyList(),
    initialPersonaId: String = "",
    memoryEnabled: Boolean,
    autoMemoryEnabled: Boolean,
    queueStatus: String = "",
    onBack: () -> Unit,
    onMemoryEnabledChange: (Boolean) -> Unit,
    onAutoMemoryChange: (Boolean) -> Unit,
    onUpsert: suspend (LongTermMemory) -> Unit,
    onDelete: (String) -> Unit,
    onItemEnabledChange: (String, Boolean) -> Unit,
    onRetry: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<LongTermMemory?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LongTermMemory?>(null) }
    var pickedPersonaId by rememberSaveable { mutableStateOf("") }
    var legacyBusyId by remember { mutableStateOf<String?>(null) }
    var legacyError by remember { mutableStateOf<String?>(null) }

    val defaultPersonaId = remember(personas, initialPersonaId) {
        resolvePersonaId(personas.map { it.id }, initialPersonaId)
    }
    val ownerId = pickedPersonaId.takeIf { id -> personas.any { it.id == id } } ?: defaultPersonaId
    val ownerName = personas.firstOrNull { it.id == ownerId }?.displayName.orEmpty()
    val visible = memoriesForPersona(memories, ownerId)
    val legacy = legacyMemories(memories)

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(stringResource(R.string.long_term_memory), onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            AppGroup { ToggleSetting(
                title = stringResource(R.string.enable_memory),
                subtitle = stringResource(R.string.enable_memory_hint),
                checked = memoryEnabled,
                onCheckedChange = onMemoryEnabledChange
            )
            ToggleSetting(
                title = stringResource(R.string.auto_memory),
                subtitle = stringResource(R.string.auto_memory_hint),
                checked = autoMemoryEnabled,
                enabled = memoryEnabled,
                onCheckedChange = onAutoMemoryChange
            )
            }
            Spacer(Modifier.height(12.dp))
            PersonaPicker(
                personas = personas,
                selectedId = ownerId,
                onSelect = { pickedPersonaId = it }
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { creating = true },
                enabled = ownerId.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(stringResource(R.string.add_memory), modifier = Modifier.padding(start = 8.dp))
            }
            if (ownerId.isBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "尚未选择人设，无法添加或修改记忆",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (queueStatus.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        queueStatus,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (visible.isEmpty()) {
                Text(
                    stringResource(R.string.no_memories),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            visible.forEach { memory ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                memory.category,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                memory.content,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        AppSwitch(
                            checked = memory.enabled,
                            onCheckedChange = { onItemEnabledChange(memory.id, it) }
                        )
                        IconButton(onClick = { editing = memory }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit))
                        }
                        IconButton(onClick = { deleting = memory }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                }
            }
            val unresolved = memories.filter { it.personaId == ownerId && it.status == "conflict" }
            if (unresolved.isNotEmpty()) AppGroup {
                Column(Modifier.padding(16.dp)) {
                    Disclosure(title = "待确认的记忆（${unresolved.size}）") {
                        Text("新旧说法不一致，暂不作为事实使用。你可以更正，也可以在以后聊天中自然澄清。", style = MaterialTheme.typography.bodySmall)
                        unresolved.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.content, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton({ editing = item }) { Text("更正") }
                                IconButton({ deleting = item }) { Icon(Icons.Default.Delete, "删除") }
                            }
                        }
                    }
                }
            }
            if (legacy.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AppGroup { Column(Modifier.padding(horizontal = 16.dp)) {
                    Disclosure(title = "未指定人设的记忆（${legacy.size}）") {
                        Text(
                            "这些旧记忆不属于任何人设，只有手动归入后才会出现在该人设下。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        legacy.forEach { memory ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        memory.category,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        memory.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(
                                    enabled = ownerId.isNotBlank() && legacyBusyId == null,
                                    onClick = {
                                        if (legacyBusyId != null || ownerId.isBlank()) return@TextButton
                                        legacyBusyId = memory.id
                                        legacyError = null
                                        scope.launch {
                                            try {
                                                // Explicit assignment keeps id, timestamps and other metadata.
                                                onUpsert(memory.copy(personaId = ownerId))
                                            } catch (cancel: CancellationException) {
                                                throw cancel
                                            } catch (_: Throwable) {
                                                legacyError = SAVE_FAILED
                                            } finally {
                                                legacyBusyId = null
                                            }
                                        }
                                    }
                                ) {
                                    Text(if (ownerName.isBlank()) "归入人设" else "归入「$ownerName」")
                                }
                            }
                        }
                        legacyError?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                }
            }
        }
    }

    if (creating || editing != null) {
        MemoryEditorDialog(
            initial = editing,
            targetPersonaId = ownerId,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { record ->
                // Only closes after the write succeeded; failures keep the draft open.
                onUpsert(record)
                creating = false
                editing = null
            }
        )
    }

    deleting?.let { memory ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_memory_title)) },
            text = { Text(memory.content) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(memory.id)
                    deleting = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun PersonaPicker(
    personas: List<Assistant>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val selectedName = personas.firstOrNull { it.id == selectedId }?.displayName.orEmpty()
    Box {
        AppTextField(
            value = selectedName,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            label = { Text("记忆归属人设") },
            readOnly = true,
            trailingIcon = {
                IconButton(onClick = { open = true }, enabled = personas.isNotEmpty()) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "选择人设")
                }
            }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            personas.forEach { persona ->
                DropdownMenuItem(
                    text = { Text(persona.displayName) },
                    onClick = {
                        onSelect(persona.id)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ToggleSetting(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AppSwitch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MemoryEditorDialog(
    initial: LongTermMemory?,
    targetPersonaId: String,
    onDismiss: () -> Unit,
    onSave: suspend (LongTermMemory) -> Unit
) {
    var content by remember(initial?.id) { mutableStateOf(initial?.content ?: "") }
    var category by remember(initial?.id) { mutableStateOf(initial?.category ?: MemoryCategories.all.first()) }
    var categoryOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val contentError = memoryContentError(content)
    // New memories need an owner; edits keep the existing owner instead.
    val ownerReady = initial != null || targetPersonaId.isNotBlank()
    val canSave = contentError == null && ownerReady && !saving

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.imePadding(),
        title = { Text(stringResource(if (initial == null) R.string.add_memory else R.string.edit_memory)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Box {
                    AppTextField(
                        value = category,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.memory_category)) },
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = { categoryOpen = true }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                        }
                    )
                    DropdownMenu(expanded = categoryOpen, onDismissRequest = { categoryOpen = false }) {
                        MemoryCategories.all.forEach { value ->
                            DropdownMenuItem(
                                text = { Text(value) },
                                onClick = {
                                    category = value
                                    categoryOpen = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                AppTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.memory_content)) },
                    minLines = 4,
                    maxLines = 8,
                    isError = contentError != null && content.isNotEmpty()
                )
                Text(
                    "${content.trim().length}/$MAX_CONTENT",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (contentError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let { message ->
                    Spacer(Modifier.height(4.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    if (!canSave) return@TextButton
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            val now = System.currentTimeMillis()
                            val trimmed = content.trim()
                            val record = initial?.copy(content = trimmed, category = category, updatedAt = now, lastConfirmedAt = now, status = "active")
                                ?: LongTermMemory(
                                    id = newId(),
                                    content = trimmed,
                                    category = category,
                                    createdAt = now,
                                    updatedAt = now,
                                    enabled = true,
                                    personaId = targetPersonaId
                                )
                            onSave(record)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Throwable) {
                            error = SAVE_FAILED
                        } finally {
                            saving = false
                        }
                    }
                }
            ) { Text(if (saving) "保存中…" else stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
