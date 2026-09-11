package com.miniichat.ui.rp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PeopleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miniichat.rp.RpCharacter
import com.miniichat.rp.RpStateOwner
import com.miniichat.rp.RpStateValue
import com.miniichat.rp.RpWorld
import com.miniichat.rp.locationName

@Composable
fun RpCharacterScreen(
    world: RpWorld,
    character: RpCharacter,
    busy: Boolean,
    onBack: () -> Unit,
    onStartDialogue: () -> Unit,
    onUpdateCharacter: (RpCharacter) -> Unit,
    onDelete: () -> Unit,
    onRegeneratePortrait: () -> Unit
) {
    val canTalk = character.id in world.presentCharacterIds
    val location = world.locations.firstOrNull { it.id == character.currentLocationId }
    val visibleStates = world.stateDefinitions.filter {
        it.playerVisible && when (it.owner) {
            RpStateOwner.CHARACTER_TEMPLATE -> true
            RpStateOwner.CHARACTER -> it.ownerTargetId.isBlank() || it.ownerTargetId == character.id
            else -> false
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var memoriesOpen by remember { mutableStateOf(false) }
    var relationshipsOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }

    RpPageReveal {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            RpTopBar(character.name, onBack) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "人物菜单")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        CharacterMenuItem(Icons.Default.Edit, "编辑人物") {
                            menuOpen = false; editOpen = true
                        }
                        CharacterMenuItem(Icons.Default.History, "查看记忆") {
                            menuOpen = false; memoriesOpen = true
                        }
                        CharacterMenuItem(Icons.Default.PeopleOutline, "查看关系") {
                            menuOpen = false; relationshipsOpen = true
                        }
                        CharacterMenuItem(Icons.Default.Refresh, "重新生成立绘") {
                            menuOpen = false; onRegeneratePortrait()
                        }
                        CharacterMenuItem(Icons.Default.DeleteOutline, "删除人物") {
                            menuOpen = false; deleteOpen = true
                        }
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 24.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        RpAvatar(
                            character.avatarPath,
                            character.name,
                            size = 112,
                            shape = RoundedCornerShape(28.dp)
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(character.name, style = MaterialTheme.typography.headlineSmall)
                        character.knownIdentity.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                character.knownDescription.takeIf { it.isNotBlank() }?.let { description ->
                    item {
                        Text(
                            description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("当前状态", style = MaterialTheme.typography.titleMedium)
                        CharacterFact("位置", world.locationName(character.currentLocationId))
                        character.mood.takeIf { it.isNotBlank() }?.let { CharacterFact("心情", it) }
                        character.relationToUser.takeIf { it.isNotBlank() }?.let {
                            CharacterFact("与你的关系", it)
                        }
                        character.currentAction.takeIf { it.isNotBlank() }?.let {
                            CharacterFact("正在做", it)
                        }
                    }
                }
                if (visibleStates.isNotEmpty()) {
                    item { Text("可见状态", style = MaterialTheme.typography.titleMedium) }
                    items(visibleStates, key = { it.id }) { definition ->
                        val value = world.stateValues.firstOrNull {
                            it.definitionId == definition.id && it.targetId == character.id
                        } ?: RpStateValue(definition.id, character.id, definition.defaultValue)
                        RpRevealItem(definition.id) { RpStateCard(definition, value) }
                    }
                }
                if (character.conversationSummaries.isNotEmpty() || character.memories.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("你知道的事情", style = MaterialTheme.typography.titleMedium)
                            (character.memories.map { it.summary } + character.conversationSummaries)
                                .takeLast(3).forEach { summary ->
                                    Text(
                                        "· $summary",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("允许主动联系")
                            Text(
                                "关闭后，这个人物不会主动发起联系。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = character.proactiveEnabled,
                            onCheckedChange = {
                                onUpdateCharacter(character.copy(proactiveEnabled = it))
                            }
                        )
                    }
                }
                item {
                    if (canTalk) {
                        Button(
                            onClick = onStartDialogue,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ChatBubbleOutline, null)
                            Spacer(Modifier.width(8.dp))
                            Text("与 ${character.name} 交谈")
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                buildString {
                                    append("${character.name} 目前不在这里。")
                                    if (location?.discovered == true) append("可能正在 ${location.name} 活动。")
                                },
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (editOpen) {
        CharacterEditorDialog(
            initial = character,
            locations = world.locations,
            onDismiss = { editOpen = false },
            onSave = { onUpdateCharacter(it); editOpen = false }
        )
    }
    if (memoriesOpen) {
        InfoListDialog(
            title = "${character.name} 的记忆",
            values = character.memories.map { it.summary } + character.conversationSummaries,
            emptyText = "还没有保存的人物记忆。",
            onDismiss = { memoriesOpen = false }
        )
    }
    if (relationshipsOpen) {
        InfoListDialog(
            title = "人物关系",
            values = listOfNotNull(
                character.relationToUser.takeIf { it.isNotBlank() }?.let { "与你：$it" }
            ) + character.relationships.map { (name, value) -> "$name：$value" },
            emptyText = "还没有公开的人物关系。",
            onDismiss = { relationshipsOpen = false }
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("删除人物？") },
            text = { Text("是否同时删除 ${character.name} 的人物资料、可见聊天记录和本地记忆？此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = { deleteOpen = false; onDelete() }) { Text("同时删除") }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun CharacterMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        leadingIcon = { Icon(icon, null) }
    )
}

@Composable
private fun CharacterFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.width(88.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoListDialog(
    title: String,
    values: List<String>,
    emptyText: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (values.isEmpty()) item { Text(emptyText) }
                else items(values) { Text("· $it") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
fun RpDialogueScreen(
    world: RpWorld,
    character: RpCharacter,
    busy: Boolean,
    onBack: () -> Unit,
    onOpenCharacter: () -> Unit,
    onSend: (String) -> Unit,
    onEndDialogue: () -> Unit
) {
    val dialogue = world.activeDialogue
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    LaunchedEffect(dialogue?.messages?.size, busy) {
        val count = dialogue?.messages?.size.orZero()
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    RpPageReveal {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()
        ) {
            RpTopBar(character.name, onBack) {
                TextButton(onClick = onEndDialogue, enabled = !busy) {
                    Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("结束")
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenCharacter)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RpAvatar(character.avatarPath, character.name, 46, RoundedCornerShape(14.dp))
                Column(Modifier.padding(start = 11.dp)) {
                    Text(character.name, fontWeight = FontWeight.Medium)
                    Text(
                        character.currentAction.ifBlank { "点击查看人物资料" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(dialogue?.messages.orEmpty(), key = { it.id }) { message ->
                    val fromUser = message.role == "user"
                    RpRevealItem(message.id) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            if (!fromUser) {
                                Box(Modifier.clickable(onClick = onOpenCharacter)) {
                                    RpAvatar(character.avatarPath, character.name, 34)
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            Surface(
                                modifier = Modifier.fillMaxWidth(0.82f),
                                color = if (fromUser) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(
                                    topStart = 17.dp, topEnd = 17.dp,
                                    bottomStart = if (fromUser) 17.dp else 5.dp,
                                    bottomEnd = if (fromUser) 5.dp else 17.dp
                                )
                            ) {
                                Text(message.text, Modifier.padding(horizontal = 14.dp, vertical = 11.dp))
                            }
                        }
                    }
                }
                if (busy) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(WindowInsets.navigationBars.asPaddingValues())) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Box(
                        Modifier.weight(1f).heightIn(min = 44.dp, max = 140.dp)
                            .clip(RoundedCornerShape(21.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 13.dp, vertical = 11.dp)
                    ) {
                        if (input.isEmpty()) {
                            Text("对 ${character.name} 说……", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
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
                            .clickable(enabled = input.isNotBlank() && !busy) {
                                val value = input.trim()
                                input = ""
                                onSend(value)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            "发送",
                            tint = if (input.isNotBlank() && !busy) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun Int?.orZero(): Int = this ?: 0
