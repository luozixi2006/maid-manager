package com.miniichat.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.nio.file.FileSystemException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

class GitHubReleaseClient(private val client: HttpClient = updateHttpClient()) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latestManifest(owner: String, repository: String): UpdateManifest = try {
        fetchLatestManifest(owner, repository)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: UpdateException) {
        throw error
    } catch (error: Exception) {
        throw UpdateException(UpdateFailureReason.DOWNLOAD_NETWORK_FAILED, cause = error)
    }

    private suspend fun fetchLatestManifest(owner: String, repository: String): UpdateManifest {
        UpdatePolicy.requireSlug(owner, "GitHub owner")
        UpdatePolicy.requireSlug(repository, "GitHub repository")
        val releaseUrl = "https://api.github.com/repos/$owner/$repository/releases/latest"
        val releaseText = boundedMetadata(releaseUrl, MAX_RELEASE_BYTES)
        val release = runCatching {
            json.decodeFromString(GitHubRelease.serializer(), releaseText)
        }.getOrElse {
            throw UpdateException(UpdateFailureReason.MANIFEST_INVALID, cause = it)
        }
        val asset = release.assets.firstOrNull { it.name == "update-manifest.json" }
            ?: throw UpdateException(UpdateFailureReason.MANIFEST_INVALID)
        UpdatePolicy.requireGitHubReleaseAssetUrl(asset.downloadUrl, owner, repository)
        val manifestText = boundedMetadata(asset.downloadUrl, MAX_MANIFEST_BYTES)
        val manifest = runCatching {
            json.decodeFromString(UpdateManifest.serializer(), manifestText)
        }.getOrElse {
            throw UpdateException(UpdateFailureReason.MANIFEST_INVALID, cause = it)
        }
        UpdatePolicy.requireGitHubReleaseAssetUrl(manifest.apkUrl, owner, repository)
        return manifest
    }

    private suspend fun boundedMetadata(url: String, limit: Int): String =
        client.prepareGet(url) { githubHeaders() }.execute { response ->
            try {
                UpdatePolicy.requireHttpsUrl(response.call.request.url.toString(), "更新信息重定向地址")
                if (!response.status.isSuccess()) {
                    throw UpdateException(UpdateFailureReason.DOWNLOAD_NETWORK_FAILED, response.status.value)
                }
                val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (length != null && (length < 0 || length > limit)) {
                    throw UpdateException(UpdateFailureReason.MANIFEST_INVALID)
                }
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val output = ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = channel.readAvailable(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > limit) {
                        throw UpdateException(UpdateFailureReason.MANIFEST_INVALID)
                    }
                    output.write(buffer, 0, count)
                }
                if (output.size() == 0 || (length != null && output.size().toLong() != length)) {
                    throw UpdateException(UpdateFailureReason.MANIFEST_INVALID)
                }
                output.toString(Charsets.UTF_8.name())
            } finally {
                // Stop the engine reader too when rejecting a body before EOF.
                response.cancel()
            }
        }

    suspend fun download(
        url: String,
        target: File,
        onProgress: (Int?) -> Unit
    ) {
        UpdatePolicy.requireHttpsUrl(url, "APK 下载地址")
        val parent = target.parentFile
            ?: throw UpdateException(UpdateFailureReason.DOWNLOAD_STORAGE_FAILED)
        try {
            if (!parent.exists() && !parent.mkdirs()) {
                throw UpdateException(UpdateFailureReason.DOWNLOAD_STORAGE_FAILED)
            }
        } catch (error: SecurityException) {
            throw UpdateException(UpdateFailureReason.DOWNLOAD_STORAGE_FAILED, cause = error)
        }
        val partial = File(target.parentFile, "${target.name}.part")
        try {
            if (partial.exists() && !partial.delete()) {
                throw UpdateException(UpdateFailureReason.DOWNLOAD_STORAGE_FAILED)
            }
            // get() returns a saved response in Ktor 2.x, buffering the entire APK
            // before bodyAsChannel() is reached. The scoped statement is streaming.
            client.prepareGet(url) {
                githubHeaders()
                timeout { requestTimeoutMillis = 30 * 60_000L }
            }.execute { response ->
                try {
                    UpdatePolicy.requireHttpsUrl(response.call.request.url.toString(), "APK 重定向地址")
                    if (!response.status.isSuccess()) {
                        throw UpdateException(UpdateFailureReason.DOWNLOAD_NETWORK_FAILED, response.status.value)
                    }
                    val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (total != null && (total <= 0 || total > MAX_APK_BYTES)) {
                        throw UpdateException(UpdateFailureReason.APK_INTEGRITY_FAILED)
                    }
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var lastPercent: Int? = if (total != null) 0 else null
                    onProgress(lastPercent)
                    val output = try {
                        FileOutputStream(partial)
                    } catch (error: Exception) {
                        throw UpdateException(UpdateFailureReason.DOWNLOAD_STORAGE_FAILED, cause = error)
                    }
                    output.use {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = try {
                                channel.readAvailable(buffer)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                throw UpdateException(UpdateFailureReason.DOWNLOAD_NETWORK_FAILED, cause = error)
                            }
                            if (count < 0) break
                            if (count == 0) continue
                            copied += count
                            if (copied > MAX_APK_BYTES || (total != null && copied > total)) {
                                throw UpdateException(UpdateFailureReason.APK_INTEGRITY_FAILED)
                            }
                            try {
                                output.write(buffer, 0, count)
                            } catch (error: Exception) {
                                throw UpdateException(UpdateFailureReason.DOWNLOAD_STORAGE_FAILED, cause = error)
                            }
                            val percent = total?.let { ((copied * 100) / it).toInt().coerceIn(0, 99) }
                            if (percent != lastPercent) {
                                onProgress(percent)
                                lastPercent = percent
                            }
                        }
                        if (copied <= 0 || (total != null && copied != total)) {
                            throw UpdateException(UpdateFailureReason.APK_INTEGRITY_FAILED)
                        }
                        try {
                            output.fd.sync()
                        } catch (error: Exception) {
                            throw UpdateException(UpdateFailureReason.DOWNLOAD_STORAGE_FAILED, cause = error)
                        }
                    }
                } finally {
                    response.cancel()
                }
            }
            currentCoroutineContext().ensureActive()
            onProgress(100)
            try {
                try {
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (error: Exception) {
                throw UpdateException(UpdateFailureReason.DOWNLOAD_STORAGE_FAILED, cause = error)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is UpdateException) throw error
            val reason = when (error) {
                is FileNotFoundException, is FileSystemException, is SecurityException ->
                    UpdateFailureReason.DOWNLOAD_STORAGE_FAILED
                else -> UpdateFailureReason.DOWNLOAD_NETWORK_FAILED
            }
            throw UpdateException(reason, cause = error)
        } finally {
            // A cancelled/failed transfer must not leave a partial APK as a result.
            runCatching { if (partial.exists()) partial.delete() }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.githubHeaders() {
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header(HttpHeaders.UserAgent, "MaidManager-Android-Updater")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    fun close() = client.close()

    private companion object {
        const val MAX_RELEASE_BYTES = 1024 * 1024
        const val MAX_MANIFEST_BYTES = 256 * 1024
        const val MAX_APK_BYTES = 512L * 1024 * 1024
    }
}

private fun updateHttpClient() = HttpClient(OkHttp) {
    install(HttpTimeout) {
        connectTimeoutMillis = 20_000
        requestTimeoutMillis = 180_000
        socketTimeoutMillis = 90_000
    }
}
