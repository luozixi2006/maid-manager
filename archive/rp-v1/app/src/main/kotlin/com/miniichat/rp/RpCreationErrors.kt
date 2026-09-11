package com.miniichat.rp

import com.miniichat.api.LlmEmptyResponseException
import com.miniichat.api.LlmHttpException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException

enum class RpCreationRecovery { RETRY, AI_SETTINGS, CHANGE_MODEL, REFRESH_MODELS }

enum class RpCreationErrorKind {
    NETWORK,
    SERVICE_UNREACHABLE,
    AUTH,
    BALANCE,
    MODEL_NOT_FOUND,
    MODEL_UNAVAILABLE,
    RATE_LIMIT,
    TIMEOUT,
    OUTPUT_TRUNCATED,
    FORMAT,
    MODEL_UNSUITABLE,
    EMPTY_RESPONSE,
    UNKNOWN
}

data class RpCreationError(
    val kind: RpCreationErrorKind,
    val title: String = "创建 RP 失败",
    val message: String,
    val detail: String,
    val primaryAction: RpCreationRecovery? = null,
    val secondaryAction: RpCreationRecovery? = null
) {
    fun actionLabel(action: RpCreationRecovery): String = when (action) {
        RpCreationRecovery.RETRY -> if (kind == RpCreationErrorKind.FORMAT) "重新生成" else "重试"
        RpCreationRecovery.AI_SETTINGS -> "AI 设置"
        RpCreationRecovery.CHANGE_MODEL -> "更换模型"
        RpCreationRecovery.REFRESH_MODELS -> "重新检测模型"
    }
}

class RpCreationFormatException(
    val rawLength: Int,
    val looksTruncated: Boolean
) : IllegalStateException(if (looksTruncated) "RP_OUTPUT_TRUNCATED" else "RP_FORMAT_INVALID")

class RpCreationSchemaException(val issues: List<String>) :
    IllegalStateException("RP_SCHEMA_INVALID: ${issues.joinToString()}")

class RpCreationOutputTruncatedException(val finishReason: String?) :
    IllegalStateException("RP_OUTPUT_TRUNCATED: ${finishReason.orEmpty()}")

class RpCreationMissingKeyException : IllegalStateException("RP_API_KEY_MISSING")

