package com.miniichat.proactive.remote

/** Parsed, validated representation of an incoming FCM character-message payload. */
data class PushEnvelope(
    val messageId: String,
    val conversationId: String,
    val personaId: String,
    val content: String,
    val createdAt: Long,
    val modelId: String,
    val providerId: String
)

sealed interface PushParseResult {
    data class Accepted(val envelope: PushEnvelope) : PushParseResult
    data class Rejected(val reason: String) : PushParseResult
}

/**
 * Pure, side-effect-free parser for a remote character-message data payload.
 * Rejections use short stable codes and never echo payload values.
 */
object PushEnvelopeParser {

    // --- Wire contract -------------------------------------------------------
    private const val SUPPORTED_SCHEMA_VERSION = "1"
    private const val SUPPORTED_TYPE = "character_message"

    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val KEY_TYPE = "type"
    private const val KEY_MESSAGE_ID = "message_id"
    private const val KEY_CONVERSATION_ID = "conversation_id"
    private const val KEY_PERSONA_ID = "persona_id"
    private const val KEY_CONTENT = "content"
    private const val KEY_CREATED_AT = "created_at"
    private const val KEY_MODEL_ID = "model_id"
    private const val KEY_PROVIDER_ID = "provider_id"

    // --- Named limits --------------------------------------------------------
    const val MAX_IDENTITY_LENGTH = 128
    const val MAX_CONTENT_CODEPOINTS = 1200
    const val MAX_METADATA_LENGTH = 200
    const val MAX_PAYLOAD_BYTES = 3800L
    const val MAX_CLOCK_SKEW_FUTURE_MS = 5L * 60L * 1000L
    const val MAX_AGE_PAST_MS = 28L * 24L * 60L * 60L * 1000L

    // --- Stable rejection codes ---------------------------------------------
    const val REASON_UNSUPPORTED_SCHEMA = "unsupported_schema"
    const val REASON_UNSUPPORTED_TYPE = "unsupported_type"
    const val REASON_INVALID_IDENTITY = "invalid_identity"
    const val REASON_INVALID_CONTENT = "invalid_content"
    const val REASON_INVALID_TIMESTAMP = "invalid_timestamp"
    const val REASON_INVALID_METADATA = "invalid_metadata"
    const val REASON_PAYLOAD_TOO_LARGE = "payload_too_large"
    const val REASON_INVALID_CLOCK = "invalid_clock"

    fun parse(data: Map<String, String>, now: Long, allowArchived: Boolean = false): PushParseResult {
        if (now < 0L) return PushParseResult.Rejected(REASON_INVALID_CLOCK)

        val schemaVersion = data[KEY_SCHEMA_VERSION]
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return PushParseResult.Rejected(REASON_UNSUPPORTED_SCHEMA)
        }
        if (data[KEY_TYPE] != SUPPORTED_TYPE) {
            return PushParseResult.Rejected(REASON_UNSUPPORTED_TYPE)
        }

        val messageId = data[KEY_MESSAGE_ID] ?: return rejectIdentity()
        if (!isValidIdentity(messageId)) return rejectIdentity()
        val conversationId = data[KEY_CONVERSATION_ID] ?: return rejectIdentity()
        if (!isValidIdentity(conversationId)) return rejectIdentity()
        val personaId = data[KEY_PERSONA_ID] ?: return rejectIdentity()
        if (!isValidIdentity(personaId)) return rejectIdentity()

        val content = data[KEY_CONTENT] ?: return PushParseResult.Rejected(REASON_INVALID_CONTENT)
        if (!isValidContent(content)) return PushParseResult.Rejected(REASON_INVALID_CONTENT)

        val rawCreatedAt = data[KEY_CREATED_AT]
            ?: return PushParseResult.Rejected(REASON_INVALID_TIMESTAMP)
        val createdAt = parseCreatedAt(rawCreatedAt, now, allowArchived)
            ?: return PushParseResult.Rejected(REASON_INVALID_TIMESTAMP)

        val modelId = data[KEY_MODEL_ID].orEmpty()
        if (!isValidMetadata(modelId)) return PushParseResult.Rejected(REASON_INVALID_METADATA)
        val providerId = data[KEY_PROVIDER_ID].orEmpty()
        if (!isValidMetadata(providerId)) return PushParseResult.Rejected(REASON_INVALID_METADATA)

        if (payloadByteSize(data) > MAX_PAYLOAD_BYTES) {
            return PushParseResult.Rejected(REASON_PAYLOAD_TOO_LARGE)
        }

        return PushParseResult.Accepted(
            PushEnvelope(
                messageId = messageId,
                conversationId = conversationId,
                personaId = personaId,
                content = content,
                createdAt = createdAt,
                modelId = modelId,
                providerId = providerId
            )
        )
    }

    private fun rejectIdentity(): PushParseResult.Rejected =
        PushParseResult.Rejected(REASON_INVALID_IDENTITY)

    private fun isValidIdentity(value: String): Boolean {
        if (value.isEmpty()) return false
        if (value.length > MAX_IDENTITY_LENGTH) return false
        if (value.first().isWhitespace() || value.last().isWhitespace()) return false
        for (c in value) {
            if (isIsoControl(c)) return false
        }
        return true
    }

    private fun isValidContent(value: String): Boolean {
        if (value.isBlank()) return false
        if (value.codePointCount(0, value.length) > MAX_CONTENT_CODEPOINTS) return false
        for (c in value) {
            if (isIsoControl(c) && c != '\n' && c != '\r' && c != '\t') return false
        }
        return true
    }

    private fun isValidMetadata(value: String): Boolean {
        if (value.isEmpty()) return true
        if (value.length > MAX_METADATA_LENGTH) return false
        if (value.first().isWhitespace() || value.last().isWhitespace()) return false
        for (c in value) {
            if (isIsoControl(c)) return false
        }
        return true
    }

    /** ISO control characters: U+0000..U+001F and U+007F..U+009F. */
    private fun isIsoControl(c: Char): Boolean =
        c.code < 0x20 || (c.code in 0x7F..0x9F)

    /**
     * Strictly decimal positive milliseconds with no sign; must fall within
     * [now - 28 days, now + 5 minutes], using overflow-safe comparisons.
     */
    private fun parseCreatedAt(raw: String, now: Long, allowArchived: Boolean): Long? {
        if (raw.isEmpty()) return null
        for (c in raw) {
            if (c < '0' || c > '9') return null
        }
        val value = raw.toLongOrNull() ?: return null
        if (value <= 0L) return null
        val upperLimit = if (now > Long.MAX_VALUE - MAX_CLOCK_SKEW_FUTURE_MS) {
            Long.MAX_VALUE
        } else {
            now + MAX_CLOCK_SKEW_FUTURE_MS
        }
        if (value > upperLimit) return null
        val lowerLimit = if (now < MAX_AGE_PAST_MS) 0L else now - MAX_AGE_PAST_MS
        if (!allowArchived && value < lowerLimit) return null
        return value
    }

    /** Sum of every key+value UTF-8 byte length, including unknown keys. */
    private fun payloadByteSize(data: Map<String, String>): Long {
        var total = 0L
        for ((key, value) in data) {
            total += key.toByteArray(Charsets.UTF_8).size.toLong()
            total += value.toByteArray(Charsets.UTF_8).size.toLong()
            if (total > MAX_PAYLOAD_BYTES) return total
        }
        return total
    }
}
