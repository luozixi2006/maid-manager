package com.miniichat.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miniichat.R
import com.miniichat.data.Attachment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun GeneratedAttachmentGallery(
    attachments: List<Attachment>,
    onSave: (String, Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val images = attachments.filter { it.type == "image" && it.uri.isNotBlank() }
    if (images.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEach { attachment ->
            GeneratedImageContent(
                localPath = attachment.uri,
                description = attachment.originalPrompt.ifBlank { attachment.name },
                onSave = onSave
            )
        }
    }
}
@Composable
fun GeneratedImageContent(
    localPath: String,
    description: String,
    onSave: (String, Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var fullscreen by remember { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, localPath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (localPath.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(localPath))?.use {
                        BitmapFactory.decodeStream(it)?.asImageBitmap()
                    }
                } else {
                    BitmapFactory.decodeFile(localPath)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(if (localPath.endsWith(".jpg", true)) "image/jpeg" else "image/png")
    ) { uri -> if (uri != null) onSave(localPath, uri) }

    Column(modifier = modifier.fillMaxWidth()) {
        val loaded = bitmap
        if (loaded == null) {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.image_load_failed),
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else {
            Image(
                bitmap = loaded,
                contentDescription = description,
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 520.dp)
                    .clip(RoundedCornerShape(14.dp)).clickable { fullscreen = true },
                contentScale = ContentScale.Fit
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { fullscreen = true }) {
                    Icon(Icons.Default.Fullscreen, contentDescription = null)
                    Text(stringResource(R.string.view_large_image), modifier = Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = {
                    val extension = if (localPath.endsWith(".jpg", true)) "jpg" else "png"
                    saveLauncher.launch("photo-${System.currentTimeMillis()}.$extension")
                }) {
                    Icon(Icons.Default.SaveAlt, contentDescription = null)
                    Text(stringResource(R.string.save_image), modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }

    if (fullscreen && bitmap != null) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Image(
                    bitmap = requireNotNull(bitmap),
                    contentDescription = description,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    contentScale = ContentScale.Fit
                )
                IconButton(
                    onClick = { fullscreen = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                }
            }
        }
    }
}
