package com.miniichat.search

import java.net.URI
import kotlinx.serialization.Serializable

/** Legacy single-provider settings kept so existing installations can be migrated. */
data class SearchConfig(
    val provider: String,
    val baseUrl: String,
    val apiKey: String,
    val engine: String
)

@Serializable
enum class SearchSourceType {
    BRAVE,
    TAVILY,
    SEARXNG,
    CUSTOM
}

@Serializable
data class SearchSourceConfig(
    val id: String,
    val type: SearchSourceType,
    val name: String,
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String = "",
    val engine: String = "",
    val maxResults: Int = 5
) {
    fun configurationProblem(): String? = when (type) {
        SearchSourceType.BRAVE,
        SearchSourceType.TAVILY -> if (apiKey.isBlank()) "请先填写 API 密钥" else null

        SearchSourceType.SEARXNG -> if (!baseUrl.isHttpUrl()) "请填写有效的 SearXNG 地址" else null
        SearchSourceType.CUSTOM -> if (!baseUrl.isHttpUrl()) "请填写有效的自定义搜索地址" else null
    }

    fun normalizedMaxResults(): Int = maxResults.coerceIn(1, 10)
}

data class SearchResult(
    val title: String,
    val snippet: String,
    val url: String,
    val sourceId: String = "",
    val sourceName: String = ""
)

enum class SearchFailureKind {
    CONFIGURATION,
    NETWORK,
    HOST_NOT_FOUND,
    CONNECTION,
    TLS,
    CLEARTEXT,
    TIMEOUT,
    HTTP,
    INVALID_RESPONSE,
    UNKNOWN
}

data class SearchFailure(
    val sourceId: String,
    val sourceName: String,
    val kind: SearchFailureKind,
    val message: String,
    val httpStatus: Int? = null,
    val providerBaseUrl: String? = null
)

data class SearchOutcome(
    val results: List<SearchResult>,
    val failures: List<SearchFailure> = emptyList()
) {
    val isPartialSuccess: Boolean get() = results.isNotEmpty() && failures.isNotEmpty()
}

interface SearchProvider {
    val type: SearchSourceType
    suspend fun search(config: SearchSourceConfig, query: String): List<SearchResult>
}

class SearchHttpException(val statusCode: Int) :
    IllegalStateException("搜索服务返回 HTTP $statusCode")

internal fun String.isHttpUrl(): Boolean {
    val uri = runCatching { URI(trim()) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https") &&
        !uri.host.isNullOrBlank() && uri.userInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null
}
