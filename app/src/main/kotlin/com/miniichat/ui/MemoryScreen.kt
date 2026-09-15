package com.miniichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniichat.R
import com.miniichat.memory.LongTermMemory
import com.miniichat.memory.MemoryCategories
import com.miniichat.util.newId

@Composable
fun MemoryScreen(
    memories: List<LongTermMemory>,
    memoryEnabled: Boolean,
    autoMemoryEnabled: Boolean,
    onBack: () -> Unit,
    onMemoryEnabledChange: (Boolean) -> Unit,
    onAutoMemoryChange: (Boolean) -> Unit,
    onUpsert: (LongTermMemory) -> Unit,
    onDelete: (String) -> Unit,
    onItemEnabledChange: (String, Boolean) -> Unit
) {
    var editing by remember { mutableStateOf<LongTermMemory?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LongTermMemory?>(null) }
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
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(stringResource(R.string.add_memory), modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(10.dp))
            if (memories.isEmpty()) {
                Text(
                    stringResource(R.string.no_memories),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            memories.forEach { memory ->
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
        }
    }

    if (creating || editing != null) {
        MemoryEditorDialog(
            initial = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = {
                onUpsert(it)
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
    onDismiss: () -> Unit,
    onSave: (LongTermMemory) -> Unit
) {
    var content by remember(initial?.id) { mutableStateOf(initial?.content ?: "") }
    var category by remember(initial?.id) { mutableStateOf(initial?.category ?: MemoryCategories.all.first()) }
    var categoryOpen by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.add_memory else R.string.edit_memory)) },
        text = {
            Column {
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
                    maxLines = 8
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = content.isNotBlank(),
                onClick = {
                    val now = System.currentTimeMillis()
                    onSave(
                        initial?.copy(content = content.trim(), category = category, updatedAt = now)
                            ?: LongTermMemory(newId(), content.trim(), category, now, now, true)
                    )
                }
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
