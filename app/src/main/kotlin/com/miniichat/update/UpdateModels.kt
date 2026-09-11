package com.miniichat.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateManifest(
    val schemaVersion: Int = 1,
    val versionCode: Long,
    val versionName: String,
    val applicationId: String,
    val minSdk: Int = 26,
    val apkUrl: String,
    val sha256: String,
    val changelog: String = "",
    val publishedAt: String = ""
)

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
    @SerialName("published_at") val publishedAt: String? = null
)

@Serializable
internal data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String
)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Current(val versionName: String) : UpdateUiState
    data class Available(val manifest: UpdateManifest) : UpdateUiState
    data class Downloading(val manifest: UpdateManifest, val percent: Int?) : UpdateUiState
    data class Ready(val manifest: UpdateManifest, val apkPath: String) : UpdateUiState
    data class Failed(val title: String, val explanation: String) : UpdateUiState
    data class NotConfigured(val explanation: String) : UpdateUiState
}

/**
 * Stable, privacy-safe update failure categories.
 *
 * The exception deliberately does not accept arbitrary messages. Network
 * libraries can include complete URLs (including query strings) in their
 * messages, so only this enum, an optional HTTP status, and exception class
 * names may reach diagnostics.
 */
enum class UpdateFailureReason {
    MANIFEST_INVALID,
    DOWNLOAD_NETWORK_FAILED,
    DOWNLOAD_STORAGE_FAILED,
    APK_INTEGRITY_FAILED,
    APK_PACKAGE_MISMATCH,
    APK_VERSION_MISMATCH,
    APK_SIGNATURE_MISMATCH,
    INSTALL_LAUNCH_FAILED
}

class UpdateException(
    val reason: UpdateFailureReason,
    val httpStatus: Int? = null,
    cause: Throwable? = null
) : Exception(reason.name, cause)
