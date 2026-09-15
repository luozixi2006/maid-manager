package com.miniichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.IconButton
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichat.R

@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    webEnabled: Boolean,
    reasoningEnabled: Boolean,
    supportsReasoning: Boolean,
    reasoningEffort: String,
    onWebEnabledChange: (Boolean) -> Unit,
    onReasoningEnabledChange: (Boolean) -> Unit,
    onReasoningEffortChange: (String) -> Unit,
    enabled: Boolean = true,
    photos: List<String> = emptyList(),
    photoBusy: Boolean = false,
    onChoosePhoto: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onRemovePhoto: (String) -> Unit = {}
) {
    val focus = LocalFocusManager.current
    var effortMenuOpen by remember { mutableStateOf(false) }
    var attachmentMenuOpen by remember { mutableStateOf(false) }
    val enabledLabel = stringResource(R.string.switch_on)
    val disabledLabel = stringResource(R.string.switch_off)
    val webLabel = stringResource(
        R.string.feature_state,
        stringResource(R.string.web_search),
        if (webEnabled) enabledLabel else disabledLabel
    )
    val reasoningLabel = stringResource(
        R.string.feature_state,
        stringResource(R.string.deep_reasoning),
        if (reasoningEnabled) enabledLabel else disabledLabel
    )
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(WindowInsets.navigationBars.asPaddingValues())
    ) {
        if (photos.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                photos.forEach { path ->
                    Box(Modifier.size(84.dp).clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)) {
                        localPhoto(path)?.let { Image(it, "待发送照片", Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
                        IconButton(onClick = { onRemovePhoto(path) }, Modifier.align(Alignment.TopEnd).size(36.dp)) {
                            Icon(Icons.Default.Close, "移除照片", Modifier.background(MaterialTheme.colorScheme.surface, CircleShape)
                                .padding(3.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            Text("照片将发送给当前模型服务，请选择支持看图的模型。", Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (photoBusy) Text("正在处理照片…", Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { onWebEnabledChange(!webEnabled) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (webEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(webLabel, fontSize = 12.sp)
            }
            Spacer(Modifier.width(2.dp))
            TextButton(
                onClick = { onReasoningEnabledChange(!reasoningEnabled) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (reasoningEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(reasoningLabel, fontSize = 12.sp)
            }
            if (reasoningEnabled && supportsReasoning) {
                Spacer(Modifier.width(4.dp))
                Box {
                    TextButton(onClick = { effortMenuOpen = true }) {
                        Text(
                            when (reasoningEffort) {
                                "low" -> stringResource(R.string.reasoning_fast)
                                "max" -> stringResource(R.string.reasoning_deep)
                                else -> stringResource(R.string.reasoning_standard)
                            },
                            fontSize = 12.sp
                        )
                    }
                    DropdownMenu(
                        expanded = effortMenuOpen,
                        onDismissRequest = { effortMenuOpen = false }
                    ) {
                        listOf(
                            "low" to R.string.reasoning_fast,
                            "high" to R.string.reasoning_standard,
                            "max" to R.string.reasoning_deep
                        ).forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                onClick = {
                                    onReasoningEffortChange(value)
                                    effortMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Box {
                IconButton(onClick = { attachmentMenuOpen = true }, enabled = !photoBusy && photos.size < 4 && !isStreaming) {
                    Icon(Icons.Default.Add, "添加照片", tint = MaterialTheme.colorScheme.primary)
                }
                DropdownMenu(expanded = attachmentMenuOpen, onDismissRequest = { attachmentMenuOpen = false }) {
                    DropdownMenuItem(text = { Text("照片图库") }, leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) },
                        onClick = { attachmentMenuOpen = false; onChoosePhoto() })
                    DropdownMenuItem(text = { Text("拍照") }, leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null) },
                        onClick = { attachmentMenuOpen = false; onTakePhoto() })
                }
            }
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 40.dp, max = 160.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        stringResource(R.string.hint_input),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    maxLines = 6,
                    enabled = enabled
                )
            }
            Spacer(Modifier.width(8.dp))
            val canSend = (value.isNotBlank() || photos.isNotEmpty()) && !photoBusy && !isStreaming && enabled
            val background = when {
                isStreaming -> MaterialTheme.colorScheme.onSurface
                canSend -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val foreground = when {
                isStreaming -> MaterialTheme.colorScheme.background
                canSend -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(background)
                    .clickable(enabled = isStreaming || canSend) {
                        if (isStreaming) onStop() else {
                            focus.clearFocus()
                            onSend()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isStreaming) Icons.Default.Stop else Icons.Default.ArrowUpward,
                    contentDescription = stringResource(if (isStreaming) R.string.stop else R.string.send),
                    tint = foreground,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
