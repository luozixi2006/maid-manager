package com.miniichat.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.miniichat.image.FLUX2_KLEIN_MODEL_ID

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val activeProviderId: String = "deepseek",
    val activeModel: String = "deepseek-v4-flash",
    val activeAssistantId: String = "default",
    val systemPrompt: String = "You are a helpful assistant.",
    val temperature: Float = 0.7f,
    val stream: Boolean = true,
    val language: String = "system",          // system | en | zh
    val dynamicColor: Boolean = true,
    val themeMode: String = "system",         // system | light | dark
    val ttsProvider: String = "Custom HTTP TTS",
    val ttsBaseUrl: String = "",
    val ttsApiKey: String = "",
    val ttsModel: String = "",
    val ttsVoiceId: String = "",
    val ttsAutoRead: Boolean = false,
    val memoryEnabled: Boolean = true,
    val autoMemoryEnabled: Boolean = true,
    val searchProvider: String = "Custom Search API",
    val searchBaseUrl: String = "",
    val searchApiKey: String = "",
    val searchEngine: String = "",
    val webEnabled: Boolean = false,
    val reasoningEnabled: Boolean = false,
    val reasoningEffort: String = "high",
    val imageGenerationEnabled: Boolean = false,
    val imageGenerationBaseUrl: String = "",
    val imageGenerationModel: String = FLUX2_KLEIN_MODEL_ID,
    val imageGenerationWidth: Int = 768,
    val imageGenerationHeight: Int = 768,
    val imageGenerationSteps: Int = 4,
    val imageGenerationGuidance: Float = 1.0f,
    val imageGenerationTimeoutSeconds: Int = 600,
    val proactiveMessagesEnabled: Boolean = false,
    val proactiveFrequency: String = "occasional",
    val proactiveDndStartMinutes: Int = 22 * 60,
    val proactiveDndEndMinutes: Int = 8 * 60,
    val lastProactiveMessageAt: Long = 0L
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val PROVIDER = stringPreferencesKey("active_provider_id")
        val MODEL = stringPreferencesKey("active_model")
        val ASSISTANT = stringPreferencesKey("active_assistant_id")
        val SYSTEM = stringPreferencesKey("system_prompt")
        val TEMP = floatPreferencesKey("temperature")
        val STREAM = booleanPreferencesKey("stream")
        val LANG = stringPreferencesKey("language")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val THEME = stringPreferencesKey("theme_mode")
        val TTS_PROVIDER = stringPreferencesKey("tts_provider")
        val TTS_BASE_URL = stringPreferencesKey("tts_base_url")
        val TTS_API_KEY = stringPreferencesKey("tts_api_key")
        val TTS_MODEL = stringPreferencesKey("tts_model")
        val TTS_VOICE = stringPreferencesKey("tts_voice_id")
        val TTS_AUTO = booleanPreferencesKey("tts_auto_read")
        val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        val AUTO_MEMORY = booleanPreferencesKey("auto_memory_enabled")
        val SEARCH_PROVIDER = stringPreferencesKey("search_provider")
        val SEARCH_BASE_URL = stringPreferencesKey("search_base_url")
        val SEARCH_API_KEY = stringPreferencesKey("search_api_key")
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val WEB_ENABLED = booleanPreferencesKey("web_enabled")
        val REASONING_ENABLED = booleanPreferencesKey("reasoning_enabled")
        val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val IMAGE_ENABLED = booleanPreferencesKey("image_generation_enabled")
        val IMAGE_BASE_URL = stringPreferencesKey("image_generation_base_url")
        val IMAGE_MODEL = stringPreferencesKey("image_generation_model")
        val IMAGE_WIDTH = intPreferencesKey("image_generation_width")
        val IMAGE_HEIGHT = intPreferencesKey("image_generation_height")
        val IMAGE_STEPS = intPreferencesKey("image_generation_steps")
        val IMAGE_GUIDANCE = floatPreferencesKey("image_generation_guidance")
        val IMAGE_TIMEOUT = intPreferencesKey("image_generation_timeout_seconds")
        val PROACTIVE_ENABLED = booleanPreferencesKey("proactive_messages_enabled")
        val PROACTIVE_FREQUENCY = stringPreferencesKey("proactive_frequency")
        val PROACTIVE_DND_START = intPreferencesKey("proactive_dnd_start_minutes")
        val PROACTIVE_DND_END = intPreferencesKey("proactive_dnd_end_minutes")
        val LAST_PROACTIVE_AT = longPreferencesKey("last_proactive_message_at")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p -> read(p) }

    private fun read(p: Preferences) = AppSettings(
        activeProviderId = p[Keys.PROVIDER] ?: "deepseek",
        activeModel = p[Keys.MODEL] ?: "deepseek-v4-flash",
        activeAssistantId = p[Keys.ASSISTANT] ?: "default",
        systemPrompt = p[Keys.SYSTEM] ?: "You are a helpful assistant.",
        temperature = p[Keys.TEMP] ?: 0.7f,
        stream = p[Keys.STREAM] ?: true,
        language = p[Keys.LANG] ?: "system",
        dynamicColor = p[Keys.DYNAMIC] ?: true,
        themeMode = p[Keys.THEME] ?: "system",
        ttsProvider = p[Keys.TTS_PROVIDER] ?: "Custom HTTP TTS",
        ttsBaseUrl = p[Keys.TTS_BASE_URL] ?: "",
        ttsApiKey = p[Keys.TTS_API_KEY] ?: "",
        ttsModel = p[Keys.TTS_MODEL] ?: "",
        ttsVoiceId = p[Keys.TTS_VOICE] ?: "",
        ttsAutoRead = p[Keys.TTS_AUTO] ?: false,
        memoryEnabled = p[Keys.MEMORY_ENABLED] ?: true,
        autoMemoryEnabled = p[Keys.AUTO_MEMORY] ?: true,
        searchProvider = p[Keys.SEARCH_PROVIDER] ?: "Custom Search API",
        searchBaseUrl = p[Keys.SEARCH_BASE_URL] ?: "",
        searchApiKey = p[Keys.SEARCH_API_KEY] ?: "",
        searchEngine = p[Keys.SEARCH_ENGINE] ?: "",
        webEnabled = p[Keys.WEB_ENABLED] ?: false,
        reasoningEnabled = p[Keys.REASONING_ENABLED] ?: false,
        reasoningEffort = p[Keys.REASONING_EFFORT] ?: "high",
        imageGenerationEnabled = p[Keys.IMAGE_ENABLED] ?: false,
        imageGenerationBaseUrl = p[Keys.IMAGE_BASE_URL] ?: "",
        imageGenerationModel = p[Keys.IMAGE_MODEL] ?: FLUX2_KLEIN_MODEL_ID,
        imageGenerationWidth = p[Keys.IMAGE_WIDTH] ?: 768,
        imageGenerationHeight = p[Keys.IMAGE_HEIGHT] ?: 768,
        imageGenerationSteps = p[Keys.IMAGE_STEPS] ?: 4,
        imageGenerationGuidance = p[Keys.IMAGE_GUIDANCE] ?: 1.0f,
        imageGenerationTimeoutSeconds = p[Keys.IMAGE_TIMEOUT] ?: 600,
        proactiveMessagesEnabled = p[Keys.PROACTIVE_ENABLED] ?: false,
        proactiveFrequency = p[Keys.PROACTIVE_FREQUENCY] ?: "occasional",
        proactiveDndStartMinutes = p[Keys.PROACTIVE_DND_START] ?: 22 * 60,
        proactiveDndEndMinutes = p[Keys.PROACTIVE_DND_END] ?: 8 * 60,
        lastProactiveMessageAt = p[Keys.LAST_PROACTIVE_AT] ?: 0L
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { p ->
            val next = transform(read(p))
            p[Keys.PROVIDER] = next.activeProviderId
            p[Keys.MODEL] = next.activeModel
            p[Keys.ASSISTANT] = next.activeAssistantId
            p[Keys.SYSTEM] = next.systemPrompt
            p[Keys.TEMP] = next.temperature
            p[Keys.STREAM] = next.stream
            p[Keys.LANG] = next.language
            p[Keys.DYNAMIC] = next.dynamicColor
            p[Keys.THEME] = next.themeMode
            p[Keys.TTS_PROVIDER] = next.ttsProvider
            p[Keys.TTS_BASE_URL] = next.ttsBaseUrl
            p[Keys.TTS_API_KEY] = next.ttsApiKey
            p[Keys.TTS_MODEL] = next.ttsModel
            p[Keys.TTS_VOICE] = next.ttsVoiceId
            p[Keys.TTS_AUTO] = next.ttsAutoRead
            p[Keys.MEMORY_ENABLED] = next.memoryEnabled
            p[Keys.AUTO_MEMORY] = next.autoMemoryEnabled
            p[Keys.SEARCH_PROVIDER] = next.searchProvider
            p[Keys.SEARCH_BASE_URL] = next.searchBaseUrl
            p[Keys.SEARCH_API_KEY] = next.searchApiKey
            p[Keys.SEARCH_ENGINE] = next.searchEngine
            p[Keys.WEB_ENABLED] = next.webEnabled
            p[Keys.REASONING_ENABLED] = next.reasoningEnabled
            p[Keys.REASONING_EFFORT] = next.reasoningEffort
            p[Keys.IMAGE_ENABLED] = next.imageGenerationEnabled
            p[Keys.IMAGE_BASE_URL] = next.imageGenerationBaseUrl
            p[Keys.IMAGE_MODEL] = next.imageGenerationModel
            p[Keys.IMAGE_WIDTH] = next.imageGenerationWidth
            p[Keys.IMAGE_HEIGHT] = next.imageGenerationHeight
            p[Keys.IMAGE_STEPS] = next.imageGenerationSteps
            p[Keys.IMAGE_GUIDANCE] = next.imageGenerationGuidance
            p[Keys.IMAGE_TIMEOUT] = next.imageGenerationTimeoutSeconds
            p[Keys.PROACTIVE_ENABLED] = next.proactiveMessagesEnabled
            p[Keys.PROACTIVE_FREQUENCY] = next.proactiveFrequency
            p[Keys.PROACTIVE_DND_START] = next.proactiveDndStartMinutes
            p[Keys.PROACTIVE_DND_END] = next.proactiveDndEndMinutes
            p[Keys.LAST_PROACTIVE_AT] = next.lastProactiveMessageAt
        }
    }
}
