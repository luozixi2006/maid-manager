package com.miniichat.ui.rp

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniichat.rp.RpStateDefinition
import com.miniichat.rp.RpStateType
import com.miniichat.rp.RpStateValue
import java.io.File

@Composable
fun RpTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {}
) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            } else Spacer(Modifier.size(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            actions()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    }
}

@Composable
fun RpAvatar(path: String?, name: String, size: Int = 48, shape: Shape = CircleShape) {
    val bitmap = remember(path) {
        path?.takeIf { File(it).isFile }?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    }
    Surface(
        modifier = Modifier.size(size.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(), contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size.dp).clip(shape)
                )
            } else {
                Text(
                    name.trim().take(1).ifBlank { "?" },
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

fun String.looksLikeImageReference(): Boolean {
    val value = trim().lowercase()
    return value.startsWith("content://") || value.startsWith("file://") ||
        value.contains('/') || value.contains('\\') ||
        value.endsWith(".png") || value.endsWith(".jpg") || value.endsWith(".jpeg") ||
        value.endsWith(".webp") || value.endsWith(".gif") || value.endsWith(".img")
}

@Composable
fun RpStateIcon(icon: String, stateName: String, size: Int = 22) {
    val context = LocalContext.current
    val bitmap = remember(icon) {
        runCatching {
            val clean = icon.removePrefix("file://")
            when {
                File(clean).isFile -> BitmapFactory.decodeFile(clean)
                icon.startsWith("content://") -> context.contentResolver.openInputStream(
                    android.net.Uri.parse(icon)
                )?.use(BitmapFactory::decodeStream)
                icon.looksLikeImageReference() -> {
                    val resourceName = File(icon).nameWithoutExtension
                    val drawable = context.resources.getIdentifier(
                        resourceName, "drawable", context.packageName
                    ).takeIf { it != 0 } ?: context.resources.getIdentifier(
                        resourceName, "mipmap", context.packageName
                    ).takeIf { it != 0 }
                    drawable?.let { BitmapFactory.decodeResource(context.resources, it) }
                }
                else -> null
            }
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stateName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(5.dp))
        )
    } else {
        Text(
            if (icon.isNotBlank() && !icon.looksLikeImageReference()) icon else stateIconFallback(stateName),
            modifier = Modifier.size(size.dp),
            maxLines = 1
        )
    }
}

private fun stateIconFallback(name: String): String = when {
    name.contains("好感") || name.contains("信任") || name.contains("爱") -> "❤️"
    name.contains("魔") || name.contains("法力") -> "✨"
    name.contains("钱") || name.contains("金币") -> "💰"
    name.contains("心情") -> "🙂"
    name.contains("线索") || name.contains("怀疑") -> "🔎"
    name.contains("地点") || name.contains("位置") -> "📍"
    else -> "•"
}

@Composable
fun RpListEntry(
    title: String,
    subtitle: String = "",
    leading: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f).padding(start = if (leading == null) 0.dp else 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
fun RpStateCard(definition: RpStateDefinition, value: RpStateValue?) {
    val text = value?.value ?: definition.defaultValue
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RpStateIcon(definition.icon, definition.name)
                Spacer(Modifier.size(8.dp))
                Text(definition.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                if (definition.type != RpStateType.PROGRESS) Text(text)
            }
            if (definition.type == RpStateType.PROGRESS) {
                val current = text.toFloatOrNull() ?: 0f
                val max = definition.maxValue.toFloat().coerceAtLeast(1f)
                val animated by animateFloatAsState(
                    targetValue = (current / max).coerceIn(0f, 1f),
                    animationSpec = tween(240), label = "rp-state"
                )
                Spacer(Modifier.size(6.dp))
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                )
                Text("$text / ${definition.maxValue.toInt()}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun RpPageReveal(scale: Boolean = false, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(160)) + (if (scale) {
            scaleIn(initialScale = 0.985f, animationSpec = tween(180))
        } else {
            slideInHorizontally(initialOffsetX = { it / 12 }, animationSpec = tween(180))
        })
    ) { content() }
}

@Composable
fun RpRevealItem(key: Any, content: @Composable () -> Unit) {
    var visible by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + slideInHorizontally(
            initialOffsetX = { it / 16 }, animationSpec = tween(180)
        )
    ) { content() }
}
