package com.miniichat.tts

data class TtsConfig(
    val provider: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val voiceId: String
)

interface TtsProvider {
    suspend fun synthesize(config: TtsConfig, text: String): ByteArray
}
