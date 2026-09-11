package com.miniichat.search

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.SerializationException
import java.net.URI

internal fun createSearchHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 45_000
        connectTimeoutMillis = 20_000
        socketTimeoutMillis = 45_000
    }
}

class BraveSearchProvider(private val client: HttpClient) : SearchProvider {
    override val type: SearchSourceType = SearchSourceType.BRAVE

    override suspend fun search(config: SearchSourceConfig, query: String): List<SearchResult> {
        require(config.apiKey.isNotBlank()) { "请先填写 Brave Search API 密钥" }
        val response = client.get("https://api.search.brave.com/res/v1/web/search") {
            accept(ContentType.Application.Json)
            header("X-Subscription-Token", config.apiKey)
            parameter("q", query)
            parameter("count", config.normalizedMaxResults())
        }
        if (!response.status.isSuccess()) throw SearchHttpException(response.status.value)
        return SearchResultParser.parse(response.bodyAsText(), config)
            .take(config.normalizedMaxResults())
    }
}

class TavilySearchProvider(private val client: HttpClient) : SearchProvider {
    override val type: SearchSourceType = SearchSourceType.TAVILY

    override suspend fun search(config: SearchSourceConfig, query: String): List<SearchResult> {
        require(config.apiKey.isNotBlank()) { "请先填写 Tavily API 密钥" }
        val response = client.post("https://api.tavily.com/search") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            setBody(buildJsonObject {
                put("query", query)
                put("search_depth", "basic")
                put("max_results", config.normalizedMaxResults())
                put("include_answer", false)
                put("include_raw_content", false)
            }.toString())
        }
        if (!response.status.isSuccess()) throw SearchHttpException(response.status.value)
        return SearchResultParser.parse(response.bodyAsText(), config)
            .take(config.normalizedMaxResults())
    }
}

class SearxNgSearchProvider(private val client: HttpClient) : SearchProvider {
    override val type: SearchSourceType = SearchSourceType.SEARXNG

    override suspend fun search(config: SearchSourceConfig, query: String): List<SearchResult> {
        val clean = config.baseUrl.trim().trimEnd('/')
        require(clean.isHttpUrl()) { "SearXNG 地址格式错误" }
        val endpoint = if (clean.endsWith("/search", ignoreCase = true)) clean else "$clean/search"
        val response = client.get(endpoint) {
            accept(ContentType.Application.Json)
            parameter("q", query)
            parameter("format", "json")
        }
        if (!response.status.isSuccess()) throw SearchHttpException(response.status.value)
        return SearchResultParser.parse(response.bodyAsText(), config)
            .take(config.normalizedMaxResults())
    }
}

internal object SearchResultParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String, source: SearchSourceConfig): List<SearchResult> {
        val root = try {
            json.parseToJsonElement(raw)
        } catch (error: SerializationException) {
            throw SerializationException("搜索服务返回的内容不是有效 JSON", error)
        }
        val items = findResultArray(root)
            ?: throw SerializationException("搜索服务返回中缺少结果列表")
        return items.mapNotNull { parseItem(it, source) }
    }

    private fun findResultArray(root: JsonElement): JsonArray? {
        if (root is JsonArray) return root
        val obj = root as? JsonObject ?: return null

        // Known response shapes: Tavily/SearXNG/Custom use `results`; Brave uses `web.results`.
        listOf("results", "data", "items", "organic", "web_results").forEach { key ->
            (obj[key] as? JsonArray)?.let { return it }
        }
        (obj["web"] as? JsonObject)?.get("results")?.let { value ->
            (value as? JsonArray)?.let { return it }
        }

        val content = (((obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("message") as? JsonObject)?.string("content")
        if (!content.isNullOrBlank()) {
            runCatching { json.parseToJsonElement(content) }.getOrNull()?.let { nested ->
                return findResultArray(nested)
            }
        }
        return null
    }

    private fun parseItem(element: JsonElement, source: SearchSourceConfig): SearchResult? {
        val obj = element as? JsonObject ?: return null
        val title = obj.string("title") ?: obj.string("name") ?: return null
        val snippet = obj.string("snippet") ?: obj.string("content")
            ?: obj.string("description") ?: ""
        val url = safeWebUrl(
            obj.string("url") ?: obj.string("link") ?: obj.string("source") ?: ""
        )
        return SearchResult(
            title = title.trim().take(300),
            snippet = snippet.trim().take(1_200),
            url = url.trim().take(2_000),
            sourceId = source.id,
            sourceName = source.name
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun safeWebUrl(raw: String): String {
        val clean = raw.trim().take(2_000)
        val uri = runCatching { URI(clean) }.getOrNull() ?: return ""
        return clean.takeIf {
            uri.scheme?.lowercase() in setOf("http", "https") &&
                !uri.host.isNullOrBlank() && uri.userInfo == null
        }.orEmpty()
    }
}

internal fun legacySource(config: SearchConfig): SearchSourceConfig = SearchSourceConfig(
    id = "legacy-custom",
    type = SearchSourceType.CUSTOM,
    name = config.provider.ifBlank { "自定义搜索" },
    enabled = config.baseUrl.isNotBlank(),
    baseUrl = config.baseUrl,
    apiKey = config.apiKey,
    engine = config.engine
)
