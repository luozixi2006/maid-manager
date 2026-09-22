package com.miniichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniichat.R
import com.miniichat.data.Assistant
import com.miniichat.util.newId

@Composable
fun AssistantsScreen(
    assistants: List<Assistant>,
    activeId: String,
    editOnOpenId: String? = null,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onUpsert: (Assistant) -> Unit,
    onPhotoError: (String) -> Unit,
    onDelete: (String) -> Unit,
    legacyProactiveEnabled: Boolean = false,
    onQuietHours: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val notifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    var editingId by rememberSaveable(editOnOpenId) { mutableStateOf(editOnOpenId) }
    val editing = assistants.firstOrNull { it.id == editingId }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Assistant?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(stringResource(R.string.personas), onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Disclosure("名字与人设如何使用") { Text(stringResource(R.string.persona_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(12.dp))
            TextButton(onQuietHours) { Text("消息通知与安静时段") }
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(stringResource(R.string.add_persona), modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            assistants.forEach { assistant ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onSelect(assistant.id) },
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = assistant.id == activeId,
                            onClick = { onSelect(assistant.id) }
                        )
                        PersonAvatar(assistant.displayName, assistant.avatarPath, 36.dp,
                            Modifier.padding(end = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(assistant.name, style = MaterialTheme.typography.titleSmall)
                            if (assistant.conversationName.isNotBlank()) {
                                Text("对话中显示为 ${assistant.displayName}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                assistant.systemPrompt,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { editingId = assistant.id }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit))
                        }
                        IconButton(
                            onClick = { deleting = assistant },
                            enabled = assistants.size > 1
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        PersonaEditorDialog(
            initial = editing,
            legacyProactiveEnabled = legacyProactiveEnabled,
            onPhotoError = onPhotoError,
            onDismiss = {
                creating = false
                editingId = null
            },
            onSave = {
                onUpsert(it)
                onSelect(it.id)
                if (it.proactiveEnabled && android.os.Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                creating = false
                editingId = null
            }
        )
    }

    deleting?.let { assistant ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_persona_title)) },
            text = { Text(stringResource(R.string.delete_persona_message, assistant.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(assistant.id)
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
private fun PersonaEditorDialog(
    initial: Assistant?,
    legacyProactiveEnabled: Boolean,
    onPhotoError: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Assistant) -> Unit
) {
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var displayName by rememberSaveable(initial?.id) { mutableStateOf(initial?.conversationName ?: "") }
    var avatarPath by rememberSaveable(initial?.id) { mutableStateOf(initial?.avatarPath) }
    val photos = rememberPhotoActions(initial?.id ?: "new-persona", 1,
        onImported = { it.firstOrNull()?.let { path -> avatarPath = path } },
        onError = onPhotoError)
    var prompt by rememberSaveable(initial?.id) { mutableStateOf(initial?.systemPrompt ?: "") }
    var proactiveEnabled by rememberSaveable(initial?.id) { mutableStateOf(initial?.canContact(legacyProactiveEnabled) ?: false) }
    var timing by rememberSaveable(initial?.id) { mutableStateOf(initial?.proactiveTiming ?: "persona") }
    var minimum by rememberSaveable(initial?.id) { mutableStateOf((initial?.proactiveMinMinutes ?: 60).toString()) }
    var maximum by rememberSaveable(initial?.id) { mutableStateOf((initial?.proactiveMaxMinutes ?: 240).toString()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val testScope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.add_persona else R.string.edit_persona))
        },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PersonAvatar(displayName.ifBlank { name }, avatarPath, 64.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        TextButton(onClick = photos.choose, enabled = !photos.busy) { Text("选择头像") }
                        Row {
                            TextButton(onClick = photos.take, enabled = !photos.busy) { Text("拍照") }
                            if (avatarPath != null) TextButton(onClick = { avatarPath = null }) { Text("移除") }
                        }
                    }
                }
                if (photos.busy) Text("正在处理照片…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("人设备注") },
                    supportingText = { Text("用来说明这个人设的用途") },
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                AppTextField(
                    value = displayName,
                    onValueChange = { displayName = it.take(60) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("对话名字（可选）") },
                    placeholder = { Text(name.ifBlank { "留空则使用人设名称" }) },
                    supportingText = { Text("聊天和通知使用此名字；留空时跟随人设备注") },
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                AppTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.assistant_system_prompt)) },
                    minLines = 5,
                    maxLines = 10
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "让这个人设主动来找我",
                        Modifier.weight(1f)
                    )
                    AppSwitch(
                        checked = proactiveEnabled,
                        onCheckedChange = { proactiveEnabled = it }
                    )
                }
                Text("依照人设和最近聊天来问候、分享话题，不用配置任务规则。开启后会按需调用当前模型；未回复时不连发。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (proactiveEnabled) {
                    if(initial!=null) Text(com.miniichat.proactive.ProactiveDiagnostics.describe(context,initial.id),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row { listOf("persona" to "随人设", "fixed" to "固定间隔", "random" to "随机间隔").forEach { (key, label) ->
                        TextButton({ timing = key }) { Text(if (timing == key) "✓ $label" else label) }
                    } }
                    if (timing != "persona") {
                        AppTextField(minimum, { minimum = it.filter(Char::isDigit).take(4) }, label = { Text(if (timing == "fixed") "间隔分钟（15–1440）" else "最短间隔分钟") })
                        if (timing == "random") AppTextField(maximum, { maximum = it.filter(Char::isDigit).take(4) }, label = { Text("最长间隔分钟（最多1440）") })
                    }
                    Text("这是尝试联系的间隔，受系统后台调度、安静时段和未回复防打扰影响，不保证准点发消息。", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = initial != null && !testing, onClick = {
                    testing = true; testResult = ""
                    testScope.launch {
                        try { testResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.miniichat.tasks.ScreenCompanion.test(context, initial!!.id) } }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { testResult = e.message?.take(160) ?: "测试未成功，请检查模型服务" }
                        finally { testing = false }
                    }
                }) { Text(if (testing) "正在生成问候…" else "测试一次问候（使用已保存人设）") }
                if (testResult.isNotBlank()) Text(testResult, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = (initial ?: Assistant(id = newId(), name = name.trim())).copy(
                        name = name.trim(),
                        conversationName = displayName.trim(),
                        avatarPath = avatarPath,
                        systemPrompt = prompt.trim(),
                        proactiveEnabled = proactiveEnabled,
                        proactiveConsentVersion = 1,
                        proactiveTiming = timing,
                        proactiveMinMinutes = minimum.toIntOrNull()?.coerceIn(15, 1440) ?: 60,
                        proactiveMaxMinutes = (maximum.toIntOrNull() ?: 240).coerceIn((minimum.toIntOrNull() ?: 60).coerceIn(15, 1440), 1440),
                        nextProactiveCheckAt = if (proactiveEnabled != initial?.canContact(legacyProactiveEnabled) || (initial?.proactiveConsentVersion ?: 0) < 1 || timing != initial?.proactiveTiming || minimum.toIntOrNull() != initial?.proactiveMinMinutes || maximum.toIntOrNull() != initial?.proactiveMaxMinutes) 0L else initial?.nextProactiveCheckAt ?: 0L
                    )
                    onSave(value)
                },
                enabled = name.isNotBlank() && prompt.isNotBlank() && !photos.busy
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
