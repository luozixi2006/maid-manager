package com.miniichat.ui.rp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miniichat.rp.RpCharacter
import com.miniichat.rp.RpSceneEntry
import com.miniichat.rp.RpStateDefinition
import com.miniichat.rp.RpStateOwner
import com.miniichat.rp.RpStateType
import com.miniichat.rp.RpStateValue
import com.miniichat.rp.RpWorld
import com.miniichat.rp.activeArcName
import com.miniichat.rp.locationName
import com.miniichat.rp.worldTimeLabel
import kotlinx.coroutines.launch

@Composable
fun RpWorldScreen(
    world: RpWorld,
    busy: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenStates: () -> Unit,
    onOpenCharacter: (String) -> Unit,
    onStartDialogue: (String) -> Unit,
    onTravel: (String) -> Unit,
    onSubmit: (String, Boolean) -> Unit
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var actionMenu by remember { mutableStateOf(false) }
    val appearedCharacters = world.characters.filter { it.hasAppeared }
    val presentCharacters = world.presentCharacterIds.mapNotNull { id ->
        world.characters.firstOrNull { it.id == id && it.hasAppeared }
    }
    LaunchedEffect(world.sceneHistory.size, busy) {
        if (world.sceneHistory.isNotEmpty()) {
            listState.animateScrollToItem(
                (world.sceneHistory.size + 4).coerceAtMost(listState.layoutInfo.totalItemsCount - 1)
                    .coerceAtLeast(0)
            )
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxSize().width(310.dp)
            ) {
                Column(Modifier.fillMaxSize().padding(WindowInsets.statusBars.asPaddingValues())) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            "已登场人物",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (appearedCharacters.isEmpty()) {
                        Text(
                            "故事里还没有正式登场的人物。",
                            modifier = Modifier.padding(20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                    } else {
                        LazyColumn(
                            Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(appearedCharacters, key = { it.id }) { character ->
                                RpRevealItem(character.id) {
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                scope.launch { drawer.close() }
                                                onOpenCharacter(character.id)
                                            }.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RpAvatar(character.avatarPath, character.name, 44)
                                        Column(Modifier.padding(start = 11.dp)) {
                                            Text(character.name, fontWeight = FontWeight.Medium)
                                            Text(
                                                character.knownIdentity.ifBlank {
                                                    character.currentAction.ifBlank { "故事人物" }
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextButton(
                        onClick = { scope.launch { drawer.close() }; onOpenSettings() },
                        modifier = Modifier.fillMaxWidth().padding(8.dp)
                    ) {
                        Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
                        Text("  RP 设置")
                    }
                    Spacer(Modifier.padding(WindowInsets.navigationBars.asPaddingValues()))
                }
            }
        }
    ) {
        RpPageReveal {
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()
            ) {
                RpSceneTopBar(world, onBack, onOpenSettings, onOpenMap)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    item("scene-context") { SceneContext(world) }
                    items(world.sceneHistory.takeLast(50), key = { it.id }) { entry ->
                        if (entry.role == "user") PlayerAction(entry) else Narration(entry)
                    }
                    if (presentCharacters.isNotEmpty()) {
                        item("present-title") {
                            Text(
                                "当前人物",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        items(presentCharacters, key = { "present-${it.id}" }) { character ->
                            RpRevealItem(character.id) {
                                CurrentCharacterRow(character) { onStartDialogue(character.id) }
                            }
                        }
                    }
                    if (world.modules.locationsEnabled) {
                        item("nearby") { NearbyLocations(world, busy, onTravel, onOpenMap) }
                    }
                    if (busy) {
                        item("busy") {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(
                                    "剧情正在继续……",
                                    modifier = Modifier.padding(start = 10.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(4.dp)) }
                }
                RpActionInput(
                    input = input,
                    onInput = { input = it },
                    busy = busy,
                    menuOpen = actionMenu,
                    onMenuChange = { actionMenu = it },
                    onSend = { expand ->
                        val value = input.trim()
                        if (value.isNotEmpty()) {
                            input = ""
                            onSubmit(value, expand)
                        }
                    },
                    onOpenStates = onOpenStates,
                    onOpenMap = onOpenMap,
                    mapEnabled = world.modules.locationsEnabled,
                    onOpenCharacters = { scope.launch { drawer.open() } }
                )
            }
        }
    }
}

@Composable
private fun RpSceneTopBar(
    world: RpWorld,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onMap: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回 RP 列表")
            }
            Column(Modifier.weight(1f)) {
                Text(world.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    listOfNotNull(
                        world.worldTimeLabel().takeIf { world.modules.timelineEnabled },
                        world.weather.takeIf { world.modules.timelineEnabled && it.isNotBlank() },
                        world.activeArcName()?.let { "剧情：$it" }
                    )
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (world.modules.locationsEnabled) {
                IconButton(onClick = onMap) { Icon(Icons.Default.Map, "地图") }
            }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "RP 设置") }
        }
        if (world.modules.locationsEnabled) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onMap)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.LocationOn, null, Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    world.locationName(),
                    modifier = Modifier.weight(1f).padding(start = 7.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text("查看地图", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    }
}

@Composable
private fun SceneContext(world: RpWorld) {
    val location = world.locations.firstOrNull { it.id == world.currentLocationId }
    val scene = world.scenes.firstOrNull { it.id == world.currentSceneId }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "当前场景",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(7.dp))
        Text(
            scene?.description?.takeIf { it.isNotBlank() }
                ?: location?.description?.takeIf { it.isNotBlank() }
                ?: world.summary.ifBlank { "故事正在这里展开。" },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        (scene?.currentStatus?.takeIf { it.isNotBlank() }
            ?: location?.currentStatus?.takeIf { it.isNotBlank() })?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun PlayerAction(entry: RpSceneEntry) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth(0.86f)) {
            Text(
                "你的行动",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 4.dp, bottom = 5.dp)
            )
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    entry.text,
                    Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun Narration(entry: RpSceneEntry) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "场景旁白",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(7.dp))
        Row {
            Box(
                Modifier.width(2.dp).heightIn(min = 36.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
            SelectionContainer {
                Text(
                    entry.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 13.dp),
                    fontStyle = FontStyle.Normal
                )
            }
        }
    }
}

@Composable
private fun CurrentCharacterRow(character: RpCharacter, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RpAvatar(character.avatarPath, character.name, 50, RoundedCornerShape(12.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(character.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    character.currentAction.ifBlank {
                        character.knownIdentity.ifBlank { "就在当前场景" }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("交谈", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun NearbyLocations(
    world: RpWorld,
    busy: Boolean,
    onTravel: (String) -> Unit,
    onOpenMap: () -> Unit
) {
    val current = world.locations.firstOrNull { it.id == world.currentLocationId }
    val reachable = world.locations.filter {
        it.id in current?.connectedLocationIds.orEmpty() && it.discovered && it.enterable
    }
    if (reachable.isEmpty()) return
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "附近可前往",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onOpenMap) { Text("地图") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(reachable, key = { it.id }) { location ->
                AssistChip(
                    onClick = { onTravel(location.id) },
                    enabled = !busy,
                    label = { Text(location.name) },
                    leadingIcon = { Icon(Icons.Default.LocationOn, null, Modifier.size(16.dp)) }
                )
            }
        }
    }
}

@Composable
private fun RpActionInput(
    input: String,
    onInput: (String) -> Unit,
    busy: Boolean,
    menuOpen: Boolean,
    onMenuChange: (Boolean) -> Unit,
    onSend: (Boolean) -> Unit,
    onOpenStates: () -> Unit,
    onOpenMap: () -> Unit,
    mapEnabled: Boolean,
    onOpenCharacters: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(WindowInsets.navigationBars.asPaddingValues())) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Box {
                IconButton(onClick = { onMenuChange(true) }, enabled = !busy) {
                    Icon(Icons.Default.Add, "更多操作")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuChange(false) }) {
                    DropdownMenuItem(
                        text = { Text("补全经过") },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                        enabled = input.isNotBlank(),
                        onClick = { onMenuChange(false); onSend(true) }
                    )
                    DropdownMenuItem(
                        text = { Text("查看当前状态") },
                        leadingIcon = { Icon(Icons.Default.Tune, null) },
                        onClick = { onMenuChange(false); onOpenStates() }
                    )
                    if (mapEnabled) {
                        DropdownMenuItem(
                            text = { Text("地图") },
                            leadingIcon = { Icon(Icons.Default.Map, null) },
                            onClick = { onMenuChange(false); onOpenMap() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("当前人物") },
                        leadingIcon = { Icon(Icons.Default.Groups, null) },
                        onClick = { onMenuChange(false); onOpenCharacters() }
                    )
                }
            }
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp, max = 150.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 13.dp, vertical = 11.dp)
            ) {
                if (input.isEmpty()) {
                    Text("描述你的行动，或自己写剧情……", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = input,
                    onValueChange = onInput,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(
                        if (input.isNotBlank() && !busy) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable(enabled = input.isNotBlank() && !busy) { onSend(false) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.ArrowUpward, "继续",
                    tint = if (input.isNotBlank() && !busy) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun RpStatesScreen(
    world: RpWorld,
    onBack: () -> Unit,
    onUpdateValue: (String, String, String) -> Unit
) {
    var editing by remember { mutableStateOf<Pair<RpStateDefinition, RpStateValue>?>(null) }
    val visible = world.stateDefinitions.filter { it.playerVisible }
    RpPageReveal {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            RpTopBar("RP 状态", onBack)
            LazyColumn(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (visible.isEmpty()) {
                    item { Text("这个 RP 暂时没有对你公开的状态。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                visible.forEach { definition ->
                    val targetIds = when (definition.owner) {
                        RpStateOwner.WORLD -> listOf(world.id)
                        RpStateOwner.USER -> listOf("user")
                        RpStateOwner.LOCATION -> listOfNotNull(
                            definition.ownerTargetId.takeIf { it.isNotBlank() }
                                ?: world.currentLocationId.takeIf { it.isNotBlank() }
                        )
                        RpStateOwner.SCENE -> listOfNotNull(
                            definition.ownerTargetId.takeIf { it.isNotBlank() }
                                ?: world.currentSceneId.takeIf { it.isNotBlank() }
                        )
                        RpStateOwner.CUSTOM -> listOfNotNull(
                            definition.ownerTargetId.ifBlank { definition.ownerTargetName }
                                .takeIf { it.isNotBlank() }
                        )
                        RpStateOwner.CHARACTER, RpStateOwner.CHARACTER_TEMPLATE -> emptyList()
                    }
                    targetIds.forEach { targetId ->
                        val value = world.stateValues.firstOrNull {
                            it.definitionId == definition.id && it.targetId == targetId
                        } ?: RpStateValue(definition.id, targetId, definition.defaultValue)
                        item(definition.id + targetId) {
                            Box(Modifier.clickable { editing = definition to value }) {
                                RpStateCard(definition, value)
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { (definition, value) ->
        var draft by remember(definition.id, value.value) { mutableStateOf(value.value) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RpStateIcon(definition.icon, definition.name)
                    Text(definition.name, Modifier.padding(start = 8.dp))
                }
            },
            text = {
                when (definition.type) {
                    RpStateType.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.equals("true", true) || draft == "是",
                            onCheckedChange = { draft = it.toString() }
                        )
                        Text(if (draft.equals("true", true) || draft == "是") "开启" else "关闭")
                    }
                    RpStateType.OPTION -> LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(definition.options) { option ->
                            FilterChip(
                                selected = draft == option,
                                onClick = { draft = option },
                                label = { Text(option) }
                            )
                        }
                    }
                    else -> OutlinedTextField(draft, { draft = it }, label = { Text("值") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateValue(definition.id, value.targetId, draft)
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }
}
