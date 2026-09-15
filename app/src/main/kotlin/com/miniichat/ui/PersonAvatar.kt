package com.miniichat.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun localPhoto(path: String?): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            path?.let {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(it, bounds)
                    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                    while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 512) {
                        options.inSampleSize *= 2
                    }
                    BitmapFactory.decodeFile(it, options)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    return image
}

@Composable
fun PersonAvatar(name: String, path: String?, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    val photo = localPhoto(path)
    Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center) {
        if (photo != null) Image(photo, "$name 的头像", Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        else Text(name.take(1).ifBlank { "·" }, color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium)
    }
}
