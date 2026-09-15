package com.miniichat.api

import com.miniichat.data.AppSettings
import com.miniichat.data.ProviderAuthMode
import com.miniichat.data.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

data class ChatMessage(
    val role: String,
    val content: String,
    val imageDataUrls: List<String> = emptyList()
)

@Serializable
private data class ChatChunk(
    val choices: List<Choice> = emptyList()
) {
    @Serializable
    data class Choice(
        val delta: Delta? = null,
        val message: ResponseMessage? = null,
        @SerialName("finish_reason") val finishReason: String? = null
    )
    @Serializable
    data class Delta(
        val content: String? = null,
        @SerialName("reasoning_content") val reasoningContent: String? = null,
        val role: String? = null
    )
    @Serializable
    data class ResponseMessage(
        val role: String = "assistant",
        val content: String = "",
        @SerialName("reasoning_content") val reasoningContent: String? = null
    )
}

@Serializable
private data class ChatResponse(
    val choices: List<ChatChunk.Choice> = emptyList()
)

@Serializable
private data class ModelEntry(val id: String)

@Serializable
private data class ModelsResponse(val data: List<ModelEntry> = emptyList())

data class ChatStreamEvent(
    val content: String? = null,
    val reasoning: String? = null
)

data class LlmCompletionResult(
    val content: String,
    val finishReason: String?,
    val httpStatus: Int,
    val elapsedMillis: Long,
    val structuredModeUsed: Boolean
)

class LlmHttpException(
    val statusCode: Int,
    val responseBody: String,
    val retryAfter: String? = null
) : RuntimeException("HTTP $statusCode")

class LlmEmptyResponseException : IllegalStateException("EMPTY_RESPONSE")

class LlmProtocolException(message: String) : IllegalStateException(message)

class LlmClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    /** Best-effort parse of a value that may be a number, bool, or string. */
    private fun coerceToJson(v: String): JsonElement {
        if (v.equals("true", true)) return JsonPrimitive(true)
        if (v.equals("false", true)) return JsonPrimitive(false)
        v.toLongOrNull()?.let { return JsonPrimitive(it) }
        v.toDoubleOrNull()?.let { return JsonPrimitive(it) }
        return JsonPrimitive(v)
    }

    private fun buildRequestBody(
        provider: ProviderConfig,
        modelId: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        temperature: Float,
        reasoningEnabled: Boolean,
        reasoningEffort: String,
        structuredJson: Boolean,
        maxOutputTokens: Int? = null
    ): String {
        val msgsJson = kotlinx.serialization.json.buildJsonArray {
            for (m in messages) {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("role", m.role)
                    put("content", messageContent(m))
                })
            }
        }
        val obj = buildJsonObject {
            put("model", modelId)
            put("stream", stream)
            put("temperature", temperature)
            put("messages", msgsJson)
            if (structuredJson) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
            maxOutputTokens?.takeIf { it > 0 }?.let { put("max_tokens", it) }
            if (supportsNativeReasoning(provider, modelId)) {
                put("thinking", buildJsonObject {
                    put("type", if (reasoningEnabled) "enabled" else "disabled")
                })
                if (reasoningEnabled) {
                    val effort = reasoningEffort.takeIf { it in setOf("low", "high", "max") } ?: "high"
                    put("reasoning_effort", effort)
                }
            }
            for ((k, v) in provider.extraBody) {
                if (k.isBlank() || k.lowercase(Locale.ROOT) in RESERVED_REQUEST_BODY_KEYS) continue
                put(k, coerceToJson(v))
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * Non-streaming completion with per-call timeouts and preserved response metadata.
     * Used by larger structured-output jobs that need their own timeout budget.
     */
    suspend fun completeDetailed(
        provider: ProviderConfig,
        modelId: String,
        messages: List<ChatMessage>,
        temperature: Float,
        structuredJson: Boolean,
        requestTimeoutMillis: Long,
        maxOutputTokens: Int? = null
    ): LlmCompletionResult {
        suspend fun request(useStructuredJson: Boolean): LlmCompletionResult {
            val startedAt = System.currentTimeMillis()
            val bodyText = buildRequestBody(
                provider = provider,
                modelId = modelId,
                messages = messages,
                stream = false,
                temperature = temperature,
                reasoningEnabled = false,
                reasoningEffort = "high",
                structuredJson = useStructuredJson,
                maxOutputTokens = maxOutputTokens
            )
            val response = client.post(OpenAiEndpointResolver.resolve(provider.baseUrl).chatCompletions) {
                timeout {
                    this.requestTimeoutMillis = requestTimeoutMillis
                    socketTimeoutMillis = requestTimeoutMillis
                    connectTimeoutMillis = 30_000
                }
                contentType(ContentType.Application.Json)
                headers {
                    if (provider.authMode == ProviderAuthMode.BEARER && provider.apiKey.isNotBlank()) {
                        append(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
                    }
                    append(HttpHeaders.Accept, "application/json")
                    for ((key, value) in provider.customHeaders) {
                        if (key.isNotBlank()) append(key, value)
                    }
                }
                setBody(bodyText)
            }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw LlmHttpException(
                    response.status.value,
                    text,
                    response.headers["Retry-After"]
                )
            }
            val parsed = try {
                json.decodeFromString(ChatResponse.serializer(), text)
            } catch (error: Exception) {
                throw LlmProtocolException("响应不是兼容的 OpenAI JSON 格式")
            }
            val choice = parsed.choices.firstOrNull()
            val content = choice?.message?.content.orEmpty()
            if (content.isBlank()) throw LlmEmptyResponseException()
            return LlmCompletionResult(
                content = content,
                finishReason = choice?.finishReason,
                httpStatus = response.status.value,
                elapsedMillis = System.currentTimeMillis() - startedAt,
                structuredModeUsed = useStructuredJson
            )
        }

        return try {
            request(structuredJson)
        } catch (error: LlmHttpException) {
            val unsupportedStructuredOutput = structuredJson && error.statusCode in setOf(400, 422) &&
                error.responseBody.containsAnyIgnoreCase(
                    "response_format", "structured output", "json_object", "json schema"
                )
            if (unsupportedStructuredOutput) request(false) else throw error
        }
    }

    suspend fun listModels(provider: ProviderConfig): List<String> {
        val resp = client.get(OpenAiEndpointResolver.resolve(provider.baseUrl).models) {
            headers {
                if (provider.authMode == ProviderAuthMode.BEARER && provider.apiKey.isNotBlank()) {
                    append(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
                }
                for ((k, v) in provider.customHeaders) {
                    if (k.isBlank()) continue
                    append(k, v)
                }
            }
        }
        if (!resp.status.isSuccess()) {
            val err = runCatching { resp.bodyAsText() }.getOrDefault("")
            throw LlmHttpException(resp.status.value, err, resp.headers["Retry-After"])
        }
        val text = resp.bodyAsText()
        val parsed = runCatching {
            json.decodeFromString(ModelsResponse.serializer(), text)
        }.getOrNull()
        if (parsed != null && parsed.data.isNotEmpty()) {
            return parsed.data.map { it.id }.distinct().sorted()
        }
        val ids = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        return ids.distinct().sorted()
    }

    fun chatStream(
        provider: ProviderConfig,
        settings: AppSettings,
        modelId: String,
        messages: List<ChatMessage>,
        reasoningEnabled: Boolean = false,
        reasoningEffort: String = "high",
        structuredJson: Boolean = false
    ): Flow<ChatStreamEvent> = flow {
        val bodyText = buildRequestBody(
            provider = provider,
            modelId = modelId,
            messages = messages,
            stream = settings.stream,
            temperature = settings.temperature,
            reasoningEnabled = reasoningEnabled,
            reasoningEffort = reasoningEffort,
            structuredJson = structuredJson,
            maxOutputTokens = null
        )

        client.preparePost(OpenAiEndpointResolver.resolve(provider.baseUrl).chatCompletions) {
            contentType(ContentType.Application.Json)
            headers {
                if (provider.authMode == ProviderAuthMode.BEARER && provider.apiKey.isNotBlank()) {
                    append(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
                }
                append(HttpHeaders.Accept, if (settings.stream) "text/event-stream" else "application/json")
                for ((k, v) in provider.customHeaders) {
                    if (k.isBlank()) continue
                    append(k, v)
                }
            }
            setBody(bodyText)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val errBody = runCatching { response.bodyAsText() }.getOrDefault("")
                throw LlmHttpException(
                    response.status.value,
                    errBody,
                    response.headers["Retry-After"]
                )
            }
            val returnedType = response.contentType()
            val returnedJson = returnedType?.let {
                it.match(ContentType.Application.Json) || it.contentSubtype.endsWith("+json")
            } == true
            if (settings.stream && !returnedJson) {
                val channel: ByteReadChannel = response.bodyAsChannel()
                var emittedAny = false
                var sawDataPayload = false
                var sawNonEmptyLine = false
                var sawTerminalMarker = false
                var malformedPayloads = 0
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isEmpty()) continue
                    sawNonEmptyLine = true
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") {
                        sawTerminalMarker = true
                        break
                    }
                    if (payload.isEmpty()) continue
                    sawDataPayload = true
                    val chunk = runCatching {
                        json.decodeFromString(ChatChunk.serializer(), payload)
                    }.getOrElse {
                        malformedPayloads += 1
                        null
                    } ?: continue
                    val choice = chunk.choices.firstOrNull()
                    if (choice?.finishReason != null) sawTerminalMarker = true
                    val delta = choice?.delta
                    if (!delta?.reasoningContent.isNullOrEmpty()) {
                        emittedAny = true
                        emit(ChatStreamEvent(reasoning = delta?.reasoningContent))
                    }
                    if (!delta?.content.isNullOrEmpty()) {
                        emittedAny = true
                        emit(ChatStreamEvent(content = delta?.content))
                    }
                }
                if (!emittedAny) {
                    if ((sawDataPayload && malformedPayloads > 0) ||
                        (sawNonEmptyLine && !sawDataPayload)
                    ) {
                        throw LlmProtocolException("流式响应不是兼容的 OpenAI SSE 格式")
                    }
                    throw LlmEmptyResponseException()
                }
                // A socket EOF is not proof that an OpenAI-compatible stream completed. If
                // content has already reached the user, surfacing an incomplete-stream error
                // lets ChatGateway keep that partial reply without switching models mid-answer.
                if (malformedPayloads > 0 || !sawTerminalMarker) {
                    throw LlmProtocolException("流式响应在完成前中断")
                }
            } else {
                val text = response.bodyAsText()
                val parsed = try {
                    json.decodeFromString(ChatResponse.serializer(), text)
                } catch (error: Exception) {
                    throw LlmProtocolException("响应不是兼容的 OpenAI JSON 格式")
                }
                val content = parsed.choices.firstOrNull()?.message?.content
                val reasoning = parsed.choices.firstOrNull()?.message?.reasoningContent
                if (!reasoning.isNullOrEmpty()) emit(ChatStreamEvent(reasoning = reasoning))
                if (!content.isNullOrEmpty()) emit(ChatStreamEvent(content = content))
                if (reasoning.isNullOrEmpty() && content.isNullOrEmpty()) {
                    throw LlmEmptyResponseException()
                }
            }
        }
    }

    fun supportsNativeReasoning(provider: ProviderConfig, modelId: String): Boolean {
        val host = runCatching { java.net.URI(provider.baseUrl).host.orEmpty() }.getOrDefault("")
        return host.equals("api.deepseek.com", ignoreCase = true) &&
            modelId.startsWith("deepseek-v4", ignoreCase = true)
    }

    fun close() = client.close()
}

private fun String.containsAnyIgnoreCase(vararg values: String): Boolean =
    values.any { contains(it, ignoreCase = true) }

/** Extra provider parameters may extend a request, but must not replace its routed identity. */
private val RESERVED_REQUEST_BODY_KEYS = setOf(
    "model",
    "messages",
    "stream",
    "temperature",
    "response_format",
    "max_tokens",
    "thinking",
    "reasoning_effort"
)
