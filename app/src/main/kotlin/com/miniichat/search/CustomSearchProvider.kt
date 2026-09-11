package com.miniichat.search

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CustomSearchProvider(
    private val client: HttpClient = createSearchHttpClient()
) : SearchProvider {
    override val type: SearchSourceType = SearchSourceType.CUSTOM

    override suspend fun search(
        config: SearchSourceConfig,
        query: String
    ): List<SearchResult> {
        val clean = config.baseUrl.trim().trimEnd('/')
        require(clean.isHttpUrl()) { "Search Base URL 格式错误" }
        val endpoint = if (clean.endsWith("/search", ignoreCase = true)) clean else "$clean/search"
        val response = client.post(endpoint) {
            contentType(ContentType.Application.Json)
            headers {
                if (config.apiKey.isNotBlank()) {
                    append(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                    append("X-API-Key", config.apiKey)
                }
            }
            setBody(buildJsonObject {
                put("query", query)
                put("engine", config.engine)
                put("max_results", config.normalizedMaxResults())
            }.toString())
        }
        if (!response.status.isSuccess()) throw SearchHttpException(response.status.value)
        return SearchResultParser.parse(
            raw = response.bodyAsText(),
            source = config
        ).take(config.normalizedMaxResults())
    }

    /** Compatibility for callers that still hold the old settings object. */
    suspend fun search(config: SearchConfig, query: String): List<SearchResult> = search(
        legacySource(config),
        query
    )
}
