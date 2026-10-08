package com.miniichat.proactive.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PushCipherTest {

    private val key = ByteArray(32) { (it + 1).toByte() }
    private val deviceId = "device-01_AB"

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Builds a genuinely authenticated outer envelope for arbitrary inner bytes. */
    private fun seal(
        plaintext: ByteArray,
        device: String = deviceId,
        keyBytes: ByteArray = key,
        nonce: ByteArray = ByteArray(12) { (it + 7).toByte() }
    ): Map<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(("maid-push:v1:" + device).toByteArray(StandardCharsets.UTF_8))
        val sealed = cipher.doFinal(plaintext)
        val blob = ByteArray(nonce.size + sealed.size)
        System.arraycopy(nonce, 0, blob, 0, nonce.size)
        System.arraycopy(sealed, 0, blob, nonce.size, sealed.size)
        return mapOf(
            "schema_version" to "1",
            "type" to "encrypted_character_message",
            "device_id" to device,
            "ciphertext" to b64(blob)
        )
    }

    private inline fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("invalid_encrypted_push", e.message)
        }
    }

    @Test
    fun roundTripUnicodeAndNewlines() {
        val payload = mapOf(
            "title" to "café \uD83D\uDE80 中文",
            "body" to "line1\nline2\r\n\t\"quoted\" \\ slash"
        )
        val out = PushCipher.encrypt(payload, key, deviceId)

        assertEquals(setOf("schema_version", "type", "device_id", "ciphertext"), out.keys)
        assertEquals("1", out["schema_version"])
        assertEquals("encrypted_character_message", out["type"])
        assertEquals(deviceId, out["device_id"])
        assertEquals(payload, PushCipher.decrypt(out, key, deviceId))
    }

    @Test
    fun emptyPayloadRoundTrip() {
        val out = PushCipher.encrypt(emptyMap(), key, deviceId)
        assertEquals(emptyMap<String, String>(), PushCipher.decrypt(out, key, deviceId))
    }

    @Test
    fun decryptsPythonCryptographyFixture() {
        // Public deterministic test key/nonce only. Produced with cryptography AESGCM on Python.
        val outer = mapOf(
            "schema_version" to "1", "type" to "encrypted_character_message",
            "device_id" to deviceId,
            "ciphertext" to "AAECAwQFBgcICQoLChJi+KapBGV7CYhjgCVXaIwq4fuZJN2bduWVPNYlraIEip7CKuCKUlY0anLbU5d0QXHN2ve5IZS+qr6CdirPt4yEbteP5QhMeg=="
        )
        assertEquals(mapOf("content" to "你好\n继续聊🙂", "message_id" to "m-test"), PushCipher.decrypt(outer, key, deviceId))
    }

    @Test
    fun changingRoutingHeaderCannotBypassAuthenticatedDeviceBinding() {
        val out = PushCipher.encrypt(mapOf("a" to "b"), key, deviceId)
        assertInvalid { PushCipher.decrypt(out + ("device_id" to "forged-device"), key, "forged-device") }
    }

    @Test
    fun independentNonceEachEncryption() {
        val payload = mapOf("a" to "b")
        val first = PushCipher.encrypt(payload, key, deviceId)
        val second = PushCipher.encrypt(payload, key, deviceId)
        assertNotEquals(first["ciphertext"], second["ciphertext"])
        assertEquals(payload, PushCipher.decrypt(first, key, deviceId))
        assertEquals(payload, PushCipher.decrypt(second, key, deviceId))
    }

    @Test
    fun tamperedCiphertextAndTagFail() {
        val out = PushCipher.encrypt(mapOf("a" to "b"), key, deviceId)
        val raw = Base64.getDecoder().decode(out["ciphertext"])

        val flippedTag = raw.copyOf()
        flippedTag[flippedTag.size - 1] = (flippedTag.last().toInt() xor 1).toByte()
        assertInvalid { PushCipher.decrypt(out + ("ciphertext" to b64(flippedTag)), key, deviceId) }

        val flippedBody = raw.copyOf()
        flippedBody[13] = (flippedBody[13].toInt() xor 1).toByte()
        assertInvalid { PushCipher.decrypt(out + ("ciphertext" to b64(flippedBody)), key, deviceId) }
    }

    @Test
    fun wrongKeyOrDeviceFails() {
        val out = PushCipher.encrypt(mapOf("a" to "b"), key, deviceId)
        assertInvalid { PushCipher.decrypt(out, ByteArray(32) { 9 }, deviceId) }
        assertInvalid { PushCipher.decrypt(out, key, "other-device") }
        assertInvalid { PushCipher.decrypt(seal("{\"a\":\"b\"}".toByteArray()), key, "other-device") }
    }

    @Test
    fun unknownSchemaTypeAndKeysRejected() {
        val base = PushCipher.encrypt(mapOf("a" to "b"), key, deviceId)
        assertInvalid { PushCipher.decrypt(base + ("schema_version" to "2"), key, deviceId) }
        assertInvalid { PushCipher.decrypt(base + ("type" to "character_message"), key, deviceId) }
        assertInvalid { PushCipher.decrypt(base + ("extra" to "x"), key, deviceId) }
        assertInvalid { PushCipher.decrypt(base - "device_id", key, deviceId) }
    }

    @Test
    fun malformedAndShortBase64Rejected() {
        val base = PushCipher.encrypt(mapOf("a" to "b"), key, deviceId)
        assertInvalid { PushCipher.decrypt(base + ("ciphertext" to "not base64!!"), key, deviceId) }
        assertInvalid { PushCipher.decrypt(base + ("ciphertext" to "AAAA"), key, deviceId) }
        assertInvalid { PushCipher.decrypt(base + ("ciphertext" to b64(ByteArray(10))), key, deviceId) }
    }

    @Test
    fun invalidKeySizeRejected() {
        assertInvalid { PushCipher.encrypt(mapOf("a" to "b"), ByteArray(16), deviceId) }
        assertInvalid { PushCipher.encrypt(mapOf("a" to "b"), ByteArray(0), deviceId) }
        val out = PushCipher.encrypt(mapOf("a" to "b"), key, deviceId)
        assertInvalid { PushCipher.decrypt(out, ByteArray(31), deviceId) }
    }

    @Test
    fun invalidDeviceIdRejected() {
        assertInvalid { PushCipher.encrypt(mapOf("a" to "b"), key, "") }
        assertInvalid { PushCipher.encrypt(mapOf("a" to "b"), key, "bad id") }
        assertInvalid { PushCipher.encrypt(mapOf("a" to "b"), key, "a".repeat(129)) }
    }

    @Test
    fun oversizedOuterRejected() {
        val big = "x".repeat(PushCipher.MAX_INNER_VALUE_BYTES)
        assertInvalid { PushCipher.encrypt(mapOf("k" to big), key, deviceId) }

        val base = PushCipher.encrypt(mapOf("a" to "b"), key, deviceId)
        val padded = base + ("pad" to "y".repeat(PushCipher.MAX_OUTER_BYTES.toInt()))
        assertInvalid { PushCipher.decrypt(padded, key, deviceId) }
    }

    @Test
    fun oversizedPlaintextRejected() {
        val json = "{\"k\":\"" + "y".repeat(3800) + "\"}"
        assertTrue(json.toByteArray(StandardCharsets.UTF_8).size > PushCipher.MAX_PLAINTEXT_BYTES)
        assertInvalid { PushCipher.decrypt(seal(json.toByteArray()), key, deviceId) }
    }

    @Test
    fun innerNonStringValuesRejected() {
        assertInvalid { PushCipher.decrypt(seal("{\"a\":1}".toByteArray()), key, deviceId) }
        assertInvalid { PushCipher.decrypt(seal("{\"a\":null}".toByteArray()), key, deviceId) }
        assertInvalid { PushCipher.decrypt(seal("{\"a\":true}".toByteArray()), key, deviceId) }
        assertInvalid { PushCipher.decrypt(seal("{\"a\":{\"b\":\"c\"}}".toByteArray()), key, deviceId) }
        assertInvalid { PushCipher.decrypt(seal("{\"a\":[\"b\"]}".toByteArray()), key, deviceId) }
        assertInvalid { PushCipher.decrypt(seal("[1,2]".toByteArray()), key, deviceId) }
        assertInvalid { PushCipher.decrypt(seal("\"plain\"".toByteArray()), key, deviceId) }
    }

    @Test
    fun malformedInnerJsonRejected() {
        assertInvalid { PushCipher.decrypt(seal("{\"a\":".toByteArray()), key, deviceId) }
        assertInvalid { PushCipher.decrypt(seal("not json".toByteArray()), key, deviceId) }
    }

    @Test
    fun invalidUtf8Rejected() {
        assertInvalid { PushCipher.decrypt(seal(byteArrayOf(0x7B, 0xFF.toByte(), 0x7D)), key, deviceId) }
    }

    @Test
    fun innerOverlongKeyRejected() {
        val field = "k".repeat(PushCipher.MAX_INNER_KEY_CHARS + 1)
        assertInvalid { PushCipher.encrypt(mapOf(field to "v"), key, deviceId) }
        assertInvalid { PushCipher.decrypt(seal(("{\"" + field + "\":\"v\"}").toByteArray()), key, deviceId) }
    }
}
