package com.miniichat.update

import java.net.URI

internal object UpdatePolicy {
    private val slugPattern = Regex("[A-Za-z0-9_.-]+")
    private val versionNamePattern = Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}")
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")

    fun requireSlug(value: String, label: String) {
        if (!slugPattern.matches(value) || value == "." || value == "..") {
            throw UpdateException(UpdateFailureReason.MANIFEST_INVALID)
        }
    }

    fun requireHttpsUrl(value: String, label: String = "更新地址"): URI {
        val uri = runCatching { URI(value) }.getOrNull()
        if (
            uri == null ||
            !uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() ||
            uri.userInfo != null ||
            uri.fragment != null ||
            (uri.port != -1 && uri.port != 443)
        ) {
            throw UpdateException(UpdateFailureReason.MANIFEST_INVALID)
        }
        return uri
    }

    fun requireGitHubReleaseAssetUrl(value: String, owner: String, repository: String) {
        val uri = requireHttpsUrl(value, "GitHub Release 资源地址")
        val expectedPrefix = "/$owner/$repository/releases/download/"
        if (
            !uri.host.equals("github.com", ignoreCase = true) ||
            !uri.path.startsWith(expectedPrefix, ignoreCase = true)
        ) {
            throw UpdateException(UpdateFailureReason.MANIFEST_INVALID)
        }
    }

    fun validateManifest(
        manifest: UpdateManifest,
        expectedApplicationId: String,
        deviceSdk: Int
    ) {
        if (manifest.schemaVersion != 1) {
            throw UpdateException(UpdateFailureReason.MANIFEST_INVALID)
        }
        if (manifest.applicationId != expectedApplicationId) {
            throw UpdateException(UpdateFailureReason.APK_PACKAGE_MISMATCH)
        }
        if (manifest.versionCode !in 1..2_100_000_000L) {
            throw UpdateException(UpdateFailureReason.MANIFEST_INVALID)
        }
        if (!versionNamePattern.matches(manifest.versionName)) {
            throw UpdateException(UpdateFailureReason.MANIFEST_INVALID)
        }
        if (manifest.minSdk !in 1..deviceSdk) {
            throw UpdateException(UpdateFailureReason.APK_VERSION_MISMATCH)
        }
        if (!sha256Pattern.matches(manifest.sha256.trim())) {
            throw UpdateException(UpdateFailureReason.MANIFEST_INVALID)
        }
        requireHttpsUrl(manifest.apkUrl, "APK 下载地址")
    }

    fun isNewer(manifest: UpdateManifest, currentVersionCode: Long): Boolean =
        manifest.versionCode > currentVersionCode
}
