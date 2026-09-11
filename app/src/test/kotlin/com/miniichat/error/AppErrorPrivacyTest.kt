package com.miniichat.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppErrorPrivacyTest {

    @Test
    fun `url sanitizer discards credentials path query and fragment`() {
        assertEquals(
            "https://api.example.com:8443",
            PrivacySanitizer.originOnly(
                "https://user:password@api.example.com:8443/v1/chat?api_key=SECRET#PRIVATE_PROMPT"
            )
        )
    }

    @Test
    fun `export applies defense in depth redaction and includes schema statistics`() {
        val deliberatelyUnsafe = AppError(
            occurredAt = 42,
            area = ErrorArea.CHAT,
            operation = ErrorOperation.SEND_MESSAGE,
            type = AppErrorType.UNEXPECTED,
            title = "Authorization: Bearer sk-super-secret-token",
            userMessage = "api_key=VERY_SECRET",
            explanation = "password=hunter2",
            suggestions = listOf("use Bearer hf_abcdefghijk"),
            providerHost = "api.example.com",
            modelId = "model-safe",
            causeChain = listOf("java.lang.IllegalStateException: PRIVATE_CHAT_BODY")
        )

        val output = AppErrorExport.encode(listOf(deliberatelyUnsafe), generatedAt = 100)

        assertTrue(output.contains(AppErrorExportDocument.SCHEMA))
        assertTrue(output.contains("\"schemaVersion\": 1"))
        assertTrue(output.contains("\"total\": 1"))
        assertTrue(output.contains("model-safe"))
        assertFalse(output.contains("super-secret"))
        assertFalse(output.contains("VERY_SECRET"))
        assertFalse(output.contains("hunter2"))
        assertFalse(output.contains("abcdefghijk"))
        assertFalse(output.contains("PRIVATE_CHAT_BODY"))
        assertEquals("model-safe", AppErrorExport.decodeErrors(output)?.single()?.modelId)
    }

    @Test
    fun `ring keeps newest one hundred unique errors`() {
        val errors = (0 until 130).map { index ->
            AppError(
                id = "ERR-$index",
                occurredAt = index.toLong(),
                title = "错误 $index",
                userMessage = "失败",
                explanation = "原因"
            )
        } + AppError(
            id = "ERR-129",
            occurredAt = 1_000,
            title = "重复",
            userMessage = "失败",
            explanation = "原因"
        )

        val result = ErrorRing.normalize(errors)

        assertEquals(ErrorRing.MAX_ENTRIES, result.size)
        assertEquals(result.size, result.map { it.id }.distinct().size)
        assertEquals("ERR-129", result.first().id)
        assertEquals(30L, result.last().occurredAt)
    }

    @Test
    fun `identifier sanitizer removes common key shapes`() {
        val value = PrivacySanitizer.safeIdentifier("model sk-abcdefghijk api_key=secret-value")!!
        assertFalse(value.contains("abcdefghijk"))
        assertFalse(value.contains("secret-value"))
        assertTrue(value.contains("[已隐藏]"))
    }
}
