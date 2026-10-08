package com.miniichat.proactive.remote

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated codec for encrypted FCM character-message payloads.
 *
 * Pure JVM: no Android runtime, storage, network logging or side effects. Every
 * rejected input throws [IllegalArgumentException] with the single safe literal
 * [SAFE_MESSAGE]; payloads, keys and plaintext are never echoed or chained into
 * causes. Semantic validation of the decoded inner map happens elsewhere.
 */
object PushCipher {

    // --- Safety --------------------------------------------------------------
    const val SAFE_MESSAGE = "invalid_encrypted_push"

    // --- Outer wire contract -------------------------------------------------
    private const val SCHEMA_VERSION = "1"
    private const val TYPE = "encrypted_character_message"
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val KEY_TYPE = "type"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_CIPHERTEXT = "ciphertext"
    private val OUTER_KEYS =
        setOf(KEY_SCHEMA_VERSION, KEY_TYPE, KEY_DEVICE_ID, KEY_CIPHERTEXT)
    private val DEVICE_ID_REGEX = Regex("[A-Za-z0-9_-]{1,128}")

    // --- Crypto parameters ---------------------------------------------------
    private const val AAD_PREFIX = "maid-push:v1:"
    private const val AES_KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val MIN_BLOB_BYTES = NONCE_BYTES + TAG_BYTES

    // --- Named limits --------------------------------------------------------
    const val MAX_OUTER_BYTES = 3800L
    const val MAX_CIPHERTEXT_CHARS = 3800
    const val MAX_PLAINTEXT_BYTES = 3800
    const val MAX_INNER_KEY_CHARS = 64
    const val MAX_INNER_VALUE_BYTES = 3800

    private val json = Json
    private val secureRandom = SecureRandom()

    /**
     * Encrypts a string-only inner map into the exact 4-key outer envelope.
     */
    fun encrypt(payload: Map<String, String>, key: ByteArray, deviceId: String): Map<String, String> {
        requireKey(key)
        requireDeviceId(deviceId)

        val plaintext = encodeInner(payload)
        val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
        val aad = (AAD_PREFIX + deviceId).toByteArray(Charsets.UTF_8)
        val sealed = gcm(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext)

        val blob = ByteArray(nonce.size + sealed.size)
        System.arraycopy(nonce, 0, blob, 0, nonce.size)
        System.arraycopy(sealed, 0, blob, nonce.size, sealed.size)

        // Bound the outer envelope before allocating the base64 string.
        val overhead = (
            KEY_SCHEMA_VERSION.length + SCHEMA_VERSION.length +
                KEY_TYPE.length + TYPE.length +
                KEY_DEVICE_ID.length + deviceId.toByteArray(Charsets.UTF_8).size +
                KEY_CIPHERTEXT.length
            ).toLong()
        if (overhead + base64Length(blob.size) > MAX_OUTER_BYTES) fail()

        val ciphertext = Base64.getEncoder().encodeToString(blob)
        if (ciphertext.length > MAX_CIPHERTEXT_CHARS) fail()

        val outer = LinkedHashMap<String, String>(4)
        outer[KEY_SCHEMA_VERSION] = SCHEMA_VERSION
        outer[KEY_TYPE] = TYPE
        outer[KEY_DEVICE_ID] = deviceId
        outer[KEY_CIPHERTEXT] = ciphertext
        if (outerByteSize(outer) > MAX_OUTER_BYTES) fail()
        return outer
    }