object RpCreationErrorClassifier {
    fun classify(
        error: Throwable,
        providerName: String,
        modelId: String,
        previousFormatFailures: Int = 0
    ): RpCreationError {
        val chain = generateSequence(error as Throwable?) { it.cause }.toList()
        val http = chain.filterIsInstance<LlmHttpException>().firstOrNull()
        val body = http?.responseBody.orEmpty()
        val lower = body.lowercase()
        val detailPrefix = listOf(
            "Provider=$providerName",
            "Model=$modelId",
            http?.statusCode?.let { "HTTP=$it" },
            "Type=${error.javaClass.simpleName.ifBlank { "Unknown" }}"
        ).filterNotNull().joinToString(" · ")

        fun result(
            kind: RpCreationErrorKind,
            message: String,
            primary: RpCreationRecovery? = null,
            secondary: RpCreationRecovery? = null,
            extra: String = ""
        ) = RpCreationError(
            kind = kind,
            message = message,
            detail = listOf(detailPrefix, extra.redactSecrets().take(240))
                .filter { it.isNotBlank() }.joinToString("\n"),
            primaryAction = primary,
            secondaryAction = secondary
        )

        if (chain.any { it is RpCreationOutputTruncatedException } ||
            chain.filterIsInstance<RpCreationFormatException>().any { it.looksTruncated }
        ) return result(
            RpCreationErrorKind.OUTPUT_TRUNCATED,
            "模型输出未完成，可能达到输出长度限制。",
            RpCreationRecovery.RETRY,
            RpCreationRecovery.CHANGE_MODEL
        )
        if (chain.any { it is RpCreationFormatException || it is RpCreationSchemaException }) {
            return if (previousFormatFailures >= 2) result(
                RpCreationErrorKind.MODEL_UNSUITABLE,
                "当前模型可能不适合创建完整 RP。",
                RpCreationRecovery.CHANGE_MODEL,
                RpCreationRecovery.RETRY
            ) else result(
                RpCreationErrorKind.FORMAT,
                "模型返回的 RP 数据格式不完整。",
                RpCreationRecovery.RETRY,
                RpCreationRecovery.CHANGE_MODEL
            )
        }
        if (chain.any { it is LlmEmptyResponseException }) return result(
            RpCreationErrorKind.EMPTY_RESPONSE,
            "AI 服务返回了空内容。",
            RpCreationRecovery.RETRY,
            RpCreationRecovery.CHANGE_MODEL
        )
        if (chain.any { it is RpCreationMissingKeyException }) return result(
            RpCreationErrorKind.AUTH,
            "请先填写当前 AI 服务的 API 密钥。",
            RpCreationRecovery.AI_SETTINGS
        )
        if (chain.any {
                it is HttpRequestTimeoutException || it is ConnectTimeoutException ||
                    it is SocketTimeoutException || it is java.net.SocketTimeoutException
            }
        ) return result(
            RpCreationErrorKind.TIMEOUT,
            "AI 响应时间过长，这个模型可能需要更长时间。",
            RpCreationRecovery.RETRY,
            RpCreationRecovery.CHANGE_MODEL
        )
        if (chain.any { it is UnknownHostException || it is NoRouteToHostException }) return result(
            RpCreationErrorKind.NETWORK,
            "无法连接 AI 服务，请检查网络连接后重试。",
            RpCreationRecovery.RETRY
        )
        if (chain.any { it is ConnectException || it is SocketException }) return result(
            RpCreationErrorKind.SERVICE_UNREACHABLE,
            "当前 AI 服务暂时无法连接。",
            RpCreationRecovery.RETRY,
            RpCreationRecovery.AI_SETTINGS
        )
        if (http != null) {
            val balance = lower.containsAny(
                "insufficient balance", "insufficient credit", "insufficient quota",
                "billing", "payment required", "余额不足", "额度不足", "欠费"
            )
            val modelUnavailable = lower.containsAny(
                "no available", "no instance", "no provider", "model unavailable",
                "upstream unavailable", "service unavailable", "无可用实例", "暂时无法调用"
            )
            val modelMissing = lower.containsAny(
                "model not found", "unknown model", "invalid model", "does not exist",
                "模型不存在", "模型已下线"
            )
            return when {
                balance || http.statusCode == 402 -> result(
                    RpCreationErrorKind.BALANCE,
                    "API 余额或额度不足。",
                    RpCreationRecovery.AI_SETTINGS,
                    extra = body
                )
                http.statusCode == 401 || http.statusCode == 403 -> result(
                    RpCreationErrorKind.AUTH,
                    "API 密钥无效或已失效。",
                    RpCreationRecovery.AI_SETTINGS,
                    extra = body
                )
                http.statusCode == 429 -> result(
                    RpCreationErrorKind.RATE_LIMIT,
                    "请求过于频繁，请稍后再试。",
                    RpCreationRecovery.RETRY,
                    extra = http.retryAfter?.let { "Retry-After=$it" }.orEmpty()
                )
                modelMissing || http.statusCode == 404 -> result(
                    RpCreationErrorKind.MODEL_NOT_FOUND,
                    "当前模型已不可用。",
                    RpCreationRecovery.REFRESH_MODELS,
                    RpCreationRecovery.CHANGE_MODEL,
                    body
                )
                modelUnavailable || http.statusCode in setOf(502, 503, 504) -> result(
                    RpCreationErrorKind.MODEL_UNAVAILABLE,
                    "当前模型暂时无法调用。",
                    RpCreationRecovery.RETRY,
                    RpCreationRecovery.CHANGE_MODEL,
                    body
                )
                else -> result(
                    RpCreationErrorKind.UNKNOWN,
                    "AI 服务返回错误（HTTP ${http.statusCode}）。",
                    RpCreationRecovery.RETRY,
                    RpCreationRecovery.AI_SETTINGS,
                    body
                )
            }
        }
        return result(
            RpCreationErrorKind.UNKNOWN,
            error.message?.take(160) ?: "创建 RP 时发生未知错误。",
            RpCreationRecovery.RETRY
        )
    }
}

private fun String.containsAny(vararg values: String): Boolean = values.any { it in this }

private fun String.redactSecrets(): String = this
    .replace(Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,}]+"), "\$1***")
    .replace(Regex("(?i)(api[_ -]?key\\s*[:=]\\s*[\"']?)[^\"'\\s,}]+"), "\$1***")
    .replace(Regex("\\bsk-[A-Za-z0-9_-]{8,}\\b"), "sk-***")
