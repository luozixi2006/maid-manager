package com.miniichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniichat.R
import com.miniichat.data.AppSettings
import com.miniichat.data.Assistant
import com.miniichat.data.ProviderConfig

@Composable
fun SettingsScreen(
    settings: AppSettings,
    providers: List<ProviderConfig>,
    assistants: List<Assistant>,
    memoryCount: Int,
    errorCount: Int,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenTts: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenChatData: () -> Unit,
    onOpenProactiveMessages: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenErrors: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val provider = providers.firstOrNull { it.id == settings.activeProviderId }
        ?: providers.firstOrNull()
    val assistant = assistants.firstOrNull { it.id == settings.activeAssistantId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(stringResource(R.string.settings), onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            SettingsSection(stringResource(R.string.section_services_network)) {
                SettingsEntry(
                    title = stringResource(R.string.api_and_models),
                    subtitle = provider?.let {
                        "${it.name} · ${settings.activeModel.ifBlank { stringResource(R.string.no_model_selected) }}"
                    } ?: stringResource(R.string.api_not_configured),
                    onClick = onOpenProviders
                )
                SettingsEntry(
                    title = stringResource(R.string.search_settings),
                    subtitle = stringResource(R.string.search_automatic_summary),
                    onClick = onOpenSearch
                )
            }

            SettingsSection(stringResource(R.string.section_conversation)) {
                SettingsEntry(title = "任务与陪伴", subtitle = "任务 · 悬浮头像 · 自动提醒", onClick = onOpenTasks)
                SettingsEntry(
                    title = stringResource(R.string.personas),
                    subtitle = assistant?.name ?: stringResource(R.string.no_persona_selected),
                    onClick = onOpenAssistants
                )
                SettingsEntry(
                    title = stringResource(R.string.long_term_memory),
                    subtitle = if (settings.memoryEnabled) stringResource(R.string.memory_count, memoryCount)
                    else stringResource(R.string.disabled),
                    onClick = onOpenMemories
                )
                SettingsEntry(
                    title = stringResource(R.string.proactive_messages),
                    subtitle = if (settings.proactiveMessagesEnabled) {
                        stringResource(R.string.proactive_messages_enabled)
                    } else {
                        stringResource(R.string.disabled)
                    },
                    onClick = onOpenProactiveMessages
                )
            }

            SettingsSection(stringResource(R.string.section_data_diagnostics)) {
                SettingsEntry(
                    title = stringResource(R.string.chat_data),
                    subtitle = stringResource(R.string.chat_data_entry_hint),
                    onClick = onOpenChatData
                )
                SettingsEntry(
                    title = stringResource(R.string.error_center),
                    subtitle = if (errorCount == 0) stringResource(R.string.no_recorded_errors)
                    else stringResource(R.string.recorded_error_count, errorCount),
                    onClick = onOpenErrors
                )
                SettingsEntry(
                    title = stringResource(R.string.software_updates),
                    subtitle = stringResource(R.string.software_updates_summary),
                    onClick = onOpenUpdates
                )
            }

            SettingsSection(stringResource(R.string.section_personalization)) {
                SettingsEntry(
                    title = stringResource(R.string.setting_appearance),
                    subtitle = when (settings.themeMode) {
                        "light" -> stringResource(R.string.appearance_light)
                        "dark" -> stringResource(R.string.appearance_dark)
                        else -> stringResource(R.string.appearance_system)
                    },
                    onClick = onOpenAppearance
                )
                SettingsEntry(
                    title = stringResource(R.string.voice_output),
                    subtitle = if (settings.ttsBaseUrl.isBlank()) {
                        stringResource(R.string.optional_not_configured)
                    } else {
                        stringResource(R.string.optional_configured, settings.ttsProvider)
                    },
                    onClick = onOpenTts
                )
            }

            SettingsSection(stringResource(R.string.section_about), isLast = true) {
                SettingsEntry(
                    title = stringResource(R.string.setting_about),
                    subtitle = stringResource(R.string.about_summary),
                    onClick = onOpenAbout
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    isLast: Boolean = false,
    content: @Composable () -> Unit
) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold
    )
    AppGroup { content() }
    if (!isLast) Spacer(Modifier.height(12.dp))
}

@Composable
private fun SettingsEntry(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
}

@Composable
fun SettingsTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f))
    }
}
