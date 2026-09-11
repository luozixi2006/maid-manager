package com.miniichat.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miniichat.R
import com.miniichat.data.AppSettings
import com.miniichat.image.ImageGenerationStatus
import com.miniichat.image.ImageGenerationUiState

@Composable
fun ImageGenerationScreen(
    settings: AppSettings,
    state: ImageGenerationUiState,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onGenerate: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: (String, Uri) -> Unit
) {
    var prompt by rememberSaveable { mutableStateOf(state.originalPrompt) }
    val configured = settings.imageGenerationEnabled && settings.imageGenerationBaseUrl.isNotBlank()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(stringResource(R.string.ai_image_generation), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(settings.imageGenerationModel, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${settings.imageGenerationWidth} × ${settings.imageGenerationHeight} · " +
                            "${settings.imageGenerationSteps} steps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!configured) {
                Text(
                    stringResource(R.string.image_service_not_configured),
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Text(stringResource(R.string.configure_image_service), Modifier.padding(start = 6.dp))
                }
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(stringResource(R.string.image_prompt)) },
                placeholder = { Text(stringResource(R.string.image_prompt_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 10,
                enabled = !state.isBusy
            )
            if (state.isBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp))
                    Text(imageStatusText(state.status))
                }
                Button(onClick = onCancel) {
                    Icon(Icons.Default.Cancel, contentDescription = null)
                    Text(stringResource(R.string.cancel_generation), Modifier.padding(start = 6.dp))
                }
            } else {
                Button(
                    onClick = { onGenerate(prompt) },
                    enabled = configured && prompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Image, contentDescription = null)
                    Text(stringResource(R.string.generate_image), Modifier.padding(start = 6.dp))
                }
            }
            state.errorMessage?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            state.localPath?.let { path ->
                GeneratedImageContent(
                    localPath = path,
                    description = state.originalPrompt,
                    onSave = onSave
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { onGenerate(state.originalPrompt) }, enabled = !state.isBusy) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(stringResource(R.string.regenerate), Modifier.padding(start = 6.dp))
                }
                Text(
                    "${state.width} × ${state.height} · seed ${state.seed ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun imageStatusText(status: ImageGenerationStatus): String = when (status) {
    ImageGenerationStatus.WAITING -> stringResource(R.string.image_status_waiting)
    ImageGenerationStatus.LOADING_MODEL -> stringResource(R.string.image_status_loading_model)
    ImageGenerationStatus.GENERATING -> stringResource(R.string.image_status_generating)
    ImageGenerationStatus.SUCCEEDED -> stringResource(R.string.image_status_success)
    ImageGenerationStatus.FAILED -> stringResource(R.string.image_status_failed)
    ImageGenerationStatus.CANCELLED -> stringResource(R.string.image_status_cancelled)
    ImageGenerationStatus.IDLE -> ""
}
