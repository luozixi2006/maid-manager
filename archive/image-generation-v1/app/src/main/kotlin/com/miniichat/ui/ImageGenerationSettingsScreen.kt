package com.miniichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.miniichat.R
import com.miniichat.data.AppSettings

@Composable
fun ImageGenerationSettingsScreen(
    settings: AppSettings,
    connectionMessage: String?,
    testingConnection: Boolean,
    onBack: () -> Unit,
    onSave: (AppSettings) -> Unit,
    onTestConnection: (String) -> Unit
) {
    var enabled by rememberSaveable(settings.imageGenerationEnabled) {
        mutableStateOf(settings.imageGenerationEnabled)
    }
    var baseUrl by rememberSaveable(settings.imageGenerationBaseUrl) {
        mutableStateOf(settings.imageGenerationBaseUrl)
    }
    var width by rememberSaveable(settings.imageGenerationWidth) {
        mutableStateOf(settings.imageGenerationWidth.toString())
    }
    var height by rememberSaveable(settings.imageGenerationHeight) {
        mutableStateOf(settings.imageGenerationHeight.toString())
    }
    var steps by rememberSaveable(settings.imageGenerationSteps) {
        mutableStateOf(settings.imageGenerationSteps.toString())
    }
    var guidance by rememberSaveable(settings.imageGenerationGuidance) {
        mutableStateOf(settings.imageGenerationGuidance.toString())
    }
    var timeout by rememberSaveable(settings.imageGenerationTimeoutSeconds) {
        mutableStateOf(settings.imageGenerationTimeoutSeconds.toString())
    }
    val validWidth = width.toIntOrNull()?.takeIf { it in 512..1536 && it % 16 == 0 }
    val validHeight = height.toIntOrNull()?.takeIf { it in 512..1536 && it % 16 == 0 }
    val validSteps = steps.toIntOrNull()?.takeIf { it in 1..12 }
    val validGuidance = guidance.toFloatOrNull()?.takeIf { it in 0f..10f }
    val validTimeout = timeout.toIntOrNull()?.takeIf { it in 30..1800 }
    val valid = baseUrl.trim().let { it.startsWith("http://") || it.startsWith("https://") } &&
        validWidth != null && validHeight != null && validSteps != null &&
        validGuidance != null && validTimeout != null

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(stringResource(R.string.ai_image_settings), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.enable_ai_image), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.enable_ai_image_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(stringResource(R.string.pc_image_service_address)) },
                placeholder = { Text("http://192.168.1.20:7861") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedButton(
                onClick = { onTestConnection(baseUrl.trim()) },
                enabled = !testingConnection && baseUrl.isNotBlank()
            ) {
                Text(if (testingConnection) stringResource(R.string.testing_connection) else stringResource(R.string.test_connection))
            }
            connectionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(
                value = settings.imageGenerationModel,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.current_image_model)) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(width, { width = it }, stringResource(R.string.image_width), Modifier.weight(1f))
                NumberField(height, { height = it }, stringResource(R.string.image_height), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(steps, { steps = it }, stringResource(R.string.generation_steps), Modifier.weight(1f))
                NumberField(guidance, { guidance = it }, stringResource(R.string.guidance_scale), Modifier.weight(1f), decimal = true)
            }
            NumberField(
                timeout,
                { timeout = it },
                stringResource(R.string.timeout_seconds),
                Modifier.fillMaxWidth()
            )
            Text(
                stringResource(R.string.image_settings_range_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    onSave(
                        settings.copy(
                            imageGenerationEnabled = enabled,
                            imageGenerationBaseUrl = baseUrl.trim().trimEnd('/'),
                            imageGenerationWidth = requireNotNull(validWidth),
                            imageGenerationHeight = requireNotNull(validHeight),
                            imageGenerationSteps = requireNotNull(validSteps),
                            imageGenerationGuidance = requireNotNull(validGuidance),
                            imageGenerationTimeoutSeconds = requireNotNull(validTimeout)
                        )
                    )
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.save)) }
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    decimal: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number
        )
    )
}
