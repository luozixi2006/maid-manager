package com.miniichat.data

import android.content.Context
import android.util.Log
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

private val Context.conversationsDataStore: DataStore<Preferences> by preferencesDataStore(name = "conversations")

class ConversationStore(private val context: Context) {

    private val key = stringPreferencesKey("conversations_json")
    private val backupKey = stringPreferencesKey("conversations_json_backup")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Conversation.serializer())

    val conversationsFlow: Flow<List<Conversation>> =
        context.conversationsDataStore.data.map { prefs ->
            prefs[key]?.let(::decode)
                ?: prefs[backupKey]?.let(::decode)
                ?: emptyList()
        }

    suspend fun snapshot(): List<Conversation> = conversationsFlow.first()

    suspend fun save(list: List<Conversation>) {
        context.conversationsDataStore.edit { prefs ->
            write(prefs, list.sortedByDescending { it.updatedAt })
        }
    }

    suspend fun upsert(conv: Conversation) {
        context.conversationsDataStore.edit { prefs ->
            val current = read(prefs).toMutableList()
            val index = current.indexOfFirst { it.id == conv.id }
            if (index >= 0) current[index] = conv else current.add(conv)
            write(prefs, current.sortedByDescending { it.updatedAt })
        }
    }

    suspend fun delete(id: String) {
        context.conversationsDataStore.edit { prefs ->
            val current = read(prefs)
            if (current.none { it.id == id }) return@edit
            write(prefs, current.filterNot { it.id == id }, keepPreviousSnapshot = false)
        }
    }

    /** Appends after re-reading inside DataStore's serialized edit transaction. */
    suspend fun appendMessage(conversationId: String, message: Message): Conversation? {
        var updated: Conversation? = null
        context.conversationsDataStore.edit { prefs ->
            val current = read(prefs).toMutableList()
            val index = current.indexOfFirst { it.id == conversationId }
            if (index < 0) return@edit
            val conversation = current[index].copy(
                messages = current[index].messages + message,
                updatedAt = System.currentTimeMillis()
            )
            current[index] = conversation
            write(prefs, current.sortedByDescending { it.updatedAt })
            updated = conversation
        }
        return updated
    }

    /**
     * Mutates one conversation inside DataStore's serialized transaction. This is the only safe
     * path for streaming updates because a background proactive message may arrive concurrently.
     */
    suspend fun updateConversation(
        id: String,
        createIfMissing: (() -> Conversation)? = null,
        keepPreviousSnapshot: Boolean = true,
        transform: (Conversation) -> Conversation
    ): Conversation? {
        var updated: Conversation? = null
        context.conversationsDataStore.edit { prefs ->
            val current = read(prefs).toMutableList()
            val index = current.indexOfFirst { it.id == id }
            val original = if (index >= 0) current[index] else createIfMissing?.invoke() ?: return@edit
            val next = transform(original)
            if (index >= 0) current[index] = next else current.add(next)
            write(
                prefs,
                current.sortedByDescending { it.updatedAt },
                keepPreviousSnapshot = keepPreviousSnapshot
            )
            updated = next
        }
        return updated
    }

    suspend fun rename(id: String, newTitle: String) {
        updateConversation(id) {
            it.copy(title = newTitle, updatedAt = System.currentTimeMillis())
        }
    }

    suspend fun recoverInterruptedMessages(startupCutoff: Long) {
        context.conversationsDataStore.edit { prefs ->
            val current = read(prefs)
            val recovered = current.map { it.recoverInterruptedTurn(startupCutoff) }
            if (recovered != current) {
                write(prefs, recovered, keepPreviousSnapshot = false)
            }
        }
    }

    private fun read(prefs: Preferences): List<Conversation> =
        prefs[key]?.let(::decode)
            ?: prefs[backupKey]?.let(::decode)
            ?: emptyList()

    private fun write(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        list: List<Conversation>,
        keepPreviousSnapshot: Boolean = true
    ) {
        val encoded = json.encodeToString(serializer, list)
        if (keepPreviousSnapshot) {
            prefs[key]
                ?.takeIf { it.isNotBlank() && decode(it) != null }
                ?.let { prefs[backupKey] = it }
        } else {
            // Explicit deletion/edit must not survive in the recovery copy and later reappear.
            prefs[backupKey] = encoded
        }
        prefs[key] = encoded
    }

    private fun decode(raw: String): List<Conversation>? = runCatching {
        json.decodeFromString<List<Conversation>>(raw)
    }.onFailure { error ->
        // Serialization messages may contain fragments of the private conversation JSON.
        Log.e(
            "MaidManagerData",
            "Conversation JSON decode failed (${error::class.java.name}); trying local backup"
        )
    }.getOrNull()
}
