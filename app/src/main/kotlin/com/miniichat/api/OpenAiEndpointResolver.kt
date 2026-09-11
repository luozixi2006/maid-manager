package com.miniichat.api

import java.net.URI

data class OpenAiEndpoints(
    val chatCompletions: String,
    val models: String
)

object OpenAiEndpointResolver {
    fun resolve(baseUrl: String): OpenAiEndpoints {
        val clean = baseUrl.trim().trimEnd('/')
        val uri = runCatching { URI(clean) }.getOrNull()
        require(uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()) {
            "Base URL 格式错误"
        }
        require(uri?.rawQuery == null && uri?.rawFragment == null && uri?.userInfo == null) {
            "Base URL 不能包含账号、查询参数或片段"
        }

        val lower = clean.lowercase()
        val apiRoot = when {
            lower.endsWith("/chat/completions") -> clean.dropLast("/chat/completions".length)
            lower.endsWith("/models") -> clean.dropLast("/models".length)
            else -> clean
        }.trimEnd('/')

        return OpenAiEndpoints(
            chatCompletions = "$apiRoot/chat/completions",
            models = "$apiRoot/models"
        )
    }
}
