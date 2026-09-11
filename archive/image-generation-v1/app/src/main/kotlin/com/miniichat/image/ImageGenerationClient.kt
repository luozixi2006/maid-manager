package com.miniichat.image

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

@Serializable
private data class GenerationRequestBody(
    val prompt: String,
    @SerialName("original_prompt") val originalPrompt: String,
    val model: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    @SerialName("guidance_scale") val guidanceScale: Float
)

@Serializable
private data class GenerationJobResponse(
    val id: String,
    val status: String,
    val model: String = FLUX2_KLEIN_MODEL_ID,
    val width: Int = 0,
    val height: Int = 0,
    val seed: Long = 0,
    @SerialName("original_prompt") val originalPrompt: String = "",
    @SerialName("effective_prompt") val effectivePrompt: String = "",
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null
)

@Serializable
private data class HealthResponse(
    val service: String = "",
    @SerialName("model_status") val modelStatus: String = "not_loaded",
    val model: String = FLUX2_KLEIN_MODEL_ID,
    val variant: String = "",
    @SerialName("model_error") val modelError: String? = null,
    val gpu: GpuResponse = GpuResponse()
)

@Serializable
private data class GpuResponse(
    val available: Boolean = false,
    val name: String? = null,
    @SerialName("free_vram_mb") val freeVramMb: Int? = null
)

private data class DownloadedImage(
    val job: GenerationJobResponse,
    val bytes: ByteArray
)

class ImageGenerationClient {
    private val jsonCodec = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(jsonCodec) }
        install(HttpTimeout)
    }

    suspend fun testConnection(config: ImageGenerationConfig): ImageServiceHealth {
        val baseUrl = normalizedBaseUrl(config.baseUrl)
        return try {
            val response = client.get("$baseUrl/health") {
                timeout {
                    connectTimeoutMillis = 5_000
                    requestTimeoutMillis = 10_000
                    socketTimeoutMillis = 10_000
                }
            }
            ensureSuccess(response)
            val health = response.body<HealthResponse>()
            ImageServiceHealth(
                serviceReady = health.service == "ready",
                modelStatus = health.modelStatus,
                model = health.model,
                variant = health.variant,
                gpuName = health.gpu.name,
                freeVramMb = health.gpu.freeVramMb,
                modelError = health.modelError
            )
        } catch (error: ImageGenerationException) {
            throw error
        } catch (error: IOException) {
            throw ImageGenerationException("PC_UNREACHABLE", "无法连接 PC 生图服务", error)
        } catch (error: Exception) {
            throw ImageGenerationException("INVALID_RESPONSE", "生图服务返回数据异常", error)
        }
    }

    suspend fun generate(
        config: ImageGenerationConfig,
        originalPrompt: String,
        effectivePrompt: String,
        onJobChanged: (String, ImageGenerationStatus) -> Unit
    ): Pair<GeneratedImageResult, ByteArray> {
        if (!config.enabled) throw ImageGenerationException("FEATURE_DISABLED", "AI 生图功能尚未启用")
        val baseUrl = normalizedBaseUrl(config.baseUrl)
        var activeJobId: String? = null
        try {
            val downloaded = withTimeout(config.timeoutSeconds.coerceIn(30, 1800) * 1_000L) {
                val startResponse = client.post("$baseUrl/v1/images/jobs") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        GenerationRequestBody(
                            prompt = effectivePrompt,
                            originalPrompt = originalPrompt,
                            model = config.model,
                            width = config.width,
                            height = config.height,
                            steps = config.steps,
                            guidanceScale = config.guidanceScale
                        )
                    )
                }
                ensureSuccess(startResponse)
                var job = startResponse.body<GenerationJobResponse>()
                activeJobId = job.id
                onJobChanged(job.id, job.status.toUiStatus())
                while (job.status !in setOf("succeeded", "failed", "cancelled")) {
                    delay(750)
                    val pollResponse = client.get("$baseUrl/v1/images/jobs/${job.id}")
                    ensureSuccess(pollResponse)
                    job = pollResponse.body()
                    onJobChanged(job.id, job.status.toUiStatus())
                }
                when (job.status) {
                    "failed" -> throw ImageGenerationException(
                        job.errorCode ?: "INFERENCE_FAILED",
                        job.errorMessage ?: "模型推理失败"
                    )
                    "cancelled" -> throw ImageGenerationException("CANCELLED", "图片生成已取消")
                }
                val imageResponse = client.get("$baseUrl/v1/images/jobs/${job.id}/image")
                ensureSuccess(imageResponse)
                DownloadedImage(job, imageResponse.body())
            }
            val job = downloaded.job
            return GeneratedImageResult(
                jobId = job.id,
                localPath = "",
                originalPrompt = job.originalPrompt.ifBlank { originalPrompt },
                effectivePrompt = job.effectivePrompt.ifBlank { effectivePrompt },
                model = job.model,
                width = job.width,
                height = job.height,
                seed = job.seed
            ) to downloaded.bytes
        } catch (error: TimeoutCancellationException) {
            cancelQuietly(baseUrl, activeJobId)
            throw ImageGenerationException("TIMEOUT", "图片生成超时，后端任务已请求取消", error)
        } catch (error: CancellationException) {
            withContext(NonCancellable) { cancelQuietly(baseUrl, activeJobId) }
            throw error
        } catch (error: ImageGenerationException) {
            throw error
        } catch (error: IOException) {
            throw ImageGenerationException("PC_UNREACHABLE", "与 PC 生图服务的连接已中断", error)
        } catch (error: Exception) {
            throw ImageGenerationException("INVALID_RESPONSE", "生图服务返回数据异常", error)
        }
    }

    private suspend fun cancelQuietly(baseUrl: String, jobId: String?) {
        if (jobId.isNullOrBlank()) return
        runCatching { client.delete("$baseUrl/v1/images/jobs/$jobId") }
    }

    private suspend fun ensureSuccess(response: HttpResponse) {
        if (response.status.isSuccess()) return
        val raw = runCatching { response.bodyAsText() }.getOrDefault("")
        val (code, message) = parseError(raw)
        throw ImageGenerationException(code, message.ifBlank { "生图服务 HTTP ${response.status.value}" })
    }

    private fun parseError(raw: String): Pair<String, String> {
        val root = runCatching { jsonCodec.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return "HTTP_ERROR" to raw.take(300)
        val detail = root["detail"]
        val objectValue = when (detail) {
            is JsonObject -> detail
            else -> root
        }
        val code = (objectValue["code"] as? JsonPrimitive)?.contentOrNull ?: "HTTP_ERROR"
        val message = (objectValue["message"] as? JsonPrimitive)?.contentOrNull
            ?: (detail as? JsonPrimitive)?.contentOrNull.orEmpty()
        return code to message
    }

    private fun normalizedBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) throw ImageGenerationException("ADDRESS_MISSING", "请先填写 PC 生图服务地址")
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw ImageGenerationException("ADDRESS_INVALID", "服务地址必须以 http:// 或 https:// 开头")
        }
        return trimmed.removeSuffix("/v1")
    }

    fun close() = client.close()
}

private fun String.toUiStatus(): ImageGenerationStatus = when (this) {
    "waiting" -> ImageGenerationStatus.WAITING
    "loading_model" -> ImageGenerationStatus.LOADING_MODEL
    "generating" -> ImageGenerationStatus.GENERATING
    "succeeded" -> ImageGenerationStatus.SUCCEEDED
    "failed" -> ImageGenerationStatus.FAILED
    "cancelled" -> ImageGenerationStatus.CANCELLED
    else -> ImageGenerationStatus.WAITING
}
