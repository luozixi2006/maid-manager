package com.miniichat.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.miniichat.R
import com.miniichat.api.OpenAiEndpointResolver
import com.miniichat.data.ProviderAuthMode
import com.miniichat.data.ProviderConfig
import com.miniichat.util.BaseUrlNormalizer
import com.miniichat.util.newId

@Composable
fun ProviderEditorScreen(
    initial: ProviderConfig?,
    onCancel: () -> Unit,
    onSave: (ProviderConfig) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "自定义服务") }
    var baseUrl by remember(initial?.id) {
        mutableStateOf(initial?.baseUrl ?: "https://api.deepseek.com")
    }
    var apiKey by remember(initial?.id) { mutableStateOf(initial?.apiKey ?: "") }
    var requiresKey by remember(initial?.id) {
        mutableStateOf(initial?.authMode != ProviderAuthMode.NONE)
    }
    var modelId by remember(initial?.id) {
        mutableStateOf(initial?.models?.firstOrNull() ?: "deepseek-v4-flash")
    }
    var fallbackModels by remember(initial?.id) {
        mutableStateOf(initial?.fallbackModels?.joinToString(", ").orEmpty())
    }
    var allowFallback by remember(initial?.id) {
        mutableStateOf(initial?.allowFallback ?: true)
    }
    var validation by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(if (initial == null) "添加模型服务" else "编辑模型服务", onCancel)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Disclosure("服务官网与配置帮助") { ProviderHelp(baseUrl) }
            Spacer(Modifier.height(12.dp))
            AppTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("服务名称") },
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            AppTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; validation = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setting_base_url)) },
                placeholder = { Text("https://api.deepseek.com") },
                singleLine = true,
                isError = validation != null,
                supportingText = validation?.let { message -> ({ Text(message) }) }
            )
            Spacer(Modifier.height(8.dp))
            SettingSwitchRow(
                title = "密钥验证",
                summary = "本地免验证服务可关闭",
                checked = requiresKey,
                onCheckedChange = { requiresKey = it }
            )
            if (requiresKey) {
                Spacer(Modifier.height(8.dp))
                AppTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.setting_api_key)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            }
            Spacer(Modifier.height(12.dp))
            AppTextField(
                value = modelId,
                onValueChange = { modelId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("默认模型") },
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            AppTextField(
                value = fallbackModels,
                onValueChange = { fallbackModels = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("备用模型（可选）") },
                supportingText = { Text("多个模型用逗号分隔；按顺序自动尝试") }
            )
            Spacer(Modifier.height(4.dp))
            SettingSwitchRow(
                title = "允许作为备用服务",
                summary = "主服务不可用且尚未输出内容时自动切换",
                checked = allowFallback,
                onCheckedChange = { allowFallback = it }
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    val normalizedUrl = BaseUrlNormalizer.normalize(baseUrl)
                    val valid = runCatching { OpenAiEndpointResolver.resolve(normalizedUrl) }.exceptionOrNull()
                    if (valid != null) {
                        validation = valid.message ?: "Base URL 格式错误"
                        return@Button
                    }
                    val primaryModel = modelId.trim()
                    val fallback = fallbackModels.split(',', '，', '\n')
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .distinct()
                    val original = initial
                    onSave(
                        (original ?: ProviderConfig(
                            id = newId(),
                            name = name.trim(),
                            baseUrl = normalizedUrl,
                            apiKey = apiKey.trim()
                        )).copy(
                            name = name.trim(),
                            baseUrl = normalizedUrl,
                            apiKey = apiKey.trim(),
                            authMode = if (requiresKey) ProviderAuthMode.BEARER else ProviderAuthMode.NONE,
                            allowFallback = allowFallback,
                            fallbackModels = fallback,
                            models = (listOf(primaryModel) + fallback + original?.models.orEmpty())
                                .filter(String::isNotBlank)
                                .distinct()
                        )
                    )
                },
                enabled = name.isNotBlank() && baseUrl.isNotBlank() && modelId.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
