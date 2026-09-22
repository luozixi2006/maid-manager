package com.miniichat.memory

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class MemoryRepository(context: Context) {
    private val appContext=context.applicationContext
    private val database = MemoryDatabase(context)
    private val _memories = MutableStateFlow<List<LongTermMemory>>(emptyList())
    val memories: StateFlow<List<LongTermMemory>> = _memories.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _memories.value = database.queryAll()
    }

    suspend fun upsert(memory: LongTermMemory) = withContext(Dispatchers.IO) {
        val embedding=LocalEmbedding.embed(appContext,memory.content)
        database.upsert(memory.copy(embedding=embedding,embeddingModel=if(embedding.isEmpty())"" else LocalEmbedding.MODEL))
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

    suspend fun enabled(personaId: String, limit: Int = 50): List<LongTermMemory> = withContext(Dispatchers.IO) {
        require(personaId.isNotBlank())
        database.queryAll().asSequence().filter { it.personaId == personaId && it.enabled && it.status == "active" }.take(limit).toList()
    }

    suspend fun relevant(personaId: String, query: String): List<LongTermMemory> = withContext(Dispatchers.IO) {
        // Legacy/imported chats can have no persona. They remain usable without borrowing memories.
        if(personaId.isBlank())return@withContext emptyList()
        val vector=LocalEmbedding.embed(appContext,query,query=true)
        MemoryRetrieval.select(personaId,query,database.queryAll(),queryEmbedding=vector,embeddingModel=LocalEmbedding.MODEL).also { selected -> database.touch(selected.map { it.id },System.currentTimeMillis()) }
    }

    fun close() = database.close()
}
