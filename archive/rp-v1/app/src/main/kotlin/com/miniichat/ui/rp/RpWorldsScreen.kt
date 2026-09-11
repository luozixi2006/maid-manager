package com.miniichat.ui.rp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.miniichat.data.AppSettings
import com.miniichat.data.ProviderConfig
import com.miniichat.rp.RpCatalogModel
import com.miniichat.rp.RpAiServices
import com.miniichat.rp.RpModelAnalyzer
import com.miniichat.rp.RpModelProfile
import com.miniichat.rp.RpModelSettings
import com.miniichat.rp.RpModelTask
import com.miniichat.rp.label
import com.miniichat.rp.RpWorld
import com.miniichat.rp.locationName
import com.miniichat.rp.worldTimeLabel

@Composable
fun RpWorldsScreen(
    worlds: List<RpWorld>,
    busy: Boolean,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onOpenWorld: (String) -> Unit,
    onDeleteWorld: (String) -> Unit
) {
    var deleteTarget by remember { mutableStateOf<RpWorld?>(null) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        RpTopBar("RP 项目", onBack) {
            IconButton(onClick = onCreate) { Icon(Icons.Default.Add, "创建 RP") }
        }
        if (worlds.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Public, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("还没有 RP 项目", style = MaterialTheme.typography.titleMedium)
                    Text("用一句话描述想玩的 RP，或从空白开始", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onCreate) { Text("创建新 RP") }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(worlds, key = { it.id }) { world ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.large,
                        onClick = { onOpenWorld(world.id) }
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Public, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(world.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (world.setupComplete) listOfNotNull(
                                        world.locationName().takeIf { world.modules.locationsEnabled },
                                        world.worldTimeLabel().takeIf { world.modules.timelineEnabled },
                                        when (world.projectMode) {
                                            com.miniichat.rp.RpProjectMode.SANDBOX -> "自由沙盒"
                                            com.miniichat.rp.RpProjectMode.STORY -> "重剧情"
                                            com.miniichat.rp.RpProjectMode.HYBRID -> "混合"
                                        }
                                    ).joinToString(" · ")
                                    else "尚未开始 · 可继续编辑",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { deleteTarget = world }) {
                                Icon(Icons.Default.Delete, "删除 RP")
                            }
                        }
                    }
                }
            }
        }
        if (busy) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
    }
    deleteTarget?.let { world ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除 RP") },
            text = { Text("确定删除“${world.name}”及其全部人物、状态、记忆和剧情吗？") },
            confirmButton = { TextButton(onClick = {
                onDeleteWorld(world.id); deleteTarget = null
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
fun RpCreateWorldScreen(
    busy: Boolean,
    phase: String?,
    initialName: String = "",
    initialIdea: String = "",
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onGenerate: (String, String) -> Unit,
    onBlank: (String, String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var idea by remember(initialIdea) { mutableStateOf(initialIdea) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        RpTopBar("创建 RP", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text("只需要描述你想玩的 RP", style = MaterialTheme.typography.titleMedium)
            Text(
                "系统会根据描述选择合适的项目结构，生成后仍可修改。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("RP 名称（可选）") }, singleLine = true
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = idea, onValueChange = { idea = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("描述你想玩的 RP") },
                placeholder = { Text("例如：现代校园恋爱故事，围绕学校、社团和主要人物发展。") },
                minLines = 7, maxLines = 14
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onGenerate(name, idea) },
                enabled = idea.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp))
                else Icon(Icons.Default.AutoAwesome, null)
                Text("  生成")
            }
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                onClick = { onBlank(name, idea) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("从空白开始") }
            if (busy) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Text(
                            phase ?: "正在创建 RP……",
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.titleSmall
                        )
                        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) {
                            Text("取消")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RpAiModelScreen(
    settings: RpModelSettings,
    providers: List<ProviderConfig>,
    busy: Boolean,
    onBack: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onSaveProvider: (String, String, String, String) -> Unit,
    onTest: (String, String, String, String) -> Unit,
    onRefresh: (String, String, String, String) -> Unit,
    onAddManualModel: (String) -> Unit,
    onProfile: (RpModelProfile) -> Unit,
    onApplyPending: () -> Unit,
    onKeepCurrent: () -> Unit,
    onCustom: () -> Unit
) {
    val selectedId = settings.activeProviderId
    val definition = RpAiServices.definition(selectedId)
    val provider = providers.firstOrNull { it.id == selectedId }
    var apiKey by remember(selectedId, provider?.apiKey) { mutableStateOf(provider?.apiKey.orEmpty()) }
    var serviceName by remember(selectedId, provider?.name) {
        mutableStateOf(provider?.name ?: definition.name)
    }
    var baseUrl by remember(selectedId, provider?.baseUrl) {
        mutableStateOf(provider?.baseUrl ?: definition.defaultBaseUrl)
    }
    var manualModelId by remember(selectedId) { mutableStateOf("") }
    val effectiveName = if (definition.custom) serviceName else definition.name
    val effectiveBaseUrl = if (definition.custom) baseUrl else definition.defaultBaseUrl
    val canConnect = !busy && effectiveBaseUrl.isNotBlank() &&
        (definition.custom || apiKey.isNotBlank())
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        RpTopBar("AI 设置", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("AI 服务", style = MaterialTheme.typography.titleMedium)
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(RpAiServices.all, key = { it.id }) { service ->
                    FilterChip(
                        selected = selectedId == service.id,
                        onClick = { onSelectProvider(service.id) },
                        label = { Text(service.name) }
                    )
                }
            }
            Text(effectiveName, style = MaterialTheme.typography.titleLarge)
            Text(
                "模型与模型 ID 以服务当前 API 实际返回为准。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            if (definition.custom) {
                OutlinedTextField(
                    value = serviceName,
                    onValueChange = { serviceName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("服务名称") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL") },
                    placeholder = { Text("例如：https://example.com/v1") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("API 密钥") }, visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            Text("密钥只保存在本机", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (selectedId) {
                RpAiServices.SILICONFLOW -> TextButton(
                    onClick = { uriHandler.openUri("https://cloud.siliconflow.cn/account/ak") }
                ) { Text("获取中国大陆站 API 密钥") }
                RpAiServices.SILICONFLOW_INTERNATIONAL -> TextButton(
                    onClick = { uriHandler.openUri("https://cloud.siliconflow.com/account/ak") }
                ) { Text("获取国际站 API 密钥") }
                RpAiServices.OPENROUTER -> TextButton(
                    onClick = { uriHandler.openUri("https://openrouter.ai/settings/keys") }
                ) { Text("获取 OpenRouter API 密钥") }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = { onTest(selectedId, effectiveName, effectiveBaseUrl, apiKey) },
                    enabled = canConnect,
                    modifier = Modifier.weight(1f)
                ) { Text("测试连接") }
                Button(
                    onClick = { onSaveProvider(selectedId, effectiveName, effectiveBaseUrl, apiKey) },
                    enabled = effectiveBaseUrl.isNotBlank(), modifier = Modifier.weight(1f)
                ) { Text("保存") }
            }
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                onClick = { onRefresh(selectedId, effectiveName, effectiveBaseUrl, apiKey) },
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.AutoAwesome, null)
                Text(if (busy) "  正在分析模型能力……" else "  检测可用模型")
            }
            if (definition.custom) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualModelId,
                        onValueChange = { manualModelId = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("手动模型 ID") },
                        singleLine = true
                    )
                    TextButton(
                        onClick = {
                            onAddManualModel(manualModelId)
                            manualModelId = ""
                        },
                        enabled = manualModelId.isNotBlank()
                    ) { Text("添加") }
                }
                Text(
                    "如果服务不支持 /models，可保存配置后手动添加模型 ID。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (settings.catalog.isNotEmpty()) {
                Text(
                    "已检测到 ${settings.catalog.size} 个文本模型 · ${relativeUpdateTime(settings.catalogUpdatedAt)}",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (settings.pendingRecommendations.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("发现新的推荐配置", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "模型列表已更新。当前手动选择不会被自动覆盖。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        RpModelTask.entries.forEach { task ->
                            val oldId = settings.tasks[task]?.defaultModel.orEmpty()
                            val newId = settings.pendingRecommendations[
                                settings.profile.takeUnless { it == RpModelProfile.CUSTOM }
                                    ?: RpModelProfile.BALANCED
                            ]?.get(task)?.defaultModel.orEmpty()
                            if (newId.isNotBlank() && oldId != newId) {
                                Text(
                                    "${task.label()}：${modelName(settings.catalog, oldId)} → ${modelName(settings.catalog, newId)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 5.dp)
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onKeepCurrent) { Text("保持当前配置") }
                            TextButton(onClick = onApplyPending) { Text("应用新配置") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("选择策略", style = MaterialTheme.typography.titleMedium)
            Text("策略会从已检测模型中动态评分，不对应任何固定模型。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            listOf(
                RpModelProfile.SAVER,
                RpModelProfile.BALANCED,
                RpModelProfile.HIGH_QUALITY
            ).forEach { profile ->
                Surface(
                    onClick = { onProfile(profile) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    color = if (settings.profile == profile) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(settings.profile == profile, onClick = { onProfile(profile) })
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(profile.label(), style = MaterialTheme.typography.titleMedium)
                            Text(
                                RpModelAnalyzer.profileReason(profile),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            RpModelAnalyzer.differentiationNotice(
                settings.catalog, settings.recommendations
            )?.let { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            if (settings.tasks.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("当前自动配置", style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        RpModelTask.entries.forEach { task ->
                            val id = settings.tasks[task]?.defaultModel.orEmpty()
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(task.label(), Modifier.weight(0.42f), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    modelName(settings.catalog, id),
                                    Modifier.weight(0.58f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (settings.catalog.any { it.id == id })
                                        MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onCustom, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text("高级设置与全部模型")
            }
        }
    }
}

@Composable
fun RpModelCustomScreen(
    settings: RpModelSettings,
    busy: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onUpdateMyModels: (List<String>) -> Unit,
    onUpdateTask: (RpModelTask, List<String>, String) -> Unit
) {
    var editingTask by remember { mutableStateOf<RpModelTask?>(null) }
    var managingModels by remember { mutableStateOf(false) }
    val expensiveHighFrequency = listOf(RpModelTask.CHARACTER, RpModelTask.NARRATION, RpModelTask.WORLD)
        .any { task ->
            val id = settings.tasks[task]?.defaultModel.orEmpty()
            settings.catalog.firstOrNull { it.id == id }?.priceLabel() == "较贵"
        }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        RpTopBar("自定义模型", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item {
                Text("每项可以保留多个候选，并指定一个默认模型。系统不会随机切换。")
                if (expensiveHighFrequency) {
                    Text(
                        "当前配置成本较高，长期 RP 可能产生更多费用。",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
            item {
                RpListEntry(
                    title = "全部模型",
                    subtitle = if (settings.myModels.isEmpty()) "尚未选择模型"
                    else "已选择 ${settings.myModels.size} 个模型 · 可搜索和筛选平台目录",
                    onClick = { managingModels = true }
                )
            }
            items(RpModelTask.entries, key = { it.name }) { task ->
                val choice = settings.tasks[task]
                val model = settings.catalog.firstOrNull { it.id == choice?.defaultModel }
                RpListEntry(
                    title = task.label(),
                    subtitle = model?.name ?: "当前模型暂不可用，请选择替代模型",
                    onClick = { editingTask = task }
                )
            }
            item {
                FilledTonalButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("重新检测并优化")
                }
            }
        }
    }
    editingTask?.let { task ->
        RpModelPickerDialog(
            task = task,
            catalog = settings.catalog,
            myModels = settings.myModels,
            recommendedIds = settings.recommendations[
                settings.profile.takeUnless { it == RpModelProfile.CUSTOM } ?: RpModelProfile.BALANCED
            ]
                ?.get(task)?.candidates.orEmpty(),
            initialCandidates = settings.tasks[task]?.candidates.orEmpty(),
            initialDefault = settings.tasks[task]?.defaultModel.orEmpty(),
            onDismiss = { editingTask = null },
            onSave = { candidates, default ->
                onUpdateTask(task, candidates, default)
                editingTask = null
            }
        )
    }
    if (managingModels) {
        RpMyModelsDialog(
            catalog = settings.catalog,
            initial = settings.myModels,
            onDismiss = { managingModels = false },
            onSave = {
                onUpdateMyModels(it)
                managingModels = false
            }
        )
    }
}

@Composable
private fun RpModelPickerDialog(
    task: RpModelTask,
    catalog: List<RpCatalogModel>,
    myModels: List<String>,
    recommendedIds: List<String>,
    initialCandidates: List<String>,
    initialDefault: String,
    onDismiss: () -> Unit,
    onSave: (List<String>, String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(initialCandidates.toSet()) }
    var defaultModel by remember { mutableStateOf(initialDefault) }
    val shown = catalog.filter { model ->
        (showAll || model.id in myModels || model.id in recommendedIds) &&
            (query.isBlank() || model.id.contains(query, true) || model.name.contains(query, true) ||
                model.provider.contains(query, true) || model.description.contains(query, true))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${task.label()}模型") },
        text = {
            Column(Modifier.heightIn(max = 520.dp)) {
                OutlinedTextField(
                    query, { query = it }, label = { Text("搜索模型") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = !showAll,
                        onClick = { showAll = false },
                        label = { Text("推荐与已选") }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = showAll,
                        onClick = { showAll = true },
                        label = { Text("查看全部模型") }
                    )
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(shown, key = { it.id }) { model ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = model.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + model.id else selected - model.id
                                    if (!checked && defaultModel == model.id) defaultModel = ""
                                }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(model.name)
                                Text(
                                    "${modelUseHint(model, task)} · ${model.contextLabel()} · ${model.priceLabel()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                            RadioButton(
                                selected = defaultModel == model.id,
                                onClick = {
                                    selected = selected + model.id
                                    defaultModel = model.id
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(selected.toList(), defaultModel) },
                enabled = selected.isNotEmpty() && defaultModel.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun RpMyModelsDialog(
    catalog: List<RpCatalogModel>,
    initial: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initial.toSet()) }
    var provider by remember { mutableStateOf<String?>(null) }
    var providerMenu by remember { mutableStateOf(false) }
    var price by remember { mutableStateOf("全部价格") }
    var priceMenu by remember { mutableStateOf(false) }
    var contextMinimum by remember { mutableStateOf(0L) }
    var contextMenu by remember { mutableStateOf(false) }
    val providers = catalog.map { it.provider.ifBlank { "未知 Provider" } }.distinct().sorted()
    val shown = catalog.filter { model ->
        (query.isBlank() || model.id.contains(query, true) || model.name.contains(query, true) ||
            model.provider.contains(query, true) || model.description.contains(query, true)) &&
            (provider == null || model.provider.ifBlank { "未知 Provider" } == provider) &&
            (price == "全部价格" || model.priceLabel() == price) &&
            (contextMinimum == 0L || (model.contextLength ?: 0L) >= contextMinimum)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("全部模型") },
        text = {
            Column(Modifier.heightIn(max = 520.dp)) {
                Text("仅显示平台 API 当前返回的文本模型；选择后可用于模型分工。")
                OutlinedTextField(
                    query, { query = it }, label = { Text("搜索全部模型") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    item {
                        Box {
                            FilterChip(
                                selected = provider != null,
                                onClick = { providerMenu = true },
                                label = { Text(provider ?: "Provider") }
                            )
                            DropdownMenu(providerMenu, { providerMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("全部 Provider") },
                                    onClick = { provider = null; providerMenu = false }
                                )
                                providers.forEach { value ->
                                    DropdownMenuItem(
                                        text = { Text(value) },
                                        onClick = { provider = value; providerMenu = false }
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Box {
                            FilterChip(
                                selected = price != "全部价格",
                                onClick = { priceMenu = true },
                                label = { Text(price) }
                            )
                            DropdownMenu(priceMenu, { priceMenu = false }) {
                                listOf("全部价格", "很便宜", "便宜", "中等", "较贵", "价格未知").forEach { value ->
                                    DropdownMenuItem(
                                        text = { Text(value) },
                                        onClick = { price = value; priceMenu = false }
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Box {
                            val contextLabel = when (contextMinimum) {
                                32_000L -> "≥32K"
                                128_000L -> "≥128K"
                                else -> "上下文"
                            }
                            FilterChip(
                                selected = contextMinimum > 0,
                                onClick = { contextMenu = true },
                                label = { Text(contextLabel) }
                            )
                            DropdownMenu(contextMenu, { contextMenu = false }) {
                                listOf(0L to "全部上下文", 32_000L to "至少 32K", 128_000L to "至少 128K")
                                    .forEach { (value, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = { contextMinimum = value; contextMenu = false }
                                        )
                                    }
                            }
                        }
                    }
                }
                Text(
                    "显示 ${shown.size} / ${catalog.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(shown, key = { it.id }) { model ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = model.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + model.id else selected - model.id
                                }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(model.name)
                                Text(
                                    "${modelUseHint(model)} · ${model.contextLabel()} · ${model.priceLabel()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "${model.provider.ifBlank { "Provider 未提供" }} · ${model.priceDetail()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected.toList()) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun modelUseHint(model: RpCatalogModel, task: RpModelTask? = null): String =
    RpModelAnalyzer.labels(model, task).takeIf { it.isNotEmpty() }?.joinToString(" · ")
        ?: model.description.lineSequence().firstOrNull()?.take(80)
            ?.takeIf { it.isNotBlank() } ?: "能力未知"

private fun modelName(catalog: List<RpCatalogModel>, id: String): String = when {
    id.isBlank() -> "未配置"
    else -> catalog.firstOrNull { it.id == id }?.name ?: "已不可用"
}

private fun relativeUpdateTime(timestamp: Long): String {
    if (timestamp <= 0L) return "尚未更新"
    val elapsed = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    val minutes = elapsed / 60_000L
    return when {
        minutes < 1 -> "刚刚更新"
        minutes < 60 -> "${minutes} 分钟前更新"
        minutes < 24 * 60 -> "${minutes / 60} 小时前更新"
        else -> "${minutes / (24 * 60)} 天前更新"
    }
}
