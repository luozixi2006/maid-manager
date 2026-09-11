package com.miniichat.ui.rp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miniichat.rp.RpCharacter
import com.miniichat.rp.RpEventRule
import com.miniichat.rp.RpLocation
import com.miniichat.rp.RpProjectMode
import com.miniichat.rp.RpProjectModules
import com.miniichat.rp.RpScene
import com.miniichat.rp.RpStateDefinition
import com.miniichat.rp.RpStoryArc
import com.miniichat.rp.RpStoryEvent
import com.miniichat.rp.RpWorld

@Composable
fun RpWorldSetupScreen(
    world: RpWorld,
    onBack: () -> Unit,
    onSaveBasics: (String, String, String, String, String, String, String, String) -> Unit,
    onUpdateStructure: (RpProjectMode, RpProjectModules) -> Unit,
    onUpsertLocation: (RpLocation) -> Unit,
    onDeleteLocation: (String) -> Unit,
    onUpsertCharacter: (RpCharacter) -> Unit,
    onDeleteCharacter: (String) -> Unit,
    onUpsertScene: (RpScene) -> Unit,
    onDeleteScene: (String) -> Unit,
    onUpsertStoryArc: (RpStoryArc) -> Unit,
    onDeleteStoryArc: (String) -> Unit,
    onUpsertStoryEvent: (RpStoryEvent) -> Unit,
    onDeleteStoryEvent: (String) -> Unit,
    onUpsertState: (RpStateDefinition) -> Unit,
    onDeleteState: (String) -> Unit,
    onUpsertEventRule: (RpEventRule) -> Unit,
    onDeleteEventRule: (String) -> Unit,
    onStart: (String, String, String, String, String, String, String, String) -> Unit
) {
    var name by remember(world.id) { mutableStateOf(world.name) }
    var summary by remember(world.id) { mutableStateOf(world.summary) }
    var publicBackground by remember(world.id) { mutableStateOf(world.publicBackground) }
    var privateBackground by remember(world.id) { mutableStateOf(world.privateBackground) }
    var rules by remember(world.id) { mutableStateOf(world.rules) }
    var visualStyle by remember(world.id) { mutableStateOf(world.visualStyle) }
    var timeRules by remember(world.id) { mutableStateOf(world.timeRules) }
    var weather by remember(world.id) { mutableStateOf(world.weather) }
    var editingLocation by remember { mutableStateOf<RpLocation?>(null) }
    var showNewLocation by remember { mutableStateOf(false) }
    var editingCharacter by remember { mutableStateOf<RpCharacter?>(null) }
    var showNewCharacter by remember { mutableStateOf(false) }
    var editingScene by remember { mutableStateOf<RpScene?>(null) }
    var showNewScene by remember { mutableStateOf(false) }
    var editingArc by remember { mutableStateOf<RpStoryArc?>(null) }
    var showNewArc by remember { mutableStateOf(false) }
    var editingStoryEvent by remember { mutableStateOf<RpStoryEvent?>(null) }
    var showNewStoryEvent by remember { mutableStateOf(false) }
    var editingState by remember { mutableStateOf<RpStateDefinition?>(null) }
    var showNewState by remember { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<RpEventRule?>(null) }
    var showNewEvent by remember { mutableStateOf(false) }
    LaunchedEffect(world.updatedAt) {
        name = world.name; summary = world.summary; publicBackground = world.publicBackground
        privateBackground = world.privateBackground; rules = world.rules
        visualStyle = world.visualStyle; timeRules = world.timeRules; weather = world.weather
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        RpTopBar(if (world.setupComplete) "编辑 RP" else "RP 预览", onBack) {
            TextButton(onClick = {
                onSaveBasics(name, summary, publicBackground, privateBackground, rules, visualStyle, timeRules, weather)
            }) { Text("保存") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            SectionTitle("RP 项目")
            Text("玩法类型")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RpProjectMode.entries.forEach { mode ->
                    FilterChip(
                        selected = world.projectMode == mode,
                        onClick = { onUpdateStructure(mode, world.modules) },
                        label = { Text(mode.label()) }
                    )
                }
            }
            ModuleSwitch("地点与地图", world.modules.locationsEnabled) {
                onUpdateStructure(world.projectMode, world.modules.copy(locationsEnabled = it))
            }
            ModuleSwitch("时间线与天气", world.modules.timelineEnabled) {
                onUpdateStructure(world.projectMode, world.modules.copy(timelineEnabled = it))
            }
            ModuleSwitch("剧情阶段与剧情事件", world.modules.storyEnabled) {
                onUpdateStructure(world.projectMode, world.modules.copy(storyEnabled = it))
            }
            ModuleSwitch("随机事件", world.modules.randomEventsEnabled) {
                onUpdateStructure(world.projectMode, world.modules.copy(randomEventsEnabled = it))
            }
            ModuleSwitch("NPC 自主模拟", world.modules.npcSimulationEnabled) {
                onUpdateStructure(world.projectMode, world.modules.copy(npcSimulationEnabled = it))
            }
            OutlinedTextField(name, { name = it }, label = { Text("RP 名称") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(summary, { summary = it }, label = { Text("RP 简介") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(publicBackground, { publicBackground = it }, label = { Text("玩家可见背景") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(privateBackground, { privateBackground = it }, label = { Text("隐藏背景") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(rules, { rules = it }, label = { Text("RP 规则") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(visualStyle, { visualStyle = it }, label = { Text("RP 视觉风格") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            if (world.modules.timelineEnabled) {
                OutlinedTextField(timeRules, { timeRules = it }, label = { Text("时间规则") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(weather, { weather = it }, label = { Text("当前天气") }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(20.dp))

            if (world.modules.locationsEnabled) {
                SectionHeader("地图与地点") { showNewLocation = true }
                if (world.locations.isEmpty()) EmptyHint("地图已启用，但还没有地点；也可以直接开始后由剧情补充。")
                world.locations.forEach { location ->
                    val connections = location.connectedLocationIds.mapNotNull { id ->
                        world.locations.firstOrNull { it.id == id }?.name
                    }.joinToString("、")
                    EditableRow(
                        title = location.name,
                        subtitle = listOf(location.type, location.description, connections.takeIf { it.isNotBlank() }?.let { "连接：$it" })
                            .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                        onEdit = { editingLocation = location },
                        onDelete = { onDeleteLocation(location.id) }
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            SectionHeader("场景") { showNewScene = true }
            if (world.scenes.isEmpty()) EmptyHint("场景是可选的叙事位置，不依赖地图；留空也能开始。")
            world.scenes.forEach { scene ->
                EditableRow(
                    title = scene.name,
                    subtitle = scene.description,
                    onEdit = { editingScene = scene },
                    onDelete = { onDeleteScene(scene.id) }
                )
            }
            Spacer(Modifier.height(20.dp))

            SectionHeader("角色") { showNewCharacter = true }
            if (world.characters.isEmpty()) EmptyHint("角色可以手动创建，也可以在剧情中自动建立。")
            world.characters.forEach { character ->
                EditableRow(
                    title = character.name,
                    subtitle = "${character.knownIdentity.ifBlank { "身份未公开" }} · ${if (character.hasAppeared) "已出场" else "尚未出场"}",
                    onEdit = { editingCharacter = character },
                    onDelete = { onDeleteCharacter(character.id) }
                )
            }
            Spacer(Modifier.height(20.dp))

            SectionHeader("自定义状态") { showNewState = true }
            if (world.stateDefinitions.isEmpty()) EmptyHint("按玩法需要创建状态；系统不预设固定 RPG 数值。")
            world.stateDefinitions.forEach { state ->
                EditableRow(
                    title = state.name,
                    subtitle = "${state.owner.label()} · ${state.type.label()} · ${if (state.playerVisible) "玩家可见" else "隐藏"}",
                    leading = { RpStateIcon(state.icon, state.name, 26) },
                    onEdit = { editingState = state },
                    onDelete = { onDeleteState(state.id) }
                )
            }
            Spacer(Modifier.height(20.dp))

            if (world.modules.storyEnabled) {
                SectionHeader("剧情阶段") { showNewArc = true }
                if (world.storyArcs.isEmpty()) EmptyHint("剧情阶段用于保持故事方向，但不会锁死玩家选择。")
                world.storyArcs.sortedBy { it.order }.forEach { arc ->
                    EditableRow(
                        title = arc.name,
                        subtitle = listOfNotNull(
                            arc.description.takeIf { it.isNotBlank() },
                            when { arc.completed -> "已完成"; arc.active -> "当前阶段"; else -> null }
                        ).joinToString(" · "),
                        onEdit = { editingArc = arc },
                        onDelete = { onDeleteStoryArc(arc.id) }
                    )
                }
                Spacer(Modifier.height(16.dp))
                SectionHeader("剧情事件") { showNewStoryEvent = true }
                if (world.storyEvents.isEmpty()) EmptyHint("剧情事件用于后台维护触发条件和影响，玩家仍用自然语言行动。")
                world.storyEvents.forEach { event ->
                    EditableRow(
                        title = event.name,
                        subtitle = listOf(event.triggerCondition, if (event.completed) "已完成" else "未完成")
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        onEdit = { editingStoryEvent = event },
                        onDelete = { onDeleteStoryEvent(event.id) }
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            if (world.modules.randomEventsEnabled) {
                SectionHeader("随机事件规则") { showNewEvent = true }
                if (world.eventRules.isEmpty()) EmptyHint("随机事件已启用，但规则可以留空。")
                world.eventRules.forEach { rule ->
                    EditableRow(
                        title = rule.name,
                        subtitle = "${rule.frequencyHint} · ${rule.description}",
                        onEdit = { editingEvent = rule },
                        onDelete = { onDeleteEventRule(rule.id) }
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    onStart(name, summary, publicBackground, privateBackground, rules, visualStyle, timeRules, weather)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (world.setupComplete) "保存并返回 RP" else "开始 RP") }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showNewLocation || editingLocation != null) LocationEditorDialog(
        initial = editingLocation, allLocations = world.locations,
        onDismiss = { showNewLocation = false; editingLocation = null },
        onSave = { onUpsertLocation(it); showNewLocation = false; editingLocation = null }
    )
    if (showNewCharacter || editingCharacter != null) CharacterEditorDialog(
        initial = editingCharacter, locations = world.locations,
        onDismiss = { showNewCharacter = false; editingCharacter = null },
        onSave = { onUpsertCharacter(it); showNewCharacter = false; editingCharacter = null }
    )
    if (showNewState || editingState != null) StateDefinitionDialog(
        initial = editingState,
        characters = world.characters,
        locations = world.locations,
        scenes = world.scenes,
        onDismiss = { showNewState = false; editingState = null },
        onSave = { onUpsertState(it); showNewState = false; editingState = null }
    )
    if (showNewEvent || editingEvent != null) EventRuleDialog(
        initial = editingEvent,
        onDismiss = { showNewEvent = false; editingEvent = null },
        onSave = { onUpsertEventRule(it); showNewEvent = false; editingEvent = null }
    )
    if (showNewScene || editingScene != null) SceneEditorDialog(
        initial = editingScene,
        onDismiss = { showNewScene = false; editingScene = null },
        onSave = { onUpsertScene(it); showNewScene = false; editingScene = null }
    )
    if (showNewArc || editingArc != null) StoryArcEditorDialog(
        initial = editingArc,
        nextOrder = world.storyArcs.size,
        onDismiss = { showNewArc = false; editingArc = null },
        onSave = { onUpsertStoryArc(it); showNewArc = false; editingArc = null }
    )
    if (showNewStoryEvent || editingStoryEvent != null) StoryEventEditorDialog(
        initial = editingStoryEvent,
        characters = world.characters,
        scenes = world.scenes,
        events = world.storyEvents,
        onDismiss = { showNewStoryEvent = false; editingStoryEvent = null },
        onSave = { onUpsertStoryEvent(it); showNewStoryEvent = false; editingStoryEvent = null }
    )
}

@Composable
private fun ModuleSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun RpProjectMode.label() = when (this) {
    RpProjectMode.SANDBOX -> "自由沙盒"
    RpProjectMode.STORY -> "重剧情"
    RpProjectMode.HYBRID -> "混合"
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SectionHeader(text: String, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = onAdd) { Icon(Icons.Default.Add, null); Text(" 添加") }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun EditableRow(
    title: String,
    subtitle: String,
    leading: @Composable (() -> Unit)? = null,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "编辑") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除") }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
}
