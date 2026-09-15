package com.miniichat.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniichat.R
import com.miniichat.data.ProviderConfig

@Composable
fun ProvidersScreen(
    providers: List<ProviderConfig>,
    fetchingId: String?,
    activeProviderId: String,
    activeModel: String,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (ProviderConfig) -> Unit,
    onDelete: (String) -> Unit,
    onFetchModels: (String) -> Unit,
    onAddManualModel: (String, String) -> Unit,
    onRemoveModel: (String, String) -> Unit,
    onSelectModel: (String, String) -> Unit
) {
    val active = providers.firstOrNull { it.id == activeProviderId } ?: providers.firstOrNull()
    var modelInput by remember(active?.id) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar("服务与模型", onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("模型服务", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "当前服务与备用模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(onClick = onCreate) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("添加")
                }
            }
            Spacer(Modifier.height(8.dp))
            active?.let { Disclosure("官网与配置帮助") { ProviderHelp(it.baseUrl) } }

            if (providers.isEmpty()) {
                Text("还没有模型服务", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            providers.sortedBy { it.priority }.forEach { provider ->
                val selected = provider.id == active?.id
                AppGroup { Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = provider.models.isNotEmpty()) {
                            onSelectModel(provider.id, provider.models.first())
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = provider.models.firstOrNull()?.let { model ->
                            { onSelectModel(provider.id, model) }
                        }
                    )
                    Column(Modifier.weight(1f)) {
                        Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            buildString {
                                append(provider.baseUrl)
                                if (provider.allowFallback) append(" · 可备用")
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onEdit(provider) }) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑 ${provider.name}")
                    }
                    if (providers.size > 1) {
                        IconButton(onClick = { onDelete(provider.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "删除 ${provider.name}")
                        }
                    }
                }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (active != null) {
                Spacer(Modifier.height(24.dp))
                Text("${active.name} 的模型", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("模型 ID") },
                        singleLine = true
                    )
                    Spacer(Modifier.padding(4.dp))
                    Button(
                        onClick = {
                            val model = modelInput.trim()
                            onAddManualModel(active.id, model)
                            onSelectModel(active.id, model)
                            modelInput = ""
                        },
                        enabled = modelInput.isNotBlank()
                    ) { Text("添加") }
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { onFetchModels(active.id) },
                    enabled = fetchingId != active.id,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (fetchingId == active.id) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(if (fetchingId == active.id) "正在获取" else "从服务获取模型")
                }
                Spacer(Modifier.height(8.dp))
                active.models.forEach { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectModel(active.id, model) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = active.id == activeProviderId && model == activeModel,
                            onClick = { onSelectModel(active.id, model) }
                        )
                        Text(
                            model,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (model in active.fallbackModels) {
                            Text("备用", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { onRemoveModel(active.id, model) }) {
                            Icon(Icons.Default.Close, contentDescription = "删除模型 $model")
                        }
                    }
                }
            }
        }
    }
}
