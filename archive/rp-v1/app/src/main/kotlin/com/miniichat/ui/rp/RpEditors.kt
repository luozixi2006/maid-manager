package com.miniichat.ui.rp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.miniichat.rp.RpCharacter
import com.miniichat.rp.RpEventRule
import com.miniichat.rp.RpLocation
import com.miniichat.rp.RpScene
import com.miniichat.rp.RpStateDefinition
import com.miniichat.rp.RpStateOwner
import com.miniichat.rp.RpStateType
import com.miniichat.rp.RpStoryArc
import com.miniichat.rp.RpStoryEvent
import com.miniichat.util.newId
import java.io.File

private val stateIcons = listOf("❤️", "🤝", "😠", "📍", "💰", "🍺", "☣", "☀️", "🔎", "•")

@Composable
fun StateDefinitionDialog(
    initial: RpStateDefinition? = null,
    characters: List<RpCharacter> = emptyList(),
    locations: List<RpLocation> = emptyList(),
    scenes: List<RpScene> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (RpStateDefinition) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var icon by remember(initial?.id) { mutableStateOf(initial?.icon ?: "•") }
    var type by remember(initial?.id) { mutableStateOf(initial?.type ?: RpStateType.TEXT) }
    var owner by remember(initial?.id) { mutableStateOf(initial?.owner ?: RpStateOwner.WORLD) }
    var ownerTargetId by remember(initial?.id) { mutableStateOf(initial?.ownerTargetId.orEmpty()) }
    var ownerTargetName by remember(initial?.id) { mutableStateOf(initial?.ownerTargetName.orEmpty()) }
    var visible by remember(initial?.id) { mutableStateOf(initial?.playerVisible ?: true) }
    var defaultValue by remember(initial?.id) { mutableStateOf(initial?.defaultValue.orEmpty()) }
    var options by remember(initial?.id) { mutableStateOf(initial?.options?.joinToString(" / ").orEmpty()) }
    var maxValue by remember(initial?.id) { mutableStateOf((initial?.maxValue ?: 100.0).toString()) }
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                val folder = File(context.filesDir, "rp_state_icons").apply { mkdirs() }
                val output = File(folder, "${initial?.id ?: newId()}.img")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    output.outputStream().use(input::copyTo)
                } ?: error("无法读取图片")
                icon = output.absolutePath
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && when (owner) {
                    RpStateOwner.CHARACTER, RpStateOwner.LOCATION, RpStateOwner.SCENE ->
                        ownerTargetId.isNotBlank()
                    RpStateOwner.CUSTOM -> ownerTargetName.isNotBlank()
                    else -> true
                },
                onClick = {
                    onSave(
                        RpStateDefinition(
                            id = initial?.id ?: newId(), name = name.trim(), icon = icon,
                            type = type, owner = owner,
                            ownerTargetId = ownerTargetId,
                            ownerTargetName = when (owner) {
                                RpStateOwner.CHARACTER -> characters.firstOrNull { it.id == ownerTargetId }?.name.orEmpty()
                                RpStateOwner.LOCATION -> locations.firstOrNull { it.id == ownerTargetId }?.name.orEmpty()
                                RpStateOwner.SCENE -> scenes.firstOrNull { it.id == ownerTargetId }?.name.orEmpty()
                                RpStateOwner.CUSTOM -> ownerTargetName.trim()
                                else -> ""
                            },
                            playerVisible = visible,
                            defaultValue = defaultValue.trim(),
                            options = options.split('/', ',', '，').map { it.trim() }.filter { it.isNotBlank() },
                            maxValue = maxValue.toDoubleOrNull()?.coerceAtLeast(1.0) ?: 100.0
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(if (initial == null) "新建状态" else "编辑状态") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("状态名称") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("图标")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RpStateIcon(icon, name, 28)
                    OutlinedTextField(
                        value = if (icon.looksLikeImageReference()) "" else icon,
                        onValueChange = { icon = it.take(4) },
                        label = { Text("Emoji") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    Button(onClick = { imagePicker.launch("image/*") }) { Text("选择图片") }
                }
                LazyRow { items(stateIcons) { item ->
                    FilterChip(
                        selected = icon == item, onClick = { icon = item },
                        label = { Text(item) }, modifier = Modifier.padding(end = 6.dp)
                    )
                } }
                Spacer(Modifier.height(8.dp))
                Text("类型")
                LazyRow { items(RpStateType.entries) { item ->
                    FilterChip(
                        selected = type == item, onClick = { type = item },
                        label = { Text(item.label()) }, modifier = Modifier.padding(end = 6.dp)
                    )
                } }
                Text("所属对象")
                LazyRow { items(RpStateOwner.entries) { item ->
                    FilterChip(
                        selected = owner == item, onClick = {
                            owner = item
                            ownerTargetId = ""
                            ownerTargetName = ""
                        },
                        label = { Text(item.label()) }, modifier = Modifier.padding(end = 6.dp)
                    )
                } }
                when (owner) {
                    RpStateOwner.CHARACTER -> EntityTargetPicker(
                        "指定人物", characters.map { it.id to it.name }, ownerTargetId
                    ) { ownerTargetId = it }
                    RpStateOwner.LOCATION -> EntityTargetPicker(
                        "指定地点", locations.map { it.id to it.name }, ownerTargetId
                    ) { ownerTargetId = it }
                    RpStateOwner.SCENE -> EntityTargetPicker(
                        "指定场景", scenes.map { it.id to it.name }, ownerTargetId
                    ) { ownerTargetId = it }
                    RpStateOwner.CUSTOM -> OutlinedTextField(
                        ownerTargetName, { ownerTargetName = it },
                        label = { Text("自定义对象名称") }, modifier = Modifier.fillMaxWidth()
                    )
                    else -> Unit
                }
                OutlinedTextField(defaultValue, { defaultValue = it }, label = { Text("初始值") }, modifier = Modifier.fillMaxWidth())
                if (type == RpStateType.OPTION) {
                    OutlinedTextField(options, { options = it }, label = { Text("选项，用 / 分隔") }, modifier = Modifier.fillMaxWidth())
                }
                if (type == RpStateType.PROGRESS) {
                    OutlinedTextField(maxValue, { maxValue = it }, label = { Text("最大值") }, modifier = Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("玩家可见", modifier = Modifier.weight(1f))
                    Switch(visible, { visible = it })
                }
            }
        }
    )
}

@Composable
private fun EntityTargetPicker(
    label: String,
    entries: List<Pair<String, String>>,
    selectedId: String,
    onSelected: (String) -> Unit
) {
    Text(label)
    if (entries.isEmpty()) {
        Text("暂无可选对象，请先创建对应内容")
    } else {
        LazyRow {
            items(entries) { (id, name) ->
                FilterChip(
                    selected = selectedId == id,
                    onClick = { onSelected(id) },
                    label = { Text(name) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
    }
}

@Composable
fun LocationEditorDialog(
    initial: RpLocation? = null,
    allLocations: List<RpLocation>,
    onDismiss: () -> Unit,
    onSave: (RpLocation) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(initial?.id) { mutableStateOf(initial?.description.orEmpty()) }
    var type by remember(initial?.id) { mutableStateOf(initial?.type.orEmpty()) }
    var background by remember(initial?.id) { mutableStateOf(initial?.background.orEmpty()) }
    var status by remember(initial?.id) { mutableStateOf(initial?.currentStatus.orEmpty()) }
    var discovered by remember(initial?.id) { mutableStateOf(initial?.discovered ?: true) }
    var enterable by remember(initial?.id) { mutableStateOf(initial?.enterable ?: true) }
    var connections by remember(initial?.id) {
        mutableStateOf(initial?.connectedLocationIds?.toSet().orEmpty())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(
                    RpLocation(
                        id = initial?.id ?: newId(), name = name.trim(), description = description.trim(),
                        type = type.trim(), background = background.trim(), currentStatus = status.trim(),
                        connectedLocationIds = connections.toList(), discovered = discovered, enterable = enterable
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(if (initial == null) "新增地点" else "编辑地点") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(type, { type = it }, label = { Text("地点类型") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("描述") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(background, { background = it }, label = { Text("相关背景") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(status, { status = it }, label = { Text("当前状态") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(discovered, { discovered = it }); Text("已发现")
                    Checkbox(enterable, { enterable = it }); Text("可进入")
                }
                if (allLocations.any { it.id != initial?.id }) {
                    Text("连接地点")
                    allLocations.filter { it.id != initial?.id }.forEach { location ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = location.id in connections,
                                onCheckedChange = { checked ->
                                    connections = if (checked) connections + location.id else connections - location.id
                                }
                            )
                            Text(location.name)
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun CharacterEditorDialog(
    initial: RpCharacter? = null,
    locations: List<RpLocation>,
    onDismiss: () -> Unit,
    onSave: (RpCharacter) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var identity by remember(initial?.id) { mutableStateOf(initial?.knownIdentity.orEmpty()) }
    var known by remember(initial?.id) { mutableStateOf(initial?.knownDescription.orEmpty()) }
    var privateProfile by remember(initial?.id) { mutableStateOf(initial?.privateProfile.orEmpty()) }
    var species by remember(initial?.id) { mutableStateOf(initial?.species.orEmpty()) }
    var gender by remember(initial?.id) { mutableStateOf(initial?.gender.orEmpty()) }
    var age by remember(initial?.id) { mutableStateOf(initial?.age.orEmpty()) }
    var hair by remember(initial?.id) { mutableStateOf(initial?.hair.orEmpty()) }
    var appearance by remember(initial?.id) { mutableStateOf(initial?.appearance.orEmpty()) }
    var clothing by remember(initial?.id) { mutableStateOf(initial?.clothing.orEmpty()) }
    var locationId by remember(initial?.id) { mutableStateOf(initial?.currentLocationId.orEmpty()) }
    var appeared by remember(initial?.id) { mutableStateOf(initial?.hasAppeared ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val base = initial ?: RpCharacter(id = newId(), name = name.trim())
                onSave(base.copy(
                    name = name.trim(), knownIdentity = identity.trim(), knownDescription = known.trim(),
                    privateProfile = privateProfile.trim(), species = species.trim(),
                    gender = gender.trim(), age = age.trim(), hair = hair.trim(),
                    appearance = appearance.trim(), clothing = clothing.trim(),
                    currentLocationId = locationId, hasAppeared = appeared,
                    firstAppearedAt = if (appeared) base.firstAppearedAt ?: System.currentTimeMillis() else null
                ))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(if (initial == null) "创建角色" else "编辑角色") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("姓名") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(identity, { identity = it }, label = { Text("玩家已知身份") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(known, { known = it }, label = { Text("玩家已知简介") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(privateProfile, { privateProfile = it }, label = { Text("隐藏设定") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(species, { species = it }, label = { Text("种族（可选）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(gender, { gender = it }, label = { Text("性别（可选）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(age, { age = it }, label = { Text("年龄（可选）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(hair, { hair = it }, label = { Text("发型与发色（可选）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(appearance, { appearance = it }, label = { Text("外貌") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(clothing, { clothing = it }, label = { Text("穿着") }, modifier = Modifier.fillMaxWidth())
                Text("当前位置")
                LazyRow { items(locations) { location ->
                    FilterChip(
                        selected = locationId == location.id, onClick = { locationId = location.id },
                        label = { Text(location.name) }, modifier = Modifier.padding(end = 6.dp)
                    )
                } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已经正式出场", Modifier.weight(1f)); Switch(appeared, { appeared = it })
                }
            }
        }
    )
}

@Composable
fun EventRuleDialog(
    initial: RpEventRule? = null,
    onDismiss: () -> Unit,
    onSave: (RpEventRule) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(initial?.id) { mutableStateOf(initial?.description.orEmpty()) }
    var frequency by remember(initial?.id) { mutableStateOf(initial?.frequencyHint ?: "偶尔") }
    var enabled by remember(initial?.id) { mutableStateOf(initial?.enabled ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            onSave(RpEventRule(initial?.id ?: newId(), name.trim(), description.trim(), enabled, frequency.trim()))
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(if (initial == null) "新增事件规则" else "编辑事件规则") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("规则说明") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(frequency, { frequency = it }, label = { Text("频率提示") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", Modifier.weight(1f)); Switch(enabled, { enabled = it })
                }
            }
        }
    )
}

@Composable
fun SceneEditorDialog(
    initial: RpScene? = null,
    onDismiss: () -> Unit,
    onSave: (RpScene) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(initial?.id) { mutableStateOf(initial?.description.orEmpty()) }
    var status by remember(initial?.id) { mutableStateOf(initial?.currentStatus.orEmpty()) }
    var visible by remember(initial?.id) { mutableStateOf(initial?.playerVisible ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增场景" else "编辑场景") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("场景名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("描述") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(status, { status = it }, label = { Text("当前状态") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("玩家可见", Modifier.weight(1f)); Switch(visible, { visible = it })
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            onSave(RpScene(initial?.id ?: newId(), name.trim(), description.trim(), status.trim(), visible))
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun StoryArcEditorDialog(
    initial: RpStoryArc? = null,
    nextOrder: Int = 0,
    onDismiss: () -> Unit,
    onSave: (RpStoryArc) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(initial?.id) { mutableStateOf(initial?.description.orEmpty()) }
    var active by remember(initial?.id) { mutableStateOf(initial?.active ?: false) }
    var completed by remember(initial?.id) { mutableStateOf(initial?.completed ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增剧情阶段" else "编辑剧情阶段") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("阶段名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("阶段方向") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("当前阶段", Modifier.weight(1f)); Switch(active, { active = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已完成", Modifier.weight(1f)); Switch(completed, { completed = it })
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            onSave(RpStoryArc(
                initial?.id ?: newId(), name.trim(), description.trim(),
                initial?.order ?: nextOrder, active && !completed, completed
            ))
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun StoryEventEditorDialog(
    initial: RpStoryEvent? = null,
    characters: List<RpCharacter>,
    scenes: List<RpScene>,
    events: List<RpStoryEvent>,
    onDismiss: () -> Unit,
    onSave: (RpStoryEvent) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(initial?.id) { mutableStateOf(initial?.description.orEmpty()) }
    var trigger by remember(initial?.id) { mutableStateOf(initial?.triggerCondition.orEmpty()) }
    var result by remember(initial?.id) { mutableStateOf(initial?.result.orEmpty()) }
    var aftermath by remember(initial?.id) { mutableStateOf(initial?.aftermath.orEmpty()) }
    var completed by remember(initial?.id) { mutableStateOf(initial?.completed ?: false) }
    var characterIds by remember(initial?.id) { mutableStateOf(initial?.characterIds?.toSet().orEmpty()) }
    var sceneIds by remember(initial?.id) { mutableStateOf(initial?.sceneIds?.toSet().orEmpty()) }
    var prerequisites by remember(initial?.id) {
        mutableStateOf(initial?.prerequisiteEventIds?.toSet().orEmpty())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增剧情事件" else "编辑剧情事件") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("事件名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("描述") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(trigger, { trigger = it }, label = { Text("触发条件") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                if (characters.isNotEmpty()) {
                    Text("参与人物")
                    characters.forEach { character ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(character.id in characterIds, { checked ->
                                characterIds = if (checked) characterIds + character.id else characterIds - character.id
                            }); Text(character.name)
                        }
                    }
                }
                if (scenes.isNotEmpty()) {
                    Text("可发生场景")
                    scenes.forEach { scene ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(scene.id in sceneIds, { checked ->
                                sceneIds = if (checked) sceneIds + scene.id else sceneIds - scene.id
                            }); Text(scene.name)
                        }
                    }
                }
                events.filter { it.id != initial?.id }.forEach { event ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(event.id in prerequisites, { checked ->
                            prerequisites = if (checked) prerequisites + event.id else prerequisites - event.id
                        }); Text("前置：${event.name}")
                    }
                }
                OutlinedTextField(result, { result = it }, label = { Text("剧情结果") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(aftermath, { aftermath = it }, label = { Text("后续影响") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已完成", Modifier.weight(1f)); Switch(completed, { completed = it })
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            onSave(RpStoryEvent(
                initial?.id ?: newId(), name.trim(), description.trim(), trigger.trim(),
                characterIds.toList(), sceneIds.toList(), prerequisites.toList(), completed,
                result.trim(), aftermath.trim()
            ))
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

fun RpStateType.label() = when (this) {
    RpStateType.NUMBER -> "数字"
    RpStateType.TEXT -> "文本"
    RpStateType.BOOLEAN -> "开关"
    RpStateType.OPTION -> "选项"
    RpStateType.COUNTER -> "计数器"
    RpStateType.PROGRESS -> "进度条"
}

fun RpStateOwner.label() = when (this) {
    RpStateOwner.WORLD -> "RP 全局"
    RpStateOwner.USER -> "玩家"
    RpStateOwner.CHARACTER -> "指定人物"
    RpStateOwner.CHARACTER_TEMPLATE -> "所有角色模板"
    RpStateOwner.LOCATION -> "地点"
    RpStateOwner.SCENE -> "场景"
    RpStateOwner.CUSTOM -> "自定义对象"
}
