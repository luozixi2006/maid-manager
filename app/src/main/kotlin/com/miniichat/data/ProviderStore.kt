package com.miniichat.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.providersDataStore: DataStore<Preferences> by preferencesDataStore(name = "providers")

class ProviderStore(private val context: Context) {
    private val key = stringPreferencesKey("providers_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(ProviderConfig.serializer())

    val providersFlow: Flow<List<ProviderConfig>> =
        context.providersDataStore.data.map { prefs ->
            val raw = prefs[key] ?: return@map listOf(defaultProvider())
            runCatching { json.decodeFromString(serializer, raw) }
                .getOrDefault(listOf(defaultProvider()))
        }

    private fun defaultProvider() = ProviderConfig(
        id = "deepseek",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        apiKey = "",
        models = listOf("deepseek-v4-flash", "deepseek-v4-pro")
    )

    suspend fun snapshot(): List<ProviderConfig> = providersFlow.first()

    suspend fun save(list: List<ProviderConfig>) {
        context.providersDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(serializer, list)
        }
    }

    suspend fun upsert(p: ProviderConfig) {
        context.providersDataStore.edit { prefs ->
            val list = read(prefs).toMutableList()
            val index = list.indexOfFirst { it.id == p.id }
            if (index >= 0) list[index] = p else list.add(p)
            prefs[key] = json.encodeToString(serializer, list)
        }
    }

    suspend fun delete(id: String) {
        context.providersDataStore.edit { prefs ->
            val list = read(prefs)
            if (list.none { it.id == id }) return@edit
            prefs[key] = json.encodeToString(serializer, list.filterNot { it.id == id })
        }
    }

    suspend fun update(id: String, transform: (ProviderConfig) -> ProviderConfig): ProviderConfig? {
        var updated: ProviderConfig? = null
        context.providersDataStore.edit { prefs ->
            val list = read(prefs).toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return@edit
            transform(list[index]).let { next ->
                list[index] = next
                updated = next
            }
            prefs[key] = json.encodeToString(serializer, list)
        }
        return updated
    }

    private fun read(prefs: Preferences): List<ProviderConfig> = prefs[key]?.let { raw ->
        runCatching { json.decodeFromString(serializer, raw) }.getOrNull()
    } ?: listOf(defaultProvider())
}
