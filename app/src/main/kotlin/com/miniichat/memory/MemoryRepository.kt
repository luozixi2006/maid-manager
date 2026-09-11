package com.miniichat.memory

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class MemoryRepository(context: Context) {
    private val database = MemoryDatabase(context)
    private val _memories = MutableStateFlow<List<LongTermMemory>>(emptyList())
    val memories: StateFlow<List<LongTermMemory>> = _memories.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _memories.value = database.queryAll()
    }

    suspend fun upsert(memory: LongTermMemory) = withContext(Dispatchers.IO) {
        database.upsert(memory)
        _memories.value = database.queryAll()
    }

    suspend fun importAll(memories: List<LongTermMemory>) = withContext(Dispatchers.IO) {
        database.upsertAll(memories)
        _memories.value = database.queryAll()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        database.delete(id)
        _memories.value = database.queryAll()
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        database.setEnabled(id, enabled)
        _memories.value = database.queryAll()
    }

    suspend fun enabled(limit: Int = 50): List<LongTermMemory> = withContext(Dispatchers.IO) {
        database.queryAll().asSequence().filter { it.enabled }.take(limit).toList()
    }

    fun close() = database.close()
}
