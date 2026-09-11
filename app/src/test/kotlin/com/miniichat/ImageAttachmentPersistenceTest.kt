package com.miniichat

import com.miniichat.data.Attachment
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageAttachmentPersistenceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun legacyAttachmentStillLoadsWithGenerationDefaults() {
        val value = json.decodeFromString<Attachment>(
            """{"type":"image","uri":"/old/image.png","mimeType":"image/png","name":"image.png"}"""
        )

        assertEquals("", value.originalPrompt)
        assertEquals("", value.generationId)
        assertEquals(0, value.width)
    }

    @Test
    fun generatedImageMetadataSurvivesRoundTrip() {
        val original = Attachment(
            type = "image",
            uri = "/generated/image.png",
            mimeType = "image/png",
            name = "image.png",
            sizeBytes = 1234,
            originalPrompt = "原始提示词",
            effectivePrompt = "effective prompt",
            generationModel = "black-forest-labs/FLUX.2-klein-4B",
            width = 768,
            height = 768,
            seed = 42,
            generationId = "job-42"
        )

        assertEquals(original, json.decodeFromString<Attachment>(json.encodeToString(original)))
    }
}
