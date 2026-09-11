package com.miniichat.update

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miniichat.error.AppError
import com.miniichat.error.AppErrorClassifier
import com.miniichat.error.AppErrorContext
import com.miniichat.error.AppErrorStore
import com.miniichat.error.ErrorArea
import com.miniichat.error.ErrorOperation
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = UpdateRepository(app)
    private val errorStore = AppErrorStore(app)
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()
    private var installInProgress = false

    fun check() {
        if (_state.value is UpdateUiState.Checking || _state.value is UpdateUiState.Downloading) return
        viewModelScope.launch {
            _state.value = UpdateUiState.Checking
            _state.value = try {
                repository.check()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordSafely(error, ErrorOperation.CHECK_UPDATE).asUiFailure()
            }
        }
    }

    fun download(manifest: UpdateManifest) {
        if (_state.value is UpdateUiState.Downloading) return
        viewModelScope.launch {
            _state.value = UpdateUiState.Downloading(manifest, null)
            try {
                val file = repository.download(manifest) { percent ->
                    _state.value = UpdateUiState.Downloading(manifest, percent)
                }
                _state.value = UpdateUiState.Ready(manifest, file.absolutePath)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = recordSafely(error, ErrorOperation.DOWNLOAD_UPDATE).asUiFailure()
            }
        }
    }

    fun install(path: String) {
        if (installInProgress) return
        val current = _state.value as? UpdateUiState.Ready ?: return
        if (path != current.apkPath) {
            reportImmediateInstallFailure(
                UpdateException(UpdateFailureReason.APK_INTEGRITY_FAILED)
            )
            return
        }
        val file = File(current.apkPath)
        if (!file.isFile) {
            reportImmediateInstallFailure(
                UpdateException(UpdateFailureReason.APK_INTEGRITY_FAILED)
            )
            return
        }
        installInProgress = true
        viewModelScope.launch {
            try {
                repository.verifyBeforeInstall(file, current.manifest)
                when (repository.installVerified(file)) {
                    InstallLaunchResult.Started -> Unit
                    InstallLaunchResult.PermissionRequired -> {
                        _state.value = current
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = recordSafely(error, ErrorOperation.INSTALL_UPDATE).asUiFailure()
            } finally {
                installInProgress = false
            }
        }
    }

    private suspend fun recordSafely(error: Throwable, operation: ErrorOperation): AppError {
        val classified = classify(error, operation)
        return try {
            errorStore.record(classified)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (storageError: Exception) {
            Log.w(
                "MaidManagerUpdates",
                "Update error persistence failed: ${storageError::class.java.name}"
            )
            classified
        }
    }

    private fun reportImmediateInstallFailure(error: UpdateException) {
        val classified = classify(error, ErrorOperation.INSTALL_UPDATE)
        _state.value = classified.asUiFailure()
        viewModelScope.launch {
            try {
                errorStore.record(classified)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (storageError: Exception) {
                Log.w(
                    "MaidManagerUpdates",
                    "Update error persistence failed: ${storageError::class.java.name}"
                )
            }
        }
    }

    private fun classify(error: Throwable, operation: ErrorOperation): AppError =
        AppErrorClassifier.classify(
            error,
            AppErrorContext(area = ErrorArea.UPDATE, operation = operation)
        )

    private fun AppError.asUiFailure(): UpdateUiState.Failed = UpdateUiState.Failed(
        title = title,
        explanation = "$userMessage\n\n原因：$explanation"
    )

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }
}
