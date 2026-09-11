package com.miniichat.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private fun manifest(
        versionCode: Long = 15,
        versionName: String = "3.0.1",
        applicationId: String = "com.maidmanager.debug",
        minSdk: Int = 26,
        apkUrl: String = "https://github.com/example/maid/releases/download/v3.0.1/app.apk",
        sha256: String = "a".repeat(64)
    ) = UpdateManifest(
        versionCode = versionCode,
        versionName = versionName,
        applicationId = applicationId,
        minSdk = minSdk,
        apkUrl = apkUrl,
        sha256 = sha256
    )

    @Test
    fun validManifestAndVersionComparisonAreAccepted() {
        val update = manifest()
        UpdatePolicy.validateManifest(update, "com.maidmanager.debug", 34)
        assertTrue(UpdatePolicy.isNewer(update, 14))
        assertFalse(UpdatePolicy.isNewer(update, 15))
    }

    @Test(expected = UpdateException::class)
    fun cleartextApkUrlIsRejected() {
        UpdatePolicy.validateManifest(
            manifest(apkUrl = "http://github.com/example/maid/app.apk"),
            "com.maidmanager.debug",
            34
        )
    }

    @Test(expected = UpdateException::class)
    fun mismatchedApplicationIdIsRejected() {
        UpdatePolicy.validateManifest(manifest(), "com.maidmanager", 34)
    }

    @Test(expected = UpdateException::class)
    fun pathLikeVersionNameIsRejected() {
        UpdatePolicy.validateManifest(
            manifest(versionName = "../../other"),
            "com.maidmanager.debug",
            34
        )
    }

    @Test(expected = UpdateException::class)
    fun malformedHashIsRejected() {
        UpdatePolicy.validateManifest(
            manifest(sha256 = "not-a-hash"),
            "com.maidmanager.debug",
            34
        )
    }

    @Test(expected = UpdateException::class)
    fun embeddedCredentialsInUrlAreRejected() {
        UpdatePolicy.validateManifest(
            manifest(apkUrl = "https://user:password@github.com/example/app.apk"),
            "com.maidmanager.debug",
            34
        )
    }

    @Test(expected = UpdateException::class)
    fun unsupportedHttpsPortIsRejected() {
        UpdatePolicy.validateManifest(
            manifest(apkUrl = "https://github.com:8443/example/app.apk"),
            "com.maidmanager.debug",
            34
        )
    }

    @Test
    fun boundGitHubReleaseAssetIsAccepted() {
        UpdatePolicy.requireGitHubReleaseAssetUrl(
            "https://github.com/example/maid/releases/download/v3.0.1/app.apk",
            "example",
            "maid"
        )
    }

    @Test(expected = UpdateException::class)
    fun releaseAssetFromAnotherRepositoryIsRejected() {
        UpdatePolicy.requireGitHubReleaseAssetUrl(
            "https://github.com/attacker/maid/releases/download/v3.0.1/app.apk",
            "example",
            "maid"
        )
    }

    @Test
    fun manifestAndIdentityFailuresExposeStableReasons() {
        val malformedManifest = captureUpdateFailure {
            UpdatePolicy.validateManifest(
                manifest(sha256 = "not-a-hash"),
                "com.maidmanager.debug",
                34
            )
        }
        val wrongPackage = captureUpdateFailure {
            UpdatePolicy.validateManifest(manifest(), "com.maidmanager", 34)
        }

        assertEquals(UpdateFailureReason.MANIFEST_INVALID, malformedManifest.reason)
        assertEquals(UpdateFailureReason.APK_PACKAGE_MISMATCH, wrongPackage.reason)
    }

    private fun captureUpdateFailure(block: () -> Unit): UpdateException = try {
        block()
        throw AssertionError("Expected UpdateException")
    } catch (error: UpdateException) {
        error
    }
}
