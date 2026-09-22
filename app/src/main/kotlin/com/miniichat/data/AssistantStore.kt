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

private val Context.assistantsDataStore: DataStore<Preferences> by preferencesDataStore(name = "assistants")

class AssistantStore(private val context: Context) {
    private val key = stringPreferencesKey("assistants_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val assistantsFlow: Flow<List<Assistant>> =
        context.assistantsDataStore.data.map { prefs ->
            val raw = prefs[key]
            if (raw.isNullOrBlank()) AssistantPresets.defaults()
            else runCatching {
                json.decodeFromString(ListSerializer(Assistant.serializer()), raw)
            }.getOrDefault(AssistantPresets.defaults())
        }

    suspend fun snapshot(): List<Assistant> = assistantsFlow.first()

    suspend fun save(list: List<Assistant>) {
        context.assistantsDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(ListSerializer(Assistant.serializer()), list)
        }
    }

    suspend fun upsert(a: Assistant) {
        transformAll { list -> if (list.any { it.id == a.id }) list.map { if (it.id == a.id) a else it } else list + a }
    }

    suspend fun update(id: String, transform: (Assistant) -> Assistant) =
        transformAll { list -> list.map { if (it.id == id) transform(it) else it } }

    suspend fun transformAll(transform: (List<Assistant>) -> List<Assistant>) {
        context.assistantsDataStore.edit { prefs ->
            val current = prefs[key]?.let { json.decodeFromString(ListSerializer(Assistant.serializer()), it) }
                ?: AssistantPresets.defaults()
            prefs[key] = json.encodeToString(ListSerializer(Assistant.serializer()), transform(current))
        }
    }

    suspend fun delete(id: String) {
        transformAll { it.filterNot { assistant -> assistant.id == id } }
    }
}
