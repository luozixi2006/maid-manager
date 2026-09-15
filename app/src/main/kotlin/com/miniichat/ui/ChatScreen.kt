package com.miniichat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichat.R
import com.miniichat.StreamingOverlay
import com.miniichat.data.AppSettings
import com.miniichat.data.Conversation
import com.miniichat.data.Message
import com.miniichat.data.MessageDeliveryStatus
import com.miniichat.data.ProviderConfig
import com.miniichat.data.Attachment
import com.miniichat.data.LocalImageStore
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import com.miniichat.tts.TtsPlaybackState
import com.miniichat.tts.TtsStatus

@Composable
fun ChatScreen(
    conversation: Conversation?,
    settings: AppSettings,
    activeProvider: ProviderConfig?,
    assistantName: String,
    assistantAvatar: String?,
    isStreaming: Boolean,
    streamingOverlay: StreamingOverlay? = null,
    ttsState: TtsPlaybackState,
    supportsReasoning: Boolean,
    onMenu: () -> Unit,
    onSend: (String, List<Attachment>) -> Boolean,
    onStop: () -> Unit,
    onRegenerate: () -> Unit,
    onRegenerateFrom: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onEditMessage: (String, String) -> Unit = { _, _ -> },
    onPlayMessage: (Message) -> Unit,
    onWebEnabledChange: (Boolean) -> Unit,
    onReasoningEnabledChange: (Boolean) -> Unit,
    onReasoningEffortChange: (String) -> Unit,
    onSaveImage: (String, Uri) -> Unit,
    onOpenErrors: () -> Unit,
    onPhotoError: (String) -> Unit,
    onNew: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditPersona: () -> Unit,
    onPickModel: () -> Unit
) {
    var input by rememberSaveable(conversation?.id) { mutableStateOf("") }
    var photoPaths by rememberSaveable(conversation?.id) { mutableStateOf(emptyList<String>()) }
    val context = LocalContext.current
    val imageStore = remember(context) { LocalImageStore(context) }
    val photoActions = rememberPhotoActions(conversation?.id ?: "new-chat", 4 - photoPaths.size,
        onImported = { photoPaths = (photoPaths + it).take(4) }, onError = onPhotoError)
    val listState = rememberLazyListState()
    val rawMessages = conversation?.messages ?: emptyList()
    // Apply in-memory streaming overlay so the assistant message updates per-token
    // without DataStore writes.
    val messages = remember(rawMessages, streamingOverlay) {
        val ov = streamingOverlay
        if (ov == null) rawMessages
        else rawMessages.map {
            if (it.id == ov.messageId) it.copy(
                content = ov.content,
                reasoningText = ov.reasoning
            ) else it
        }
    }
    var editingMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingDraft by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(messages.size, isStreaming) {
        if (messages.isNotEmpty() && !listState.isScrollInProgress) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .imePadding()
    ) {
        ChatTopBar(
            title = assistantName,
            avatarPath = assistantAvatar,
            onEditPersona = onEditPersona,
            modelLabel = settings.activeModel.ifBlank { stringResource(R.string.select_model) },
            providerLabel = activeProvider?.name,
            onMenu = onMenu,
            onPickModel = onPickModel,
            onNew = onNew
        )

        if (messages.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                PersonAvatar(assistantName, assistantAvatar, 72.dp, Modifier.clickable(onClick = onEditPersona))
                Spacer(Modifier.height(16.dp))
                Text(assistantName, style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Text("发条消息，或分享一张照片", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.TextButton(onClick = onEditPersona) { Text("名字与头像") }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageItem(
                        message = msg,
                        avatarPath = assistantAvatar,
                        senderLabel = if (msg.role == "user") stringResource(R.string.you)
                        else assistantName.ifBlank { stringResource(R.string.default_ai_name) },
                        isLastAssistant = msg.id == messages.lastOrNull()?.id && msg.role == "assistant",
                        isStreaming = isStreaming,
                        editing = editingMessageId == msg.id,
                        editingDraft = if (editingMessageId == msg.id) editingDraft else "",
                        onEditingDraftChange = { editingDraft = it },
                        onStartEdit = {
                            editingMessageId = msg.id
                            editingDraft = msg.content
                        },
                        onCommitEdit = {
                            editingMessageId?.let { id ->
                                onEditMessage(id, editingDraft)
                            }
                            editingMessageId = null
                            editingDraft = ""
                        },
                        onCancelEdit = {
                            editingMessageId = null
                            editingDraft = ""
                        },
                        onDelete = { onDeleteMessage(msg.id) },
                        onRegenerateFrom = {
                            if (msg.role == "user") onRegenerateFrom(msg.id)
                            else messages.takeWhile { it.id != msg.id }.lastOrNull { it.role == "user" }
                                ?.let { onRegenerateFrom(it.id) }
                        },
                        ttsState = ttsState,
                        onPlay = { onPlayMessage(msg) },
                        onOpenErrors = onOpenErrors,
                        onSaveImage = onSaveImage
                    )
                }
            }
        }

        InputBar(
            value = input,
            onValueChange = { input = it },
            onSend = {
                if (onSend(input, photoPaths.map(imageStore::attachment))) {
                    input = ""
                    photoPaths = emptyList()
                }
            },
            onStop = onStop,
            isStreaming = isStreaming,
            webEnabled = settings.webEnabled,
            reasoningEnabled = settings.reasoningEnabled,
            supportsReasoning = supportsReasoning,
            reasoningEffort = settings.reasoningEffort,
            onWebEnabledChange = onWebEnabledChange,
            onReasoningEnabledChange = onReasoningEnabledChange,
            onReasoningEffortChange = onReasoningEffortChange,
            enabled = activeProvider != null && settings.activeModel.isNotBlank(),
            photos = photoPaths,
            photoBusy = photoActions.busy,
            onChoosePhoto = photoActions.choose,
            onTakePhoto = photoActions.take,
            onRemovePhoto = { path -> photoPaths = photoPaths - path }
        )
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    avatarPath: String?,
    onEditPersona: () -> Unit,
    modelLabel: String,
    providerLabel: String?,
    onMenu: () -> Unit,
    onPickModel: () -> Unit,
    onNew: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_navigation),
                    tint = MaterialTheme.colorScheme.onSurface)
            }
            PersonAvatar(title, avatarPath, 36.dp, Modifier.clickable(onClick = onEditPersona))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title, Modifier.clickable(onClick = onEditPersona), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(
                modifier = Modifier
                    .clickable(onClick = onPickModel)
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    listOfNotNull(providerLabel?.takeIf { it.isNotBlank() }, modelLabel)
                        .joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.select_model),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            }
            IconButton(onClick = onNew) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.new_chat),
                    tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    }
}

