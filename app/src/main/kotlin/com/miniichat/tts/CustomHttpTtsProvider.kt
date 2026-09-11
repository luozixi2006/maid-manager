package com.miniichat.tts

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URI
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CustomHttpTtsProvider : TtsProvider {
    private companion object {
        const val TAG = "MaidManagerTts"
    }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    override suspend fun synthesize(config: TtsConfig, text: String): ByteArray {
        val clean = config.baseUrl.trim().trimEnd('/')
        require(clean.startsWith("http://") || clean.startsWith("https://")) {
            "TTS Base URL 格式错误"
        }
        val endpoint = if (clean.endsWith("/audio/speech", ignoreCase = true)) clean
        else "$clean/audio/speech"
        val diagnosticEndpoint = endpoint.safeEndpointForLog()
        val requestBody = buildJsonObject {
            put("model", config.model)
            put("voice", config.voiceId)
            put("input", text)
            put("response_format", "wav")
            put("speed", 1.0)
        }.toString()
        // The request body contains the private message being spoken and must never be logged.
        Log.d(TAG, "POST $diagnosticEndpoint")
        val response = try {
            client.post(endpoint) {
                contentType(ContentType.Application.Json)
                headers {
                    if (config.apiKey.isNotBlank()) {
                        append(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                    }
                    append(HttpHeaders.Accept, "audio/wav, audio/*;q=0.9, application/octet-stream;q=0.8")
                }
                setBody(requestBody)
            }
        } catch (error: Throwable) {
            // Throwable messages may contain a raw URL or client internals. Keep only the type.
            Log.e(TAG, "POST $diagnosticEndpoint failed type=${error::class.java.name}")
            throw error
        }
        val responseType = response.headers[HttpHeaders.ContentType]
        Log.d(
            TAG,
            "POST $diagnosticEndpoint status=${response.status.value} " +
                "contentType=${responseType ?: "missing"}"
        )
        if (!response.status.isSuccess()) {
            // Consume for connection reuse, then discard. The response body may contain secrets.
            runCatching { response.bodyAsText() }
            throw IllegalStateException("TTS HTTP ${response.status.value}")
        }
        val normalizedType = responseType?.substringBefore(';')?.trim()?.lowercase()
        if (normalizedType !in setOf(
                "audio/wav",
                "audio/x-wav",
                "audio/wave",
                "audio/vnd.wave",
                "application/octet-stream"
            )
        ) {
            throw IllegalStateException("TTS 返回格式错误：${responseType ?: "缺少 Content-Type"}")
        }
        val bytes = response.body<ByteArray>()
        require(bytes.isNotEmpty()) { "TTS 返回音频为空" }
        if (!bytes.isWaveFile()) {
            throw IllegalStateException("TTS 返回格式错误：响应不是有效 WAV")
        }
        Log.d(TAG, "Received WAV bytes=${bytes.size}")
        return bytes
    }

    fun close() = client.close()
}

/** Returns a URL safe for diagnostics: no user info, query or fragment. */
private fun String.safeEndpointForLog(): String = runCatching {
    val uri = URI(this)
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" }
        ?: return@runCatching "<invalid-endpoint>"
    val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() }
        ?: return@runCatching "<invalid-endpoint>"
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    val path = uri.rawPath.orEmpty()
        .split('/')
        .joinToString("/") { segment ->
            when {
                // Some compatible services place credentials in a path segment.
                segment.length > 64 -> "[hidden]"
                else -> segment.replace(Regex("[^A-Za-z0-9._~!$&'()*+,;=:@%\\-]"), "_")
            }
        }
        .let { if (it.startsWith('/')) it else "/$it" }
    "$scheme://$host$port$path"
}.getOrDefault("<invalid-endpoint>")

private fun ByteArray.isWaveFile(): Boolean {
    if (size < 12) return false
    return this[0] == 'R'.code.toByte() &&
        this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() &&
        this[3] == 'F'.code.toByte() &&
        this[8] == 'W'.code.toByte() &&
        this[9] == 'A'.code.toByte() &&
        this[10] == 'V'.code.toByte() &&
        this[11] == 'E'.code.toByte()
}
