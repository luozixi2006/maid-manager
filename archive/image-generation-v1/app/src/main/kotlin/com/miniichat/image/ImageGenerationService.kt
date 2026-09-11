package com.miniichat.image

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImageGenerationService(private val context: Context) {
    private val client = ImageGenerationClient()

    suspend fun testConnection(config: ImageGenerationConfig): ImageServiceHealth =
        client.testConnection(config)

    suspend fun generate(
        config: ImageGenerationConfig,
        originalPrompt: String,
        effectivePrompt: String = originalPrompt,
        collection: String,
        onJobChanged: (String, ImageGenerationStatus) -> Unit = { _, _ -> }
    ): GeneratedImageResult {
        val (result, bytes) = client.generate(config, originalPrompt, effectivePrompt, onJobChanged)
        val path = persist(bytes, collection)
        return result.copy(localPath = path)
    }

    suspend fun generateToPath(
        config: ImageGenerationConfig,
        originalPrompt: String,
        effectivePrompt: String = originalPrompt,
        outputPath: String
    ): GeneratedImageResult {
        val (result, bytes) = client.generate(config, originalPrompt, effectivePrompt) { _, _ -> }
        val path = persistExact(bytes, File(outputPath))
        return result.copy(localPath = path)
    }

    suspend fun copyToUri(localPath: String, uri: Uri) = withContext(Dispatchers.IO) {
        val source = File(localPath)
        if (!source.isFile) throw ImageGenerationException("IMAGE_FILE_MISSING", "本地图片文件不存在")
        val output = context.contentResolver.openOutputStream(uri)
            ?: throw ImageGenerationException("IMAGE_SAVE_FAILED", "无法打开保存位置")
        output.use { target -> source.inputStream().use { it.copyTo(target) } }
    }

    fun delete(localPath: String?) {
        val file = localPath?.let(::File) ?: return
        val root = File(context.filesDir, "generated_images")
        val resolvedFile = runCatching { file.canonicalFile }.getOrNull() ?: return
        val resolvedRoot = runCatching { root.canonicalFile }.getOrNull() ?: return
        if (resolvedFile.path.startsWith(resolvedRoot.path + File.separator)) {
            runCatching { resolvedFile.delete() }
        }
    }

    private suspend fun persist(bytes: ByteArray, collection: String): String = withContext(Dispatchers.IO) {
        val safeCollection = collection.replace(Regex("[^A-Za-z0-9_-]"), "_").take(100)
        val directory = File(context.filesDir, "generated_images/$safeCollection")
        val target = File(directory, "${UUID.randomUUID()}.png")
        persistExactBlocking(bytes, target)
    }

    private suspend fun persistExact(bytes: ByteArray, target: File): String = withContext(Dispatchers.IO) {
        persistExactBlocking(bytes, target)
    }

    private fun persistExactBlocking(bytes: ByteArray, target: File): String {
        val directory = target.parentFile
            ?: throw ImageGenerationException("IMAGE_SAVE_FAILED", "图片目标目录无效")
        if (!directory.exists() && !directory.mkdirs()) {
            throw ImageGenerationException("IMAGE_SAVE_FAILED", "无法创建图片保存目录")
        }
        val temp = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw ImageGenerationException("INVALID_IMAGE", "生图服务返回的不是有效图片")
            }
            if (target.exists() && !target.delete()) {
                throw ImageGenerationException("IMAGE_SAVE_FAILED", "无法替换旧图片")
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            return target.absolutePath
        } catch (error: ImageGenerationException) {
            temp.delete()
            throw error
        } catch (error: Exception) {
            temp.delete()
            throw ImageGenerationException("IMAGE_SAVE_FAILED", "图片保存失败", error)
        }
    }

    fun close() = client.close()
}
