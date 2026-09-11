package com.miniichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.miniichat.data.AppSettings
import com.miniichat.data.SearchSourceStore
import com.miniichat.search.SearchConfig
import com.miniichat.search.SearchSourceConfig
import com.miniichat.search.SearchSourceType
import kotlinx.coroutines.launch

@Composable
fun SearchSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onSave: (AppSettings) -> Unit
) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { SearchSourceStore(context) }
    val storedSources by store.sourcesFlow.collectAsState(initial = SearchSourceStore.defaultSources())
    var sources by remember { mutableStateOf(SearchSourceStore.defaultSources()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(settings.searchBaseUrl, settings.searchApiKey, settings.searchEngine) {
        store.migrateLegacy(
            SearchConfig(
                provider = settings.searchProvider,
                baseUrl = settings.searchBaseUrl,
                apiKey = settings.searchApiKey,
                engine = settings.searchEngine
            )
        )
    }
    LaunchedEffect(storedSources) { sources = storedSources }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar("联网搜索", onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "自动多源搜索",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "每次联网时会同时查询所有已启用且配置完整的搜索源，自动合并并去除重复结果，无需手动选择。单个来源失败不会中断其他来源。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            sources.forEachIndexed { index, source ->
                SearchSourceEditor(
                    source = source,
                    onChange = { updated ->
                        sources = sources.toMutableList().also { it[index] = updated }
                    }
                )
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    scope.launch {
                        store.save(sources)
                        val custom = sources.firstOrNull { it.type == SearchSourceType.CUSTOM }
                        // Keep legacy fields in sync until all callers have moved to SearchSourceStore.
                        onSave(
                            settings.copy(
                                searchProvider = "多源自动聚合",
                                searchBaseUrl = custom?.baseUrl?.trim().orEmpty(),
                                searchApiKey = custom?.apiKey?.trim().orEmpty(),
                                searchEngine = custom?.engine?.trim().orEmpty()
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存搜索设置")
            }
        }
    }
}

@Composable
private fun SearchSourceEditor(
    source: SearchSourceConfig,
    onChange: (SearchSourceConfig) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = sourceDescription(source.type),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = source.enabled,
                    onCheckedChange = { onChange(source.copy(enabled = it)) }
                )
            }

            when (source.type) {
                SearchSourceType.BRAVE,
                SearchSourceType.TAVILY -> ApiKeyField(source, onChange)

                SearchSourceType.SEARXNG -> {
                    OutlinedTextField(
                        value = source.baseUrl,
                        onValueChange = { onChange(source.copy(baseUrl = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("服务地址") },
                        placeholder = { Text("https://search.example.com") },
                        supportingText = { Text("填写实例根地址；App 会自动追加 /search。") },
                        singleLine = true
                    )
                }

                SearchSourceType.CUSTOM -> {
                    OutlinedTextField(
                        value = source.baseUrl,
                        onValueChange = { onChange(source.copy(baseUrl = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("服务地址") },
                        placeholder = { Text("https://example.com") },
                        supportingText = { Text("兼容原 POST /search 协议。") },
                        singleLine = true
                    )
                    ApiKeyField(source, onChange, optional = true)
                    OutlinedTextField(
                        value = source.engine,
                        onValueChange = { onChange(source.copy(engine = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("搜索引擎或模型（可选）") },
                        singleLine = true
                    )
                }
            }

            if (source.enabled) {
                source.configurationProblem()?.let { problem ->
                    Text(
                        text = problem,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ApiKeyField(
    source: SearchSourceConfig,
    onChange: (SearchSourceConfig) -> Unit,
    optional: Boolean = false
) {
    OutlinedTextField(
        value = source.apiKey,
        onValueChange = { onChange(source.copy(apiKey = it)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(if (optional) "API 密钥（可选）" else "API 密钥") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true
    )
}

private fun sourceDescription(type: SearchSourceType): String = when (type) {
    SearchSourceType.BRAVE -> "Brave 官方 Web Search API"
    SearchSourceType.TAVILY -> "面向回答检索优化的 Tavily Search API"
    SearchSourceType.SEARXNG -> "自托管或可信实例，无需固定供应商"
    SearchSourceType.CUSTOM -> "现有兼容接口：POST /search"
}
