package com.miniichat.data

import kotlinx.serialization.Serializable

@Serializable
enum class ProviderAuthMode {
    BEARER,
    NONE
}

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<String> = emptyList(),
    val customHeaders: Map<String, String> = emptyMap(),
    val extraBody: Map<String, String> = emptyMap(),
    val authMode: ProviderAuthMode = ProviderAuthMode.BEARER,
    val enabled: Boolean = true,
    val allowFallback: Boolean = true,
    val fallbackModels: List<String> = emptyList(),
    val priority: Int = 100,
    val createdAt: Long = System.currentTimeMillis()
)

object ProviderPresets {
    data class Preset(
        val name: String,
        val baseUrl: String,
        val sampleModel: String,
        val hint: String
    )

    val all: List<Preset> = listOf(
        Preset(
            "DeepSeek",
            "https://api.deepseek.com",
            "deepseek-v4-flash",
            "DeepSeek 官方 OpenAI Compatible API"
        ),
        Preset("自定义", "https://", "", "任意 OpenAI Compatible API")
    )
}
