package com.miniichat.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.nio.file.FileSystemException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class GitHubReleaseClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            requestTimeoutMillis = 180_000
            socketTimeoutMillis = 180_000
        }
    }

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
        val releaseResponse = client.get(releaseUrl) { githubHeaders() }
        UpdatePolicy.requireHttpsUrl(releaseResponse.call.request.url.toString(), "GitHub 重定向地址")
        if (!releaseResponse.status.isSuccess()) {
            throw UpdateException(
                UpdateFailureReason.DOWNLOAD_NETWORK_FAILED,
                httpStatus = releaseResponse.status.value
            )
        }
        val release = runCatching {
            json.decodeFromString(GitHubRelease.serializer(), releaseResponse.bodyAsText())
        }.getOrElse {
            throw UpdateException(UpdateFailureReason.MANIFEST_INVALID, cause = it)
        }
        val asset = release.assets.firstOrNull { it.name == "update-manifest.json" }
            ?: throw UpdateException(UpdateFailureReason.MANIFEST_INVALID)
        UpdatePolicy.requireGitHubReleaseAssetUrl(asset.downloadUrl, owner, repository)
        val manifestResponse = client.get(asset.downloadUrl) { githubHeaders() }
        UpdatePolicy.requireHttpsUrl(manifestResponse.call.request.url.toString(), "更新清单重定向地址")
        if (!manifestResponse.status.isSuccess()) {
            throw UpdateException(
                UpdateFailureReason.DOWNLOAD_NETWORK_FAILED,
                httpStatus = manifestResponse.status.value
            )
        }
        val manifest = runCatching {
            json.decodeFromString(UpdateManifest.serializer(), manifestResponse.bodyAsText())
        }.getOrElse {
            throw UpdateException(UpdateFailureReason.MANIFEST_INVALID, cause = it)
        }
        UpdatePolicy.requireGitHubReleaseAssetUrl(manifest.apkUrl, owner, repository)
        return manifest
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
            val response = client.get(url) { githubHeaders() }
            UpdatePolicy.requireHttpsUrl(response.call.request.url.toString(), "APK 重定向地址")
            if (!response.status.isSuccess()) {
                throw UpdateException(
                    UpdateFailureReason.DOWNLOAD_NETWORK_FAILED,
                    httpStatus = response.status.value
                )
            }
            val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var copied = 0L
            val output = try {
                FileOutputStream(partial)
            } catch (error: Exception) {
                throw UpdateException(UpdateFailureReason.DOWNLOAD_STORAGE_FAILED, cause = error)
            }
            output.use {
                while (!channel.isClosedForRead) {
                    val count = try {
                        channel.readAvailable(buffer)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        throw UpdateException(UpdateFailureReason.DOWNLOAD_NETWORK_FAILED, cause = error)
                    }
                    if (count == -1) break
                    if (count == 0) continue
                    try {
                        output.write(buffer, 0, count)
                    } catch (error: Exception) {
                        throw UpdateException(UpdateFailureReason.DOWNLOAD_STORAGE_FAILED, cause = error)
                    }
                    copied += count
                    onProgress(total?.takeIf { it > 0 }?.let { ((copied * 100) / it).toInt().coerceIn(0, 100) })
                }
                try {
                    output.fd.sync()
                } catch (error: Exception) {
                    throw UpdateException(UpdateFailureReason.DOWNLOAD_STORAGE_FAILED, cause = error)
                }
            }
            if (partial.length() <= 0L) {
                throw UpdateException(UpdateFailureReason.APK_INTEGRITY_FAILED)
            }
            if (target.exists() && !target.delete()) {
                throw UpdateException(UpdateFailureReason.DOWNLOAD_STORAGE_FAILED)
            }
            if (!partial.renameTo(target)) {
                throw UpdateException(UpdateFailureReason.DOWNLOAD_STORAGE_FAILED)
            }
        } catch (error: Exception) {
            partial.delete()
            if (error is CancellationException) throw error
            if (error is UpdateException) throw error
            val reason = when (error) {
                is FileNotFoundException, is FileSystemException, is SecurityException ->
                    UpdateFailureReason.DOWNLOAD_STORAGE_FAILED
                else -> UpdateFailureReason.DOWNLOAD_NETWORK_FAILED
            }
            throw UpdateException(reason, cause = error)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.githubHeaders() {
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header(HttpHeaders.UserAgent, "MaidManager-Android-Updater")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    fun close() = client.close()
}
