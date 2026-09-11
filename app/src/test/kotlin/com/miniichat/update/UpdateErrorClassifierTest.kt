package com.miniichat.update

import com.miniichat.error.AppErrorClassifier
import com.miniichat.error.AppErrorContext
import com.miniichat.error.AppErrorType
import com.miniichat.error.ErrorArea
import com.miniichat.error.ErrorOperation
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateErrorClassifierTest {

    private val context = AppErrorContext(
        area = ErrorArea.UPDATE,
        operation = ErrorOperation.DOWNLOAD_UPDATE
    )

    @Test
    fun `update failures map to distinct diagnostic types`() {
        val expected = mapOf(
            UpdateFailureReason.MANIFEST_INVALID to AppErrorType.UPDATE_MANIFEST_INVALID,
            UpdateFailureReason.DOWNLOAD_NETWORK_FAILED to AppErrorType.UPDATE_DOWNLOAD_FAILED,
            UpdateFailureReason.DOWNLOAD_STORAGE_FAILED to AppErrorType.LOCAL_STORAGE_FAILED,
            UpdateFailureReason.APK_INTEGRITY_FAILED to AppErrorType.UPDATE_INTEGRITY_FAILED,
            UpdateFailureReason.APK_PACKAGE_MISMATCH to AppErrorType.UPDATE_PACKAGE_MISMATCH,
            UpdateFailureReason.APK_VERSION_MISMATCH to AppErrorType.UPDATE_VERSION_MISMATCH,
            UpdateFailureReason.APK_SIGNATURE_MISMATCH to AppErrorType.UPDATE_SIGNATURE_MISMATCH,
            UpdateFailureReason.INSTALL_LAUNCH_FAILED to AppErrorType.UPDATE_INSTALL_FAILED
        )

        expected.forEach { (reason, type) ->
            val classified = AppErrorClassifier.classify(UpdateException(reason), context)
            assertEquals(reason.name, type, classified.type)
            assertTrue(reason.name, classified.explanation.isNotBlank())
            assertTrue(reason.name, classified.suggestions.isNotEmpty())
        }
    }

    @Test
    fun `download HTTP status is retained without retaining URL or response details`() {
        val error = UpdateException(
            reason = UpdateFailureReason.DOWNLOAD_NETWORK_FAILED,
            httpStatus = 503,
            cause = IOException(
                "https://github.com/private/release.apk?token=TOP_SECRET returned body PRIVATE_BODY"
            )
        )

        val classified = AppErrorClassifier.classify(error, context)

        assertEquals(AppErrorType.UPDATE_DOWNLOAD_FAILED, classified.type)
        assertEquals(503, classified.httpStatus)
        assertFalse(classified.toString().contains("TOP_SECRET"))
        assertFalse(classified.toString().contains("PRIVATE_BODY"))
        assertFalse(classified.toString().contains("release.apk"))
        assertEquals(
            listOf(UpdateException::class.java.name, IOException::class.java.name),
            classified.causeChain
        )
    }
}
