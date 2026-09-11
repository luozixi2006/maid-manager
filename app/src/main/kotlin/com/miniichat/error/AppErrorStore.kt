package com.miniichat.error

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

private val Context.appErrorsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_errors")

class AppErrorStore(private val context: Context) {
    private val errorsKey = stringPreferencesKey("errors_json_v1")
    private val backupKey = stringPreferencesKey("errors_json_backup_v1")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val errors: Flow<List<AppError>> = context.appErrorsDataStore.data.map { preferences ->
        decode(preferences[errorsKey])
            ?: decode(preferences[backupKey])
            ?: emptyList()
    }

    suspend fun snapshot(): List<AppError> = errors.first()

    suspend fun record(error: AppError): AppError {
        val safe = PrivacySanitizer.sanitized(error)
        context.appErrorsDataStore.edit { preferences ->
            val current = decode(preferences[errorsKey])
                ?: decode(preferences[backupKey])
                ?: emptyList()
            write(preferences, ErrorRing.normalize(listOf(safe) + current))
        }
        return safe
    }

    suspend fun record(error: Throwable, context: AppErrorContext = AppErrorContext()): AppError =
        record(AppErrorClassifier.classify(error, context))

    suspend fun delete(id: String) {
        context.appErrorsDataStore.edit { preferences ->
            val current = decode(preferences[errorsKey])
                ?: decode(preferences[backupKey])
                ?: emptyList()
            write(preferences, current.filterNot { it.id == id }, keepPreviousSnapshot = false)
        }
    }

    suspend fun clear() {
        context.appErrorsDataStore.edit { preferences ->
            preferences.remove(errorsKey)
            preferences.remove(backupKey)
        }
    }

    private fun encode(errors: List<AppError>): String =
        json.encodeToString(ListSerializer(AppError.serializer()), errors)

    private fun write(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        errors: List<AppError>,
        keepPreviousSnapshot: Boolean = true
    ) {
        val encoded = encode(errors)
        if (keepPreviousSnapshot) {
            preferences[errorsKey]
                ?.takeIf { it.isNotBlank() && decode(it) != null }
                ?.let { preferences[backupKey] = it }
        } else {
            preferences[backupKey] = encoded
        }
        preferences[errorsKey] = encoded
    }

    private fun decode(raw: String?): List<AppError>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            ErrorRing.normalize(json.decodeFromString(ListSerializer(AppError.serializer()), raw))
        }.onFailure { error ->
            // Serialization messages may contain fragments from a damaged old record.
            Log.w(
                "MaidManagerErrors",
                "Error history decode failed (${error::class.java.name}); using local backup"
            )
        }.getOrNull()
    }
}
