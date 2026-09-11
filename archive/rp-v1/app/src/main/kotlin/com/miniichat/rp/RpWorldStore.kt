package com.miniichat.rp

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

private val Context.rpWorldDataStore: DataStore<Preferences> by preferencesDataStore(name = "rp_worlds")

class RpWorldStore(private val context: Context) {
    private val worldsKey = stringPreferencesKey("worlds_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val worldsFlow: Flow<List<RpWorld>> = context.rpWorldDataStore.data.map { preferences ->
        val raw = preferences[worldsKey] ?: return@map emptyList()
        runCatching { json.decodeFromString(ListSerializer(RpWorld.serializer()), raw) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.updatedAt }
    }

    suspend fun snapshot(): List<RpWorld> = worldsFlow.first()

    suspend fun save(worlds: List<RpWorld>) {
        context.rpWorldDataStore.edit { preferences ->
            preferences[worldsKey] = json.encodeToString(
                ListSerializer(RpWorld.serializer()),
                worlds.sortedByDescending { it.updatedAt }
            )
        }
    }

    suspend fun upsert(world: RpWorld) {
        val worlds = snapshot().toMutableList()
        val index = worlds.indexOfFirst { it.id == world.id }
        if (index >= 0) worlds[index] = world else worlds.add(world)
        save(worlds)
    }

    suspend fun delete(worldId: String) {
        save(snapshot().filterNot { it.id == worldId })
    }
}
