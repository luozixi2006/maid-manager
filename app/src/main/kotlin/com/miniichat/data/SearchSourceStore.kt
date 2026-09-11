package com.miniichat.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.miniichat.search.SearchConfig
import com.miniichat.search.SearchSourceConfig
import com.miniichat.search.SearchSourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.searchSourcesDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "search_sources")

/** Local-only configuration for automatic multi-source search. */
class SearchSourceStore(private val context: Context) {
    private val sourcesKey = stringPreferencesKey("sources_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val sourcesFlow: Flow<List<SearchSourceConfig>> = context.searchSourcesDataStore.data.map { prefs ->
        decode(prefs[sourcesKey]) ?: defaultSources()
    }

    suspend fun snapshot(): List<SearchSourceConfig> = sourcesFlow.first()

    suspend fun save(sources: List<SearchSourceConfig>) {
        val normalized = normalize(sources)
        context.searchSourcesDataStore.edit { prefs ->
            prefs[sourcesKey] = json.encodeToString(
                ListSerializer(SearchSourceConfig.serializer()),
                normalized
            )
        }
    }

    suspend fun upsert(source: SearchSourceConfig) {
        context.searchSourcesDataStore.edit { prefs ->
            val current = (decode(prefs[sourcesKey]) ?: defaultSources()).toMutableList()
            val index = current.indexOfFirst { it.id == source.id }
            if (index >= 0) current[index] = source else current += source
            prefs[sourcesKey] = json.encodeToString(
                ListSerializer(SearchSourceConfig.serializer()),
                normalize(current)
            )
        }
    }

    /**
     * Imports the previous single custom source once. It never overwrites a multi-source setup.
     */
    suspend fun migrateLegacy(config: SearchConfig) {
        if (config.baseUrl.isBlank()) return
        context.searchSourcesDataStore.edit { prefs ->
            if (!prefs[sourcesKey].isNullOrBlank()) return@edit
            val migrated = defaultSources().map { source ->
                if (source.type == SearchSourceType.CUSTOM) {
                    source.copy(
                        name = config.provider.ifBlank { "自定义搜索" },
                        enabled = true,
                        baseUrl = config.baseUrl.trim(),
                        apiKey = config.apiKey.trim(),
                        engine = config.engine.trim()
                    )
                } else {
                    source
                }
            }
            prefs[sourcesKey] = json.encodeToString(
                ListSerializer(SearchSourceConfig.serializer()),
                migrated
            )
        }
    }

    private fun decode(raw: String?): List<SearchSourceConfig>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString(ListSerializer(SearchSourceConfig.serializer()), raw)
        }.getOrNull()?.let(::normalize)
    }

    private fun normalize(sources: List<SearchSourceConfig>): List<SearchSourceConfig> {
        val fixed = defaultSources().map { fallback ->
            val saved = sources.firstOrNull { it.id == fallback.id }
                ?: sources.firstOrNull { it.type == fallback.type }
            saved?.copy(
                id = fallback.id,
                name = saved.name.ifBlank { fallback.name },
                baseUrl = normalizeBaseUrl(saved.baseUrl),
                apiKey = saved.apiKey.trim(),
                engine = saved.engine.trim(),
                maxResults = saved.maxResults.coerceIn(1, 10)
            ) ?: fallback
        }
        val extraCustom = sources.filter { source ->
            source.type == SearchSourceType.CUSTOM && source.id != "custom"
        }
        return (fixed + extraCustom.map { source ->
            source.copy(
                name = source.name.trim().ifBlank { "自定义搜索" },
                baseUrl = normalizeBaseUrl(source.baseUrl),
                apiKey = source.apiKey.trim(),
                engine = source.engine.trim(),
                maxResults = source.maxResults.coerceIn(1, 10)
            )
        }).distinctBy { it.id }
    }

    private fun normalizeBaseUrl(value: String): String = value.trim().let { clean ->
        if (clean.endsWith("://")) clean else clean.trimEnd('/')
    }

    companion object {
        fun defaultSources(): List<SearchSourceConfig> = listOf(
            SearchSourceConfig(
                id = "brave",
                type = SearchSourceType.BRAVE,
                name = "Brave Search"
            ),
            SearchSourceConfig(
                id = "tavily",
                type = SearchSourceType.TAVILY,
                name = "Tavily"
            ),
            SearchSourceConfig(
                id = "searxng",
                type = SearchSourceType.SEARXNG,
                name = "SearXNG"
            ),
            SearchSourceConfig(
                id = "custom",
                type = SearchSourceType.CUSTOM,
                name = "自定义兼容 API"
            )
        )
    }
}
