package com.miniichat.ui.rp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniichat.data.ProviderConfig
import com.miniichat.rp.RpModelSettings
import com.miniichat.rp.RpWorld
import com.miniichat.rp.locationName
import com.miniichat.rp.worldTimeLabel
import kotlin.math.roundToInt

@Composable
fun RpWorldSettingsScreen(
    world: RpWorld,
    onBack: () -> Unit,
    onEditWorld: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenStates: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPortraits: () -> Unit,
    onOpenData: () -> Unit
) {
    RpPageReveal {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            RpTopBar("RP 设置", onBack)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 18.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item {
                    SettingsGroup("剧本") {
                        SettingRow(Icons.Default.Public, "RP 项目设定", world.summary.ifBlank { "背景、规则、模块与视觉风格" }, onEditWorld)
                        if (world.modules.locationsEnabled) {
                            SettingDivider()
                            SettingRow(Icons.Default.Map, "地图与地点", "${world.locations.size} 个地点 · 当前 ${world.locationName()}", onOpenMap)
                        }
                        SettingDivider()
                        SettingRow(
                            Icons.AutoMirrored.Filled.ViewList,
                            "状态与剧情",
                            "${world.stateDefinitions.size} 个状态 · ${world.storyArcs.size} 个剧情阶段",
                            onOpenStates
                        )
                    }
                }
                item {
                    SettingsGroup("人物") {
                        SettingRow(Icons.Default.Groups, "人物管理", "${world.characters.size} 人 · ${world.characters.count { it.hasAppeared }} 人已登场", onOpenCharacters)
                        SettingDivider()
                        SettingRow(Icons.Default.Image, "人物立绘", "生成入口、提示词与本地保存", onOpenPortraits)
                    }
                }
                item {
                    SettingsGroup("其他") {
                        SettingRow(Icons.Default.Storage, "本地数据", "查看数据规模或删除这个 RP", onOpenData)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
        ) { Column { content() } }
    }
}

@Composable
private fun SettingDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 54.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        thickness = 0.5.dp
    )
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RpMapScreen(
    world: RpWorld,
    busy: Boolean,
    onBack: () -> Unit,
    onTravel: (String) -> Unit,
    onManage: () -> Unit
) {
    val current = world.locations.firstOrNull { it.id == world.currentLocationId }
    val shown = world.locations.filter { it.discovered }
    val hiddenCount = world.locations.count { !it.discovered }
    RpPageReveal(scale = true) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            RpTopBar("地图", onBack) {
                TextButton(onClick = onManage) { Text("编辑") }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 18.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(world.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOfNotNull(
                            world.worldTimeLabel().takeIf { world.modules.timelineEnabled },
                            world.weather.takeIf { world.modules.timelineEnabled && it.isNotBlank() }
                        ).joinToString(" · ").ifBlank { "当前 RP 未启用时间线" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "地点关系会随剧情保存；已经生成的地点再次抵达时不会重新随机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (shown.isEmpty()) {
                    item { Text("还没有公开地点，可以从 RP 编辑页创建。") }
                }
                items(shown, key = { it.id }) { location ->
                    val isCurrent = location.id == current?.id
                    val connected = location.connectedLocationIds.mapNotNull { id ->
                        world.locations.firstOrNull { it.id == id }
                    }.map { if (it.discovered) it.name else "？？？" }
                    RpRevealItem(location.id) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
                        ) {
                            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (isCurrent) Icons.Default.LocationOn else Icons.Default.Route,
                                        null,
                                        tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        location.name,
                                        modifier = Modifier.weight(1f).padding(start = 9.dp),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    if (isCurrent) Text("当前", color = MaterialTheme.colorScheme.primary)
                                }
                                location.description.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium)
                                }
                                location.currentStatus.takeIf { it.isNotBlank() }?.let {
                                    Text("状态：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (connected.isNotEmpty()) {
                                    Text(
                                        "连接：${connected.joinToString(" · ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (!isCurrent && location.enterable && location.id in current?.connectedLocationIds.orEmpty()) {
                                    TextButton(
                                        onClick = { onTravel(location.id) },
                                        enabled = !busy,
                                        modifier = Modifier.align(Alignment.End)
                                    ) { Text("前往") }
                                }
                            }
                        }
                    }
                }
                if (hiddenCount > 0) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("还有 $hiddenCount 个未发现地点", Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RpModelParametersScreen(
    settings: RpModelSettings,
    onBack: () -> Unit,
    onRecommendedChanged: (Boolean) -> Unit,
    onTemperatureChanged: (Float) -> Unit,
    onCreationTimeoutChanged: (Int) -> Unit,
    onCreationMaxOutputChanged: (Int) -> Unit
) {
    RpPageReveal {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            RpTopBar("生成参数", onBack)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("使用推荐参数", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "沿用当前 AI 设置中已经验证过的参数，适合大多数世界。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(settings.useRecommendedParameters, onRecommendedChanged)
                        }
                    }
                }
                if (!settings.useRecommendedParameters) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("高级参数", style = MaterialTheme.typography.titleMedium)
                            Text("创作自由度 ${"%.1f".format(settings.temperature)}")
                            Slider(
                                value = settings.temperature,
                                onValueChange = onTemperatureChanged,
                                valueRange = 0f..2f,
                                steps = 19
                            )
                            Text(
                                "数值低时剧情更稳定、克制；数值高时变化更多，也更容易偏离既有设定。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("RP 创建请求", style = MaterialTheme.typography.titleMedium)
                        Text("创建超时 ${settings.creationTimeoutSeconds} 秒")
                        Slider(
                            value = settings.creationTimeoutSeconds.toFloat(),
                            onValueChange = {
                                onCreationTimeoutChanged((it / 30f).roundToInt() * 30)
                            },
                            valueRange = 120f..600f,
                            steps = 15
                        )
                        Text("最大输出约 ${settings.creationMaxOutputTokens} tokens")
                        Slider(
                            value = settings.creationMaxOutputTokens.toFloat(),
                            onValueChange = {
                                onCreationMaxOutputChanged((it / 1000f).roundToInt() * 1000)
                            },
                            valueRange = 2_000f..16_000f,
                            steps = 13
                        )
                        Text(
                            "RP 创建使用独立的大型请求配置；普通聊天和人物对话不受这里影响。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                }
            }
        }
    }
}

