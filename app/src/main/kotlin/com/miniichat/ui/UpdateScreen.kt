package com.miniichat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.miniichat.update.UpdateUiState

@Composable
fun UpdateScreen(
    state: UpdateUiState,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownload: (com.miniichat.update.UpdateManifest) -> Unit,
    onInstall: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar("软件更新", onBack)
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("当前版本", style = MaterialTheme.typography.labelLarge)
            Text(com.miniichat.BuildConfig.VERSION_NAME, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            when (state) {
                UpdateUiState.Idle -> {
                    Text("从项目的 GitHub Release 安全检查新版本。")
                    Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("检查更新") }
                }
                UpdateUiState.Checking -> ProgressLine("正在检查 GitHub Release…")
                is UpdateUiState.Current -> {
                    Text("已经是最新版本。")
                    FilledTonalButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("重新检查") }
                }
                is UpdateUiState.Available -> {
                    Text("发现 ${state.manifest.versionName}", style = MaterialTheme.typography.titleLarge)
                    if (state.manifest.changelog.isNotBlank()) Text(state.manifest.changelog)
                    Button(
                        onClick = { onDownload(state.manifest) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("下载并校验") }
                }
                is UpdateUiState.Downloading -> ProgressLine(
                    state.percent?.let { "正在下载并校验 · $it%" } ?: "正在下载并校验…"
                )
                is UpdateUiState.Ready -> {
                    Text("更新包已通过完整性、包名、版本和签名校验。")
                    Button(
                        onClick = { onInstall(state.apkPath) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("打开系统安装确认") }
                    Text(
                        "Android 首次更新可能要求允许本应用安装未知来源应用。系统仍会显示安装确认。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is UpdateUiState.NotConfigured -> {
                    Text(state.explanation)
                    FilledTonalButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("重新检查") }
                }
                is UpdateUiState.Failed -> {
                    Text(state.title, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                    Text(state.explanation)
                    FilledTonalButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("重试") }
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator()
        Text(text)
    }
}
