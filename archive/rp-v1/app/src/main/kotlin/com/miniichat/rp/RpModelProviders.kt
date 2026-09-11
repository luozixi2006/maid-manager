package com.miniichat.rp

import com.miniichat.data.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class RpAiServiceDefinition(
    val id: String,
    val name: String,
    val defaultBaseUrl: String,
    val custom: Boolean = false
)

object RpAiServices {
    // Keep the historical id for the mainland service so existing settings remain readable.
    const val SILICONFLOW = "siliconflow"
    const val SILICONFLOW_INTERNATIONAL = "siliconflow_international"
    const val OPENROUTER = "openrouter"
    const val CUSTOM = "rp_custom"

    val all = listOf(
        RpAiServiceDefinition(SILICONFLOW, "硅基流动（中国大陆）", "https://api.siliconflow.cn/v1"),
        RpAiServiceDefinition(
            SILICONFLOW_INTERNATIONAL,
            "SiliconFlow International",
            "https://api.siliconflow.com/v1"
        ),
        RpAiServiceDefinition(OPENROUTER, "OpenRouter", "https://openrouter.ai/api/v1"),
        RpAiServiceDefinition(CUSTOM, "自定义 API", "", custom = true)
    )

    fun definition(id: String): RpAiServiceDefinition = all.firstOrNull { it.id == id } ?: all.first()
}

interface RpModelCatalogSource {
    suspend fun load(provider: ProviderConfig): List<RpCatalogModel>
    fun close()
}

class SiliconFlowCatalogSource : BaseCatalogSource() {
    override suspend fun load(provider: ProviderConfig): List<RpCatalogModel> =
        request(
            provider,
            "${modelsBase(provider.baseUrl)}/models?type=text",
            richMetadata = false,
            forceStructuredOutput = true
        )
}

class OpenRouterCatalogSource : BaseCatalogSource() {
    override suspend fun load(provider: ProviderConfig): List<RpCatalogModel> =
        request(provider, "${modelsBase(provider.baseUrl)}/models/user", richMetadata = true)
}

class CustomCompatibleCatalogSource : BaseCatalogSource() {
    override suspend fun load(provider: ProviderConfig): List<RpCatalogModel> =
        request(provider, "${modelsBase(provider.baseUrl)}/models", richMetadata = false)
}

abstract class BaseCatalogSource : RpModelCatalogSource {
    private val client = HttpClient(OkHttp)
    private val json = Json { ignoreUnknownKeys = true }

    protected suspend fun request(
        provider: ProviderConfig,
        url: String,
        richMetadata: Boolean,
        forceStructuredOutput: Boolean = false
    ): List<RpCatalogModel> {
        val response = client.get(url) {
            if (provider.apiKey.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
            }
            provider.customHeaders.forEach { (name, value) -> header(name, value) }
        }
        if (!response.status.isSuccess()) {
            error("HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
        }
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = root["data"] as? JsonArray ?: error("模型列表响应格式不受支持")
        return data.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id")
            if (id.isBlank()) return@mapNotNull null
            val architecture = obj["architecture"] as? JsonObject
            val pricing = obj["pricing"] as? JsonObject
            val inputModalities = architecture.stringList("input_modalities")
            val outputModalities = architecture.stringList("output_modalities")
            val supported = obj.stringList("supported_parameters")
            if (outputModalities.isNotEmpty() && "text" !in outputModalities) return@mapNotNull null
            RpCatalogModel(
                id = id,
                name = obj.string("name").ifBlank { id },
                description = obj.string("description").take(600),
                provider = obj.string("owned_by").ifBlank {
                    id.substringBefore('/').takeUnless { it == id }.orEmpty()
                },
                contextLength = obj.long("context_length"),
                promptPricePerToken = pricing?.number("prompt"),
                completionPricePerToken = pricing?.number("completion"),
                inputModalities = inputModalities,
                outputModalities = outputModalities,
                supportedParameters = supported,
                supportsTextGeneration = true,
                supportsTools = supported.takeIf { it.isNotEmpty() }?.let { "tools" in it },
                supportsStructuredOutput = if (forceStructuredOutput) true else {
                    supported.takeIf { it.isNotEmpty() }?.let {
                        "structured_outputs" in it || "response_format" in it
                    }
                },
                supportsImageInput = inputModalities.takeIf { it.isNotEmpty() }?.let { "image" in it }
            ).let { model ->
                if (richMetadata) model else model.copy(
                    description = model.description,
                    supportsTools = model.supportsTools,
                    supportsStructuredOutput = model.supportsStructuredOutput
                )
            }
        }.filter(::isTextRpCandidate).distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    override fun close() = client.close()
}

private fun modelsBase(value: String): String {
    val clean = value.trim().trimEnd('/')
    return clean.removeSuffix("/chat/completions").trimEnd('/')
}

private fun isTextRpCandidate(model: RpCatalogModel): Boolean {
    val key = "${model.id} ${model.name} ${model.description}".lowercase()
    return listOf(
        "embedding", "rerank", "moderation", "text-to-speech", "speech-to-text",
        "image generation", "stable-diffusion", "flux.", "whisper", "cosyvoice"
    ).none { it in key }
}

private fun JsonObject.string(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.number(key: String): Double? =
    this[key]?.jsonPrimitive?.doubleOrNull
        ?: this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

private fun JsonObject.long(key: String): Long? =
    this[key]?.jsonPrimitive?.longOrNull
        ?: this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

private fun JsonObject?.stringList(key: String): List<String> =
    ((this?.get(key) as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull })
        .orEmpty().map { it.lowercase() }.distinct()
