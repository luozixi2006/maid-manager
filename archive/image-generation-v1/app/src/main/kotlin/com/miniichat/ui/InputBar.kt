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
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import com.miniichat.data.Attachment

@Suppress("UNUSED_PARAMETER")
@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    attachments: List<Attachment>,
    onAttachmentsChange: (List<Attachment>) -> Unit,
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
    imageGenerationEnabled: Boolean = false,
    isImageGenerating: Boolean = false,
    onGenerateImage: () -> Unit = {},
    onCancelImage: () -> Unit = {},
    enabled: Boolean = true
) {
    val focus = LocalFocusManager.current
    var effortMenuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.navigationBars.asPaddingValues())
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = webEnabled,
                onClick = { onWebEnabledChange(!webEnabled) },
                label = { Text(stringResource(R.string.web_search), fontSize = 12.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = reasoningEnabled,
                onClick = { onReasoningEnabledChange(!reasoningEnabled) },
                label = { Text(stringResource(R.string.deep_reasoning), fontSize = 12.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
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
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = isImageGenerating,
                onClick = { if (isImageGenerating) onCancelImage() else onGenerateImage() },
                enabled = isImageGenerating || (imageGenerationEnabled && value.trim().isNotEmpty()),
                label = {
                    Text(
                        stringResource(
                            if (isImageGenerating) R.string.cancel_generation
                            else R.string.generate_image
                        ),
                        fontSize = 12.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        if (isImageGenerating) Icons.Default.Stop else Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
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
                        color = LocalContentColor.current,
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    maxLines = 6,
                    enabled = enabled || imageGenerationEnabled
                )
            }
            Spacer(Modifier.width(8.dp))
            val canSend = value.trim().isNotEmpty() && !isStreaming && enabled
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
