package com.miniichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    rpProviderName: String,
    rpProviderConfigured: Boolean,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenTts: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenImageGeneration: () -> Unit,
    onOpenChatData: () -> Unit,
    onOpenProactiveMessages: () -> Unit,
    onOpenRpModels: () -> Unit,
    onOpenRpParameters: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val provider = providers.firstOrNull { it.id == settings.activeProviderId }
        ?: providers.firstOrNull()
    val assistant = assistants.firstOrNull { it.id == settings.activeAssistantId }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(stringResource(R.string.settings), onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            SettingsSection(stringResource(R.string.section_ai_models)) {
                SettingsEntry(
                    title = stringResource(R.string.api_and_models),
                    subtitle = provider?.let {
                        "${it.name} · ${settings.activeModel.ifBlank { stringResource(R.string.no_model_selected) }}"
                    } ?: stringResource(R.string.api_not_configured),
                    icon = { Icon(Icons.Default.Key, contentDescription = null) },
                    onClick = onOpenProviders
                )
                SettingsEntry(
                    title = stringResource(R.string.ai_image_settings),
                    subtitle = if (!settings.imageGenerationEnabled) stringResource(R.string.disabled)
                    else settings.imageGenerationModel,
                    icon = { Icon(Icons.Default.Image, contentDescription = null) },
                    onClick = onOpenImageGeneration
                )
            }

            SettingsSection(stringResource(R.string.section_voice)) {
                SettingsEntry(
                    title = stringResource(R.string.voice_output),
                    subtitle = if (settings.ttsBaseUrl.isBlank()) stringResource(R.string.not_configured)
                    else settings.ttsProvider,
                    icon = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) },
                    onClick = onOpenTts
                )
            }

            SettingsSection(stringResource(R.string.section_rp)) {
                SettingsEntry(
                    title = stringResource(R.string.rp_model_settings),
                    subtitle = if (rpProviderConfigured) rpProviderName
                    else stringResource(R.string.rp_provider_not_configured, rpProviderName),
                    icon = { Icon(Icons.Default.TheaterComedy, contentDescription = null) },
                    onClick = onOpenRpModels
                )
                SettingsEntry(
                    title = stringResource(R.string.rp_generation_parameters),
                    subtitle = stringResource(R.string.rp_generation_parameters_hint),
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    onClick = onOpenRpParameters
                )
            }

            SettingsSection(stringResource(R.string.section_chat)) {
                SettingsEntry(
                    title = stringResource(R.string.personas),
                    subtitle = assistant?.name ?: stringResource(R.string.no_persona_selected),
                    icon = { Icon(Icons.Default.Face, contentDescription = null) },
                    onClick = onOpenAssistants
                )
                SettingsEntry(
                    title = stringResource(R.string.long_term_memory),
                    subtitle = if (settings.memoryEnabled) stringResource(R.string.memory_count, memoryCount)
                    else stringResource(R.string.disabled),
                    icon = { Icon(Icons.Default.Memory, contentDescription = null) },
                    onClick = onOpenMemories
                )
                SettingsEntry(
                    title = stringResource(R.string.search_settings),
                    subtitle = if (settings.searchBaseUrl.isBlank()) stringResource(R.string.not_configured)
                    else settings.searchProvider,
                    icon = { Icon(Icons.Default.Language, contentDescription = null) },
                    onClick = onOpenSearch
                )
                SettingsEntry(
                    title = stringResource(R.string.chat_data),
                    subtitle = stringResource(R.string.chat_data_entry_hint),
                    icon = { Icon(Icons.Default.Storage, contentDescription = null) },
                    onClick = onOpenChatData
                )
                SettingsEntry(
                    title = stringResource(R.string.proactive_messages),
                    subtitle = if (settings.proactiveMessagesEnabled) {
                        stringResource(R.string.proactive_messages_enabled)
                    } else {
                        stringResource(R.string.disabled)
                    },
                    icon = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) },
                    onClick = onOpenProactiveMessages
                )
            }

            SettingsSection(stringResource(R.string.section_appearance)) {
                SettingsEntry(
                    title = stringResource(R.string.setting_appearance),
                    subtitle = when (settings.themeMode) {
                        "light" -> stringResource(R.string.appearance_light)
                        "dark" -> stringResource(R.string.appearance_dark)
                        else -> stringResource(R.string.appearance_system)
                    },
                    icon = { Icon(Icons.Default.Palette, contentDescription = null) },
                    onClick = onOpenAppearance
                )
            }

            SettingsSection(stringResource(R.string.section_about), isLast = true) {
                SettingsEntry(
                    title = stringResource(R.string.setting_about),
                    subtitle = stringResource(R.string.about_summary),
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
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
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = { content() })
    if (!isLast) Spacer(Modifier.height(24.dp))
}

@Composable
private fun SettingsEntry(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.background,
                content = icon
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
fun SettingsTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
}
