package com.miniichat.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object ApkVerifier {
    fun verify(context: Context, file: File, manifest: UpdateManifest) {
        if (!file.isFile || file.length() <= 0L) {
            throw UpdateException(UpdateFailureReason.APK_INTEGRITY_FAILED)
        }
        val expectedHash = manifest.sha256.trim().lowercase()
        if (!expectedHash.matches(Regex("[0-9a-f]{64}"))) {
            throw UpdateException(UpdateFailureReason.MANIFEST_INVALID)
        }
        val actualHash = runCatching { sha256(file) }.getOrElse {
            throw UpdateException(UpdateFailureReason.APK_INTEGRITY_FAILED, cause = it)
        }
        if (actualHash != expectedHash) {
            throw UpdateException(UpdateFailureReason.APK_INTEGRITY_FAILED)
        }

        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateException(UpdateFailureReason.APK_INTEGRITY_FAILED)
        if (candidate.packageName != context.packageName || manifest.applicationId != context.packageName) {
            throw UpdateException(UpdateFailureReason.APK_PACKAGE_MISMATCH)
        }
        if (candidate.longVersionCodeCompat() != manifest.versionCode) {
            throw UpdateException(UpdateFailureReason.APK_VERSION_MISMATCH)
        }
        if (candidate.versionName != manifest.versionName) {
            throw UpdateException(UpdateFailureReason.APK_VERSION_MISMATCH)
        }
        if (candidate.applicationInfo?.minSdkVersion != manifest.minSdk) {
            throw UpdateException(UpdateFailureReason.APK_VERSION_MISMATCH)
        }
        val current = context.packageManager.getPackageInfo(context.packageName, flags)
        if (candidate.longVersionCodeCompat() <= current.longVersionCodeCompat()) {
            throw UpdateException(UpdateFailureReason.APK_VERSION_MISMATCH)
        }
        val currentSigners = current.signerDigests()
        val candidateSigners = candidate.signerDigests()
        if (currentSigners.isEmpty() || candidateSigners.isEmpty() ||
            currentSigners != candidateSigners
        ) {
            throw UpdateException(UpdateFailureReason.APK_SIGNATURE_MISMATCH)
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun PackageInfo.longVersionCodeCompat(): Long = if (Build.VERSION.SDK_INT >= 28) {
        longVersionCode
    } else {
        @Suppress("DEPRECATION") versionCode.toLong()
    }

    private fun PackageInfo.signerDigests(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION") signatures.orEmpty()
        }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
