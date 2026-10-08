package com.miniichat.proactive.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushEnvelopeTest {
    @Test fun onlyAuthenticatedCatchupAllowsArchivedMessages() {
        val data = baseData().apply { this["created_at"] = "1000" }
        assertTrue(PushEnvelopeParser.parse(data, now) is PushParseResult.Rejected)
        assertTrue(PushEnvelopeParser.parse(data, now, allowArchived = true) is PushParseResult.Accepted)
        data["created_at"] = (now + 600_000).toString()
        assertTrue(PushEnvelopeParser.parse(data, now, allowArchived = true) is PushParseResult.Rejected)
    }

    private val now = 1_000_000_000_000L

    private fun baseData(): MutableMap<String, String> = mutableMapOf(
        "schema_version" to "1",
        "type" to "character_message",
        "message_id" to "msg-1",
        "conversation_id" to "conv-1",
        "persona_id" to "persona-1",
        "content" to "hello",
        "created_at" to "999999999000"
    )

    private fun parse(data: Map<String, String> = baseData()): PushParseResult =
        PushEnvelopeParser.parse(data, now)

    private fun rejectedReason(result: PushParseResult): String {
        assertTrue("expected Rejected but was $result", result is PushParseResult.Rejected)
        return (result as PushParseResult.Rejected).reason
    }

    private fun acceptedEnvelope(result: PushParseResult): PushEnvelope {
        assertTrue("expected Accepted but was $result", result is PushParseResult.Accepted)
        return (result as PushParseResult.Accepted).envelope
    }

    @Test
    fun validChineseMultilineContentIsRetained() {
        val content = "你好，世界\n第二行\r\n第三行\t结束"
        val data = baseData().apply { this["content"] = content }
        val envelope = acceptedEnvelope(parse(data))
        assertEquals(content, envelope.content)
        assertEquals("msg-1", envelope.messageId)
        assertEquals("conv-1", envelope.conversationId)
        assertEquals("persona-1", envelope.personaId)
        assertEquals(999_999_999_000L, envelope.createdAt)
    }

    @Test
    fun arbitraryNonUuidIdsAreAccepted() {
        val data = baseData().apply {
            this["message_id"] = "本地消息-001"
            this["conversation_id"] = "会话:2024/10/08"
            this["persona_id"] = "imported.persona@v2"
        }
        val envelope = acceptedEnvelope(parse(data))
        assertEquals("本地消息-001", envelope.messageId)
        assertEquals("会话:2024/10/08", envelope.conversationId)
        assertEquals("imported.persona@v2", envelope.personaId)
    }

    @Test
    fun eachRequiredFieldMissingIsRejected() {
        val expected = mapOf(
            "schema_version" to "unsupported_schema",
            "type" to "unsupported_type",
            "message_id" to "invalid_identity",
            "conversation_id" to "invalid_identity",
            "persona_id" to "invalid_identity",
            "content" to "invalid_content",
            "created_at" to "invalid_timestamp"
        )
        for ((key, reason) in expected) {
            val data = baseData().apply { remove(key) }
            assertEquals("missing $key", reason, rejectedReason(parse(data)))
        }
    }

    @Test
    fun unsupportedSchemaIsRejected() {
        assertEquals(
            "unsupported_schema",
            rejectedReason(parse(baseData().apply { this["schema_version"] = "2" }))
        )
        assertEquals(
            "unsupported_schema",
            rejectedReason(parse(baseData().apply { this["schema_version"] = " 1" }))
        )
    }

    @Test
    fun unsupportedTypeIsRejected() {
        assertEquals(
            "unsupported_type",
            rejectedReason(parse(baseData().apply { this["type"] = "other" }))
        )
        assertEquals(
            "unsupported_type",
            rejectedReason(parse(baseData().apply { this["type"] = "character_message " }))
        )
    }

    @Test
    fun invalidIdentitiesAreRejected() {
        val invalid = listOf("", "   ", " lead", "trail ", "a\u0000b", "a\u007fb", "x".repeat(129))
        for (value in invalid) {
            val data = baseData().apply { this["persona_id"] = value }
            assertEquals("id=$value", "invalid_identity", rejectedReason(parse(data)))
        }
        val atLimit = baseData().apply { this["persona_id"] = "x".repeat(128) }
        assertEquals(128, acceptedEnvelope(parse(atLimit)).personaId.length)
    }

    @Test
    fun invalidContentIsRejected() {
        val invalid = listOf("", "  ", "\n\t ", "ok\u0000", "ok\u0007", "ok\u009c")
        for (value in invalid) {
            val data = baseData().apply { this["content"] = value }
            assertEquals("content=$value", "invalid_content", rejectedReason(parse(data)))
        }
    }

    @Test
    fun contentCodepointLimitHandlesEmoji() {
        val atLimit = "a".repeat(1196) + "😀".repeat(4) // 1200 codepoints, more UTF-16 chars
        val envelope = acceptedEnvelope(parse(baseData().apply { this["content"] = atLimit }))
        assertEquals(atLimit, envelope.content)

        val overLimit = "a".repeat(1197) + "😀".repeat(4) // 1201 codepoints
        assertEquals(
            "invalid_content",
            rejectedReason(parse(baseData().apply { this["content"] = overLimit }))
        )
    }

    @Test
    fun malformedTimestampsAreRejected() {
        val malformed = listOf("", "abc", "-1", "+1", "1.0", " 1", "1 ", "0", "9".repeat(30))
        for (value in malformed) {
            val data = baseData().apply { this["created_at"] = value }
            assertEquals("created_at=$value", "invalid_timestamp", rejectedReason(parse(data)))
        }
    }

    @Test
    fun timestampBoundsAreEnforced() {
        val skew = 5L * 60L * 1000L
        val day = 24L * 60L * 60L * 1000L
        acceptedEnvelope(parse(baseData().apply { this["created_at"] = (now + skew).toString() }))
        acceptedEnvelope(parse(baseData().apply { this["created_at"] = (now - 28 * day).toString() }))
        assertEquals(
            "invalid_timestamp",
            rejectedReason(parse(baseData().apply { this["created_at"] = (now + skew + 1).toString() }))
        )
        assertEquals(
            "invalid_timestamp",
            rejectedReason(parse(baseData().apply { this["created_at"] = (now - 28 * day - 1).toString() }))
        )
    }

    @Test
    fun optionalMetadataDefaultsAndLimits() {
        val defaults = acceptedEnvelope(parse(baseData()))
        assertEquals("", defaults.modelId)
        assertEquals("", defaults.providerId)

        acceptedEnvelope(parse(baseData().apply { this["model_id"] = "" }))
        acceptedEnvelope(parse(baseData().apply {
            this["model_id"] = "gpt-4o"
            this["provider_id"] = "openai"
        }))

        assertEquals(
            "invalid_metadata",
            rejectedReason(parse(baseData().apply { this["model_id"] = "x".repeat(201) }))
        )
        assertEquals(
            "invalid_metadata",
            rejectedReason(parse(baseData().apply { this["provider_id"] = " oops" }))
        )
        assertEquals(
            "invalid_metadata",
            rejectedReason(parse(baseData().apply { this["model_id"] = "bad\u0001id" }))
        )
    }

    @Test
    fun unknownFieldsAreIgnoredButCountTowardPayloadSize() {
        acceptedEnvelope(parse(baseData().apply { this["future_field"] = "ok" }))
        val oversized = baseData().apply { this["future_field"] = "x".repeat(4000) }
        assertEquals("payload_too_large", rejectedReason(parse(oversized)))
    }

    @Test
    fun invalidClockIsRejected() {
        assertEquals("invalid_clock", rejectedReason(PushEnvelopeParser.parse(baseData(), -1L)))
    }

    @Test
    fun inputMapIsNotMutated() {
        val acceptedInput = baseData().apply {
            this["content"] = "你好"
            this["unknown"] = "value"
        }
        val acceptedSnapshot = LinkedHashMap(acceptedInput)
        parse(acceptedInput)
        assertEquals(acceptedSnapshot, acceptedInput)

        val rejectedInput = baseData().apply { remove("content") }
        val rejectedSnapshot = LinkedHashMap(rejectedInput)
        parse(rejectedInput)
        assertEquals(rejectedSnapshot, rejectedInput)
    }
}
