package com.miniichat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import java.net.URI

data class ServiceHelp(val name: String, val console: String, val docs: String, val models: String)

private val siliconHelp = ServiceHelp("硅基流动", "https://cloud.siliconflow.cn/",
    "https://docs.siliconflow.cn/docs/userguide/capabilities/vision", "https://cloud.siliconflow.cn/models")
private val routerHelp = ServiceHelp("OpenRouter", "https://openrouter.ai/keys",
    "https://openrouter.ai/docs", "https://openrouter.ai/models")
private val deepseekHelp = ServiceHelp("DeepSeek", "https://platform.deepseek.com/",
    "https://api-docs.deepseek.com/", "https://api-docs.deepseek.com/quick_start/pricing")

fun helpForService(baseUrl: String): ServiceHelp? {
    val host = runCatching { URI(baseUrl.trim()).host?.lowercase() }.getOrNull() ?: return null
    return when (host) {
        "api.siliconflow.cn", "api.siliconflow.com" -> siliconHelp
        "openrouter.ai" -> routerHelp
        "api.deepseek.com" -> deepseekHelp
        else -> null
    }
}

@Composable
fun OfficialLink(label: String, url: String) {
    val handler = LocalUriHandler.current
    var failed by remember { mutableStateOf(false) }
    TextButton(onClick = { failed = runCatching { handler.openUri(url) }.isFailure }) {
        Text("$label ↗", style = MaterialTheme.typography.bodyMedium)
    }
    if (failed) Text("无法打开浏览器：$url", color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall)
}

@Composable
fun ProviderHelp(baseUrl: String) {
    val help = helpForService(baseUrl)
    Column(Modifier.fillMaxWidth()) {
        if (help != null) {
            OfficialLink("${help.name} · 获取 API 密钥", help.console)
            OfficialLink("模型目录与价格", help.models)
            OfficialLink("接口与使用说明", help.docs)
        } else {
            Text("自定义服务的地址、密钥和模型 ID 请以该服务商的文档为准。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OfficialLink("硅基流动 · 密钥与模型", siliconHelp.console)
            OfficialLink("OpenRouter · 模型目录", routerHelp.models)
        }
        Text("发送照片需要视觉模型；请在模型目录确认支持图片输入。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
