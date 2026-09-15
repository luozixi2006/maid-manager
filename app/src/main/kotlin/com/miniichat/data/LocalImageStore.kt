package com.miniichat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImageInputException(val userReason: String) : IOException(userReason)

/** Private JPEG copies survive picker permission expiry. Re-encoding removes EXIF location data. */
class LocalImageStore(private val context: Context) {
    private val directory get() = File(context.filesDir, "chat_images").apply { mkdirs() }

    fun cameraUri(): Uri {
        val folder = File(context.cacheDir, "camera").apply { mkdirs() }
        return FileProvider.getUriForFile(context, "${context.packageName}.files",
            File.createTempFile("capture-", ".jpg", folder))
    }

    fun discardCamera(uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("import-", ".tmp", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var count = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        count += read
                        if (count > 25L * 1024 * 1024) throw ImageInputException("照片超过 25 MB，请选择较小的图片。")
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw ImageInputException("无法读取照片，请重新选择。")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth.toLong() * bounds.outHeight > 100_000_000L) {
                throw ImageInputException("照片无法解码或尺寸过大，请选择 JPG、PNG 或 WebP 图片。")
            }
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2400) {
                options.inSampleSize *= 2
            }
            var bitmap = BitmapFactory.decodeFile(temporary.path, options)
                ?: throw ImageInputException("照片无法解码，请尝试另一张图片。")
            try {
                val orientation = runCatching {
                    ExifInterface(temporary.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                }.getOrDefault(1)
                val matrix = Matrix().apply {
                    when (orientation) {
                        2 -> setScale(-1f, 1f)
                        3 -> setRotate(180f)
                        4 -> { setRotate(180f); postScale(-1f, 1f) }
                        5 -> { setRotate(90f); postScale(-1f, 1f) }
                        6 -> setRotate(90f)
                        7 -> { setRotate(-90f); postScale(-1f, 1f) }
                        8 -> setRotate(-90f)
                    }
                }
                if (!matrix.isIdentity) {
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
                }
                val scale = 1600f / maxOf(bitmap.width, bitmap.height)
                if (scale < 1f) {
                    val resized = Bitmap.createScaledBitmap(bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                    if (resized !== bitmap) { bitmap.recycle(); bitmap = resized }
                }
                val target = File(directory, "${UUID.randomUUID()}.jpg")
                if (bitmap.hasAlpha()) {
                    val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                    android.graphics.Canvas(flattened).apply {
                        drawColor(android.graphics.Color.WHITE)
                        drawBitmap(bitmap, 0f, 0f, null)
                    }
                    bitmap.recycle()
                    bitmap = flattened
                }
                try {
                    target.outputStream().use {
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) {
                            throw ImageInputException("照片保存失败，请检查手机存储空间。")
                        }
                    }
                    if (target.length() > 2 * 1024 * 1024) {
                        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 65, it) }
                    }
                    if (target.length() !in 1..(2L * 1024 * 1024)) {
                        throw ImageInputException("照片压缩后仍然过大，请裁剪后重试。")
                    }
                    target.absolutePath
                } catch (error: Exception) { target.delete(); throw error }
            } finally { bitmap.recycle() }
        } finally { temporary.delete() }
    }

    fun attachment(path: String) = Attachment("image", path, "image/jpeg", "照片.jpg")

    suspend fun dataUrls(attachments: List<Attachment>): List<String> = withContext(Dispatchers.IO) {
        if (attachments.count { it.type == "image" } > MAX_PHOTOS) {
            throw ImageInputException("单条消息最多支持 4 张照片，请减少照片数量。")
        }
        attachments.filter { it.type == "image" }.map { attachment ->
            val file = File(attachment.uri).canonicalFile
            // Only our processed photos may be sent. Imported arbitrary file paths cannot leak files.
            if (file.parentFile != directory.canonicalFile || !file.isFile) {
                throw ImageInputException("历史照片已丢失或无法读取，请重新添加照片，或新建对话。")
            }
            if (file.length() !in 1..(2L * 1024 * 1024)) throw ImageInputException("照片大小不符合要求，请重新添加。")
            "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }
    }

    companion object { const val MAX_PHOTOS = 4 }
}
