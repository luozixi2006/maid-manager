package com.miniichat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.miniichat.R
import com.miniichat.data.AppSettings

@Composable
fun ProactiveMessagesScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onSave: (AppSettings) -> Unit
) {
    val context = LocalContext.current
    var enabled by remember(settings) { mutableStateOf(settings.proactiveMessagesEnabled) }
    var frequency by remember(settings) { mutableStateOf(settings.proactiveFrequency) }
    var dndStart by remember(settings) { mutableStateOf(formatMinutes(settings.proactiveDndStartMinutes)) }
    var dndEnd by remember(settings) { mutableStateOf(formatMinutes(settings.proactiveDndEndMinutes)) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(stringResource(R.string.proactive_messages), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Disclosure("主动消息说明") { Text(stringResource(R.string.proactive_messages_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.allow_proactive_messages), Modifier.weight(1f))
                AppSwitch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Text(stringResource(R.string.proactive_frequency), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "rare" to R.string.frequency_rare,
                    "occasional" to R.string.frequency_occasional,
                    "frequent" to R.string.frequency_frequent
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = frequency == value,
                        onClick = { frequency = value },
                        label = { Text(stringResource(label)) }
                    )
                }
            }

            Text(stringResource(R.string.do_not_disturb), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextField(
                    value = dndStart,
                    onValueChange = { dndStart = it.take(5); validationError = null },
                    label = { Text("开始 HH:mm") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                AppTextField(
                    value = dndEnd,
                    onValueChange = { dndEnd = it.take(5); validationError = null },
                    label = { Text("结束 HH:mm") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                stringResource(R.string.dnd_summary, dndStart, dndEnd),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Text(
                    stringResource(R.string.notification_permission_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val start = parseMinutes(dndStart)
                    val end = parseMinutes(dndEnd)
                    if (start == null || end == null) {
                        validationError = "时间格式应为 HH:mm"
                        return@Button
                    }
                    onSave(
                        settings.copy(
                            proactiveMessagesEnabled = enabled,
                            proactiveFrequency = frequency,
                            proactiveDndStartMinutes = start,
                            proactiveDndEndMinutes = end
                        )
                    )
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    onBack()
                }
            ) { Text("保存") }
        }
    }
}

private fun formatMinutes(value: Int): String =
    "%02d:%02d".format((value.coerceIn(0, 1439) / 60), value.coerceIn(0, 1439) % 60)

private fun parseMinutes(value: String): Int? {
    val parts = value.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}