@Composable
private fun MessageItem(
    message: Message,
    avatarPath: String?,
    senderLabel: String,
    isLastAssistant: Boolean,
    isStreaming: Boolean,
    editing: Boolean = false,
    editingDraft: String = "",
    onEditingDraftChange: (String) -> Unit = {},
    onStartEdit: () -> Unit = {},
    onCommitEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onRegenerateFrom: () -> Unit = {},
    ttsState: TtsPlaybackState,
    onPlay: () -> Unit,
    onOpenErrors: () -> Unit,
    onSaveImage: (String, Uri) -> Unit
) {
    val isUser = message.role == "user"
    if (isUser) {
        UserBubble(
            message = message,
            editing = editing,
            editingDraft = editingDraft,
            onEditingDraftChange = onEditingDraftChange,
            onStartEdit = onStartEdit,
            onCommitEdit = onCommitEdit,
            onCancelEdit = onCancelEdit,
            onDelete = onDelete,
            onRegenerateFrom = onRegenerateFrom,
            actionsEnabled = !isStreaming,
            onSaveImage = onSaveImage
        )
    } else {
        AssistantRow(
            message = message,
            senderLabel = senderLabel,
            avatarPath = avatarPath,
            isLastAssistant = isLastAssistant,
            isStreaming = isStreaming,
            onDelete = onDelete,
            actionsEnabled = !isStreaming,
            onRegenerate = onRegenerateFrom,
            ttsState = ttsState,
            onPlay = onPlay,
            onOpenErrors = onOpenErrors,
            onSaveImage = onSaveImage
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    message: Message,
    editing: Boolean,
    editingDraft: String,
    onEditingDraftChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onCommitEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: () -> Unit,
    onRegenerateFrom: () -> Unit,
    actionsEnabled: Boolean,
    onSaveImage: (String, Uri) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.82f),
            horizontalAlignment = Alignment.End
        ) {
            if (editing) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp)
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = editingDraft,
                        onValueChange = onEditingDraftChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                            lineHeight = 22.sp
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.TextButton(onClick = onCancelEdit) {
                            Text(stringResource(R.string.cancel))
                        }
                        Spacer(Modifier.width(4.dp))
                        androidx.compose.material3.TextButton(onClick = onCommitEdit) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            } else if (message.content.isNotEmpty()) {
                Box {
                    Box(
                        modifier = Modifier
                            .clip(
                                RoundedCornerShape(
                                    topStart = 18.dp,
                                    topEnd = 18.dp,
                                    bottomStart = 18.dp,
                                    bottomEnd = 4.dp
                                )
                            )
                            .background(MaterialTheme.colorScheme.primary)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { menuOpen = true }
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = message.content,
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy)) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = {
                                clipboard.setText(AnnotatedString(message.content))
                                menuOpen = false
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit)) },
                            enabled = actionsEnabled,
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = {
                                menuOpen = false
                                onStartEdit()
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.regenerate_from_here)) },
                            enabled = actionsEnabled,
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = {
                                menuOpen = false
                                onRegenerateFrom()
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            enabled = actionsEnabled,
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }
            GeneratedAttachmentGallery(
                attachments = message.attachments,
                onSave = onSaveImage,
                modifier = Modifier.padding(top = if (message.content.isNotEmpty()) 8.dp else 0.dp)
            )
            if (message.content.isBlank() && message.attachments.isNotEmpty()) {
                Row {
                    androidx.compose.material3.TextButton(onClick = onRegenerateFrom, enabled = actionsEnabled) { Text("重新发送") }
                    androidx.compose.material3.TextButton(onClick = onDelete, enabled = actionsEnabled) { Text("删除") }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AssistantRow(
    message: Message,
    avatarPath: String?,
    senderLabel: String,
    isLastAssistant: Boolean,
    isStreaming: Boolean,
    onDelete: () -> Unit,
    actionsEnabled: Boolean,
    onRegenerate: () -> Unit,
    ttsState: TtsPlaybackState,
    onPlay: () -> Unit,
    onOpenErrors: () -> Unit,
    onSaveImage: (String, Uri) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(senderLabel, avatarPath, 28.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                senderLabel,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(8.dp))
        val showReasoning = message.reasoningEnabled && (
            message.reasoningText.isNotBlank() || message.reasoningSummary.isNotBlank() ||
                (isLastAssistant && isStreaming)
            )
        if (showReasoning) {
            ReasoningBlock(
                reasoningText = message.reasoningText,
                summary = message.reasoningSummary,
                isStreaming = isLastAssistant && isStreaming,
                answerStarted = message.content.isNotEmpty()
            )
            Spacer(Modifier.height(8.dp))
        }
        if (message.content.isEmpty() && isLastAssistant && isStreaming && !showReasoning) {
            TypingDots()
        } else if (message.content.isNotEmpty()) {
            Box {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (message.content.isNotEmpty()) menuOpen = true }
                        )
                ) {
                    SelectionContainer {
                        MarkdownText(
                            text = message.content,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy)) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = {
                            clipboard.setText(AnnotatedString(message.content))
                            menuOpen = false
                        }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        enabled = actionsEnabled,
                        leadingIcon = {
                            Icon(
                                androidx.compose.material.icons.Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
        GeneratedAttachmentGallery(
            attachments = message.attachments,
            onSave = onSaveImage,
            modifier = Modifier.padding(top = if (message.content.isNotEmpty()) 10.dp else 0.dp)
        )
        if (message.deliveryStatus == MessageDeliveryStatus.PARTIAL_FAILED) {
            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    append(stringResource(R.string.reply_incomplete))
                    message.errorReportId?.let { append(" · $it") }
                },
                modifier = Modifier.clickable(onClick = onOpenErrors),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (message.content.isNotEmpty() && (!isLastAssistant || !isStreaming) &&
            message.attachments.none { it.type == "image" }
        ) {
            if (message.sources.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                SourcesBlock(message.sources)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CopyButton(content = message.content)
                Spacer(Modifier.width(8.dp))
                PlaybackButton(
                    messageId = message.id,
                    state = ttsState,
                    onClick = onPlay
                )
                if (actionsEnabled) {
                    Spacer(Modifier.width(8.dp))
                    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRegenerate)
                        .padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text("重新回答", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyButton(content: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1200); copied = false } }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                clipboard.setText(AnnotatedString(content))
                copied = true
            }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = "copy",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (copied) stringResource(R.string.copied) else stringResource(R.string.copy),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlaybackButton(
    messageId: String,
    state: TtsPlaybackState,
    onClick: () -> Unit
) {
    val active = state.messageId == messageId
    val status = if (active) state.status else TtsStatus.IDLE
    Box(
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when (status) {
                    TtsStatus.LOADING -> Icons.Default.HourglassEmpty
                    TtsStatus.PLAYING -> Icons.Default.Pause
                    else -> Icons.Default.PlayArrow
                },
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Text(
                when (status) {
                    TtsStatus.LOADING -> stringResource(R.string.loading)
                    TtsStatus.PLAYING -> stringResource(R.string.pause)
                    else -> stringResource(R.string.play)
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReasoningBlock(
    reasoningText: String,
    summary: String,
    isStreaming: Boolean,
    answerStarted: Boolean
) {
    var expanded by remember { mutableStateOf(isStreaming && reasoningText.isNotBlank()) }
    LaunchedEffect(isStreaming, reasoningText, answerStarted) {
        expanded = isStreaming && !answerStarted && reasoningText.isNotBlank()
    }
    val displayText = reasoningText.ifBlank { summary }
    val title = when {
        isStreaming && !answerStarted -> stringResource(R.string.reasoning_in_progress)
        isStreaming -> stringResource(R.string.reasoning_complete)
        reasoningText.isNotBlank() -> stringResource(R.string.reasoning_process)
        else -> stringResource(R.string.reasoning_summary)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.clickable(enabled = displayText.isNotBlank()) { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (displayText.isNotBlank()) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (expanded && displayText.isNotBlank()) {
            Text(
                displayText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SourcesBlock(sources: List<com.miniichat.data.SourceReference>) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.sources),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        sources.forEachIndexed { index, source ->
            Text(
                "${index + 1}. ${source.title}",
                modifier = Modifier.padding(top = 3.dp).clickable(enabled = source.url.isNotBlank()) {
                    runCatching { uriHandler.openUri(source.url) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (source.url.isNotBlank()) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TypingDots() {
    val infinite = rememberInfiniteTransition(label = "typing")
    val alpha by infinite.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (i == 0) alpha
                            else if (i == 1) (1f - alpha)
                            else alpha * 0.7f + 0.3f
                        )
                    )
            )
        }
    }
}