    /**
     * Authenticates and decodes an outer envelope, returning the inner string map.
     */
    fun decrypt(data: Map<String, String>, key: ByteArray, expectedDeviceId: String): Map<String, String> {
        requireKey(key)
        requireDeviceId(expectedDeviceId)

        // Size + shape checks before any base64 allocation.
        if (outerByteSize(data) > MAX_OUTER_BYTES) fail()
        if (data.keys != OUTER_KEYS) fail()
        if (data[KEY_SCHEMA_VERSION] != SCHEMA_VERSION) fail()
        if (data[KEY_TYPE] != TYPE) fail()

        val deviceId = data[KEY_DEVICE_ID] ?: fail()
        if (deviceId != expectedDeviceId) fail()
        if (!DEVICE_ID_REGEX.matches(deviceId)) fail()

        val ciphertext = data[KEY_CIPHERTEXT] ?: fail()
        if (ciphertext.length > MAX_CIPHERTEXT_CHARS) fail()

        val blob = decodeBase64(ciphertext)
        if (blob.size < MIN_BLOB_BYTES) fail()

        val nonce = blob.copyOfRange(0, NONCE_BYTES)
        val sealed = blob.copyOfRange(NONCE_BYTES, blob.size)
        val aad = (AAD_PREFIX + deviceId).toByteArray(Charsets.UTF_8)
        val plaintext = gcm(Cipher.DECRYPT_MODE, key, nonce, aad, sealed)
        return decodeInner(plaintext)
    }

    // --- Validation helpers --------------------------------------------------

    private fun requireKey(key: ByteArray) {
        if (key.size != AES_KEY_BYTES) fail()
    }

    private fun requireDeviceId(deviceId: String) {
        if (!DEVICE_ID_REGEX.matches(deviceId)) fail()
    }

    private fun base64Length(byteCount: Int): Long = ((byteCount + 2) / 3).toLong() * 4L

    /** Sum of every key+value UTF-8 byte length, including unknown keys. */
    private fun outerByteSize(data: Map<String, String>): Long {
        var total = 0L
        for ((field, value) in data) {
            total += field.toByteArray(Charsets.UTF_8).size.toLong()
            total += value.toByteArray(Charsets.UTF_8).size.toLong()
            if (total > MAX_OUTER_BYTES) return total
        }
        return total
    }

    /** String-only JSON object, bounded by key length, value bytes and total bytes. */
    private fun encodeInner(payload: Map<String, String>): ByteArray {
        val fields = LinkedHashMap<String, JsonElement>(payload.size)
        var total = 0L
        for ((field, value) in payload) {
            if (field.length > MAX_INNER_KEY_CHARS) fail()
            val valueBytes = value.toByteArray(Charsets.UTF_8).size
            if (valueBytes > MAX_INNER_VALUE_BYTES) fail()
            total += valueBytes
            if (total > MAX_PLAINTEXT_BYTES) fail()
            fields[field] = JsonPrimitive(value)
        }
        val encoded = json.encodeToString(JsonObject.serializer(), JsonObject(fields))
        val bytes = encoded.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PLAINTEXT_BYTES) fail()
        return bytes
    }

    private fun decodeInner(plaintext: ByteArray): Map<String, String> {
        if (plaintext.size > MAX_PLAINTEXT_BYTES) fail()
        val text = decodeUtf8Strict(plaintext)

        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            fail()
        } catch (e: IllegalArgumentException) {
            fail()
        }
        val obj = element as? JsonObject ?: fail()

        val result = LinkedHashMap<String, String>(obj.size)
        for ((field, value) in obj) {
            if (field.length > MAX_INNER_KEY_CHARS) fail()
            val primitive = value as? JsonPrimitive ?: fail()
            if (!primitive.isString) fail()
            val decoded = primitive.content
            if (decoded.toByteArray(Charsets.UTF_8).size > MAX_INNER_VALUE_BYTES) fail()
            result[field] = decoded
        }
        return result
    }

    private fun decodeBase64(text: String): ByteArray = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        fail()
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            fail()
        }
    }

    private fun gcm(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray
    ): ByteArray = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        cipher.doFinal(input)
    } catch (e: GeneralSecurityException) {
        fail()
    } catch (e: IllegalArgumentException) {
        fail()
    }

    /** Rejects with a fixed literal, never a cause or nested payload text. */
    private fun fail(): Nothing = throw IllegalArgumentException(SAFE_MESSAGE)
}