@Composable
fun RpPortraitSettingsScreen(
    world: RpWorld,
    settings: RpModelSettings,
    providers: List<ProviderConfig>,
    onBack: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var selectedProviderId by remember(settings.imageGeneration.providerId) {
        mutableStateOf(settings.imageGeneration.providerId)
    }
    var imageModelId by remember(settings.imageGeneration.modelId) {
        mutableStateOf(settings.imageGeneration.modelId)
    }
    var visualStyle by remember(world.id, world.visualStyle) { mutableStateOf(world.visualStyle) }
    val selectableProviders = providers.filter { it.baseUrl.isNotBlank() }.distinctBy { it.id }
    val providerConfigured = selectedProviderId.isNotBlank() && imageModelId.isNotBlank()
    RpPageReveal {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            RpTopBar("人物立绘", onBack)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("人物首次出场时生成", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (providerConfigured) "生成后保存在该 RP 的本地目录，并在后续剧情中复用。"
                                else "当前没有配置图片生成服务；人物会先使用稳定的文字头像。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(world.portraitGenerationEnabled, onEnabledChanged)
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("生图服务", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "复用已保存服务的 Base URL、API 密钥和请求头；不会绑定固定厂商。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(
                                    selected = selectedProviderId.isBlank(),
                                    onClick = { selectedProviderId = "" },
                                    label = { Text("未配置") }
                                )
                            }
                            items(selectableProviders, key = { it.id }) { provider ->
                                FilterChip(
                                    selected = selectedProviderId == provider.id,
                                    onClick = { selectedProviderId = provider.id },
                                    label = { Text(provider.name) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = imageModelId,
                            onValueChange = { imageModelId = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("生图模型 ID") },
                            placeholder = { Text("填写所选服务实际支持的模型 ID") },
                            singleLine = true,
                            enabled = selectedProviderId.isNotBlank()
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = visualStyle,
                        onValueChange = { visualStyle = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("RP 视觉风格") },
                        minLines = 3
                    )
                }
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("生成规则", fontWeight = FontWeight.Medium)
                            Text("系统会结合 RP 视觉风格、种族、年龄、发型、外貌、穿着和身份构建立绘提示词。")
                            Text("生成失败或服务未配置时不会阻断剧情，可在人物菜单中重新尝试。")
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            onSave(selectedProviderId, imageModelId.trim(), visualStyle.trim())
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("保存人物头像设置") }
                }
            }
        }
    }
}

@Composable
fun RpDataManagementScreen(
    world: RpWorld,
    onBack: () -> Unit,
    onExportHandoff: () -> Unit,
    onProactiveChanged: (Boolean) -> Unit,
    onDeleteWorld: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    RpPageReveal {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            RpTopBar("本地数据", onBack)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text("${world.name} 的全部 RP 数据只保存在本机。", style = MaterialTheme.typography.bodyLarge)
                }
                item { DataFact("地点", world.locations.size.toString()) }
                item { DataFact("人物", world.characters.size.toString()) }
                item { DataFact("场景记录", world.sceneHistory.size.toString()) }
                item { DataFact("RP 事件", world.recentEvents.size.toString()) }
                item { DataFact("规则修正", world.ruleCorrections.size.toString()) }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("允许 RP 人物主动联系")
                            Text(
                                "人物仍会遵守世界时间、通信条件和世界规则。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = world.proactiveMessagesEnabled,
                            onCheckedChange = onProactiveChanged
                        )
                    }
                }
                item {
                    OutlinedButton(onClick = onExportHandoff, modifier = Modifier.fillMaxWidth()) {
                        Text("导出 RP 交接包")
                    }
                }
                item {
                    Button(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.DeleteOutline, null)
                        Text("删除这个 RP", Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${world.name}？") },
            text = { Text("RP 项目、人物、可选地图、状态和剧情记录都会从本机删除，此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteWorld() }) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun DataFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
