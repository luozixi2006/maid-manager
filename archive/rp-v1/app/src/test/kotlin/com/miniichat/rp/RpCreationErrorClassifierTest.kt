package com.miniichat.rp

import com.miniichat.api.LlmHttpException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpCreationErrorClassifierTest {
    private fun classify(error: Throwable, failures: Int = 0) =
        RpCreationErrorClassifier.classify(error, "测试服务", "test-model", failures)

    @Test fun noNetworkIsNotReportedAsTimeout() {
        val result = classify(UnknownHostException("dns failed"))
        assertEquals(RpCreationErrorKind.NETWORK, result.kind)
        assertTrue(result.message.contains("无法连接"))
    }

    @Test fun unauthorizedIsApiKeyError() {
        assertEquals(
            RpCreationErrorKind.AUTH,
            classify(LlmHttpException(401, "unauthorized")).kind
        )
    }

    @Test fun missingApiKeyGoesDirectlyToAiSettings() {
        val result = classify(RpCreationMissingKeyException())
        assertEquals(RpCreationErrorKind.AUTH, result.kind)
        assertEquals(RpCreationRecovery.AI_SETTINGS, result.primaryAction)
    }

    @Test fun balanceErrorIsDetectedFromProviderBody() {
        assertEquals(
            RpCreationErrorKind.BALANCE,
            classify(LlmHttpException(403, "insufficient balance")).kind
        )
    }

    @Test fun missingModelHasRefreshAction() {
        val result = classify(LlmHttpException(404, "model not found"))
        assertEquals(RpCreationErrorKind.MODEL_NOT_FOUND, result.kind)
        assertEquals(RpCreationRecovery.REFRESH_MODELS, result.primaryAction)
    }

    @Test fun unavailableModelIsSeparateFromConnectionFailure() {
        assertEquals(
            RpCreationErrorKind.MODEL_UNAVAILABLE,
            classify(LlmHttpException(503, "no available instance")).kind
        )
    }

    @Test fun rateLimitPreservesRetryAfterWithoutKeyData() {
        val result = classify(LlmHttpException(429, "rate limited", "12"))
        assertEquals(RpCreationErrorKind.RATE_LIMIT, result.kind)
        assertTrue(result.detail.contains("Retry-After=12"))
    }

    @Test fun realSocketTimeoutGetsLongResponseMessage() {
        val result = classify(SocketTimeoutException("timeout"))
        assertEquals(RpCreationErrorKind.TIMEOUT, result.kind)
        assertTrue(result.message.contains("响应时间过长"))
    }

    @Test fun invalidJsonGetsFormatError() {
        assertEquals(
            RpCreationErrorKind.FORMAT,
            classify(RpCreationFormatException(100, false)).kind
        )
    }

    @Test fun truncatedJsonGetsOutputIncompleteError() {
        assertEquals(
            RpCreationErrorKind.OUTPUT_TRUNCATED,
            classify(RpCreationFormatException(100, true)).kind
        )
    }

    @Test fun repeatedFormatFailuresSuggestChangingModel() {
        val result = classify(RpCreationFormatException(100, false), failures = 2)
        assertEquals(RpCreationErrorKind.MODEL_UNSUITABLE, result.kind)
        assertEquals(RpCreationRecovery.CHANGE_MODEL, result.primaryAction)
    }
}
