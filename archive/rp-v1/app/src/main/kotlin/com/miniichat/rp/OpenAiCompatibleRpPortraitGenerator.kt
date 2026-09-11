package com.miniichat.rp

import android.graphics.BitmapFactory
import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** OpenAI-compatible image adapter. The provider and model are always user-selected. */
class OpenAiCompatibleRpPortraitGenerator : RpPortraitGenerator {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 180_000
        }
    }

    override suspend fun generateFixedPortrait(
        provider: RpPortraitProvider,
        prompt: String,
        outputPath: String
    ): String {
        val response = client.post(imageEndpoint(provider.baseUrl)) {
            contentType(ContentType.Application.Json)
            if (provider.apiKey.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
            }
            provider.customHeaders.forEach { (name, value) -> header(name, value) }
            setBody(json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("model", provider.modelId)
                put("prompt", prompt)
                if (isSiliconFlow(provider.baseUrl)) {
                    put("image_size", "1328x1328")
                    put("batch_size", 1)
                } else {
                    put("size", "1024x1024")
                    put("n", 1)
                }
            }))
        }
        if (!response.status.isSuccess()) {
            error("图片服务 HTTP ${response.status.value}: ${response.bodyAsText().take(240)}")
        }

        val contentType = response.headers[HttpHeaders.ContentType].orEmpty().lowercase()
        val bytes = if (contentType.startsWith("image/")) {
            response.body<ByteArray>()
        } else {
            resolveImageBytes(provider, response.bodyAsText())
        }
        require(bytes.isNotEmpty()) { "图片服务返回空文件" }

        val target = File(outputPath)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        temporary.writeBytes(bytes)
        require(BitmapFactory.decodeFile(temporary.absolutePath) != null) { "图片服务返回格式错误" }
        if (target.exists() && !target.delete()) error("旧头像无法替换")
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        return target.absolutePath
    }

    private suspend fun resolveImageBytes(provider: RpPortraitProvider, raw: String): ByteArray {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: error("图片服务返回格式错误")
        val items = (root["data"] as? JsonArray) ?: (root["images"] as? JsonArray)
        val item = items?.firstOrNull() as? JsonObject
            ?: error("图片服务没有返回图片")
        item["b64_json"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            return Base64.decode(it, Base64.DEFAULT)
        }
        val url = item["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (url.startsWith("data:image", ignoreCase = true)) {
            return Base64.decode(url.substringAfter(','), Base64.DEFAULT)
        }
        if (url.isBlank()) error("图片服务没有返回图片地址")
        val imageResponse = client.get(url) {
            if (sameHost(provider.baseUrl, url) && provider.apiKey.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
            }
            if (sameHost(provider.baseUrl, url)) {
                provider.customHeaders.forEach { (name, value) -> header(name, value) }
            }
        }
        if (!imageResponse.status.isSuccess()) error("头像下载失败：HTTP ${imageResponse.status.value}")
        return imageResponse.body()
    }

    private fun imageEndpoint(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
            .removeSuffix("/chat/completions")
            .removeSuffix("/images/generations")
            .trimEnd('/')
        return "$clean/images/generations"
    }

    private fun sameHost(first: String, second: String): Boolean = runCatching {
        java.net.URI(first).host.equals(java.net.URI(second).host, ignoreCase = true)
    }.getOrDefault(false)

    private fun isSiliconFlow(baseUrl: String): Boolean = runCatching {
        java.net.URI(baseUrl).host.orEmpty().let { host ->
            host.equals("api.siliconflow.cn", ignoreCase = true) ||
                host.equals("api.siliconflow.com", ignoreCase = true)
        }
    }.getOrDefault(false)

    override fun close() = client.close()
}
