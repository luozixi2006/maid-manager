package com.miniichat.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.miniichat.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateRepository(private val context: Context) {
    private val client = GitHubReleaseClient()

    suspend fun check(): UpdateUiState = withContext(Dispatchers.IO) {
        val owner = BuildConfig.UPDATE_GITHUB_OWNER
        val repo = BuildConfig.UPDATE_GITHUB_REPO
        if (owner.isBlank() || repo.isBlank()) {
            return@withContext UpdateUiState.NotConfigured("项目仓库尚未绑定，完成首次 GitHub 发布后即可检查更新。")
        }
        val manifest = client.latestManifest(owner, repo)
        UpdatePolicy.validateManifest(manifest, context.packageName, Build.VERSION.SDK_INT)
        val currentCode = context.packageManager.getPackageInfo(context.packageName, 0)
            .let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }
        if (UpdatePolicy.isNewer(manifest, currentCode)) {
            UpdateUiState.Available(manifest)
        } else {
            UpdateUiState.Current(BuildConfig.VERSION_NAME)
        }
    }

    suspend fun download(manifest: UpdateManifest, onProgress: (Int?) -> Unit): File =
        withContext(Dispatchers.IO) {
            UpdatePolicy.validateManifest(manifest, context.packageName, Build.VERSION.SDK_INT)
            val directory = File(context.cacheDir, "updates")
            val target = File(directory, "maid-manager-${manifest.versionCode}.apk")
            client.download(manifest.apkUrl, target, onProgress)
            ApkVerifier.verify(context, target, manifest)
            target
        }

    suspend fun verifyBeforeInstall(file: File, manifest: UpdateManifest) {
        withContext(Dispatchers.IO) {
            UpdatePolicy.validateManifest(manifest, context.packageName, Build.VERSION.SDK_INT)
            ApkVerifier.verify(context, file, manifest)
        }
    }

    fun installVerified(file: File): InstallLaunchResult {
        return try {
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                InstallLaunchResult.PermissionRequired
            } else {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    clipData = ClipData.newRawUri("verified-update", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                InstallLaunchResult.Started
            }
        } catch (error: Exception) {
            if (error is UpdateException) throw error
            throw UpdateException(UpdateFailureReason.INSTALL_LAUNCH_FAILED, cause = error)
        }
    }

    fun close() = client.close()
}

enum class InstallLaunchResult { Started, PermissionRequired }
