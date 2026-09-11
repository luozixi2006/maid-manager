package com.miniichat.error

import com.miniichat.api.LlmEmptyResponseException
import com.miniichat.api.LlmHttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AppErrorClassifierTest {

    @Test
    fun `http errors have distinct explanations and retain only safe metadata`() {
        val context = AppErrorContext(
            area = ErrorArea.CHAT,
            operation = ErrorOperation.SEND_MESSAGE,
            providerBaseUrl = "https://user:secret@api.example.com/v1/chat?api_key=TOP_SECRET#private",
            modelId = "model-a"
        )

        val auth = AppErrorClassifier.classify(
            LlmHttpException(401, "server echoed sk-PRIVATE_SECRET and the complete prompt"),
            context
        )

        assertEquals(AppErrorType.AUTHENTICATION_FAILED, auth.type)
        assertEquals(401, auth.httpStatus)
        assertEquals("api.example.com", auth.providerHost)
        assertEquals("model-a", auth.modelId)
        val persisted = auth.toString()
        assertFalse(persisted.contains("TOP_SECRET"))
        assertFalse(persisted.contains("PRIVATE_SECRET"))
        assertFalse(persisted.contains("complete prompt"))
    }

    @Test
    fun `network failures are not all mislabeled as timeout`() {
        assertEquals(
            AppErrorType.REQUEST_TIMEOUT,
            AppErrorClassifier.classify(SocketTimeoutException()).type
        )
        assertEquals(
            AppErrorType.HOST_NOT_FOUND,
            AppErrorClassifier.classify(UnknownHostException()).type
        )
        assertEquals(
            AppErrorType.NETWORK_UNAVAILABLE,
            AppErrorClassifier.classify(IOException()).type
        )
    }

    @Test
    fun `classifier follows a wrapped cause chain without saving messages`() {
        val error = IllegalStateException(
            "outer message contains PRIVATE_CHAT_TEXT",
            SocketTimeoutException("inner query contains input=PRIVATE_PROMPT")
        )
        val result = AppErrorClassifier.classify(error)

        assertEquals(AppErrorType.REQUEST_TIMEOUT, result.type)
        assertEquals(
            listOf(IllegalStateException::class.java.name, SocketTimeoutException::class.java.name),
            result.causeChain
        )
        assertFalse(result.toString().contains("PRIVATE_CHAT_TEXT"))
        assertFalse(result.toString().contains("PRIVATE_PROMPT"))
    }

    @Test
    fun `empty response has its own actionable type`() {
        val result = AppErrorClassifier.classify(LlmEmptyResponseException())
        assertEquals(AppErrorType.EMPTY_RESPONSE, result.type)
        assertTrue(result.explanation.isNotBlank())
        assertTrue(result.suggestions.isNotEmpty())
    }

    @Test
    fun `invalid provider address is recognized without persisting it`() {
        val result = AppErrorClassifier.classify(
            IllegalArgumentException("bad URL includes api_key=PRIVATE"),
            AppErrorContext(providerBaseUrl = "not a valid url")
        )
        assertEquals(AppErrorType.INVALID_ADDRESS, result.type)
        assertNull(result.providerHost)
        assertFalse(result.toString().contains("PRIVATE"))
    }
}
