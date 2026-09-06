package com.animeow.app.ui.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.backup.NativeBackupSummary
import com.animeow.app.data.backup.NativeRestoreSelection
import com.animeow.app.data.backup.PreparedNativeRestore
import com.animeow.app.util.withDataTransferLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface NativeBackupUiState {
    data object Idle : NativeBackupUiState
    data object Exporting : NativeBackupUiState
    data class ExportSuccess(val summary: NativeBackupSummary) : NativeBackupUiState
    data object Inspecting : NativeBackupUiState
    data class RestoreReady(
        val prepared: PreparedNativeRestore,
        val summary: NativeBackupSummary,
        val selection: NativeRestoreSelection = NativeRestoreSelection(),
        val restoring: Boolean = false,
        val message: String? = null,
    ) : NativeBackupUiState
    data object Restoring : NativeBackupUiState
    data class RestoreSuccess(
        val summary: NativeBackupSummary,
        val selection: NativeRestoreSelection,
    ) : NativeBackupUiState
    data class Error(val message: String) : NativeBackupUiState
}

class NativeBackupViewModel(application: Application) : AndroidViewModel(application) {
    private val service = (application as AniMeowApplication).nativeBackupService
    private val _state = MutableStateFlow<NativeBackupUiState>(NativeBackupUiState.Idle)
    val state: StateFlow<NativeBackupUiState> = _state.asStateFlow()
    private var prepared: PreparedNativeRestore? = null
    private var restoreInFlight = false

    fun export(uri: Uri) = viewModelScope.launch {
        _state.value = NativeBackupUiState.Exporting
        try {
            _state.value = NativeBackupUiState.ExportSuccess(withDataTransferLock { service.export(uri) })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _state.value = NativeBackupUiState.Error(error.message ?: "备份导出失败")
        }
    }

    fun inspectRestore(uri: Uri) = viewModelScope.launch {
        discardPrepared()
        _state.value = NativeBackupUiState.Inspecting
        try {
            val result = service.prepareRestore(uri)
            prepared = result
            _state.value = NativeBackupUiState.RestoreReady(result, result.summary)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _state.value = NativeBackupUiState.Error(error.message ?: "无法读取原生备份")
        }
    }

    fun confirmRestore(selection: NativeRestoreSelection) {
        if (selection.isEmpty || restoreInFlight) return
        val snapshot = prepared ?: return
        val ready = _state.value as? NativeBackupUiState.RestoreReady ?: return
        restoreInFlight = true
        _state.value = ready.copy(selection = selection, restoring = true, message = null)
        viewModelScope.launch {
            try {
                prepared = null
                _state.value = NativeBackupUiState.RestoreSuccess(
                    withDataTransferLock { service.restore(snapshot, selection) },
                    selection,
                )
            } catch (error: CancellationException) {
                prepared = snapshot
                throw error
            } catch (error: Throwable) {
                prepared = snapshot
                _state.value = ready.copy(
                    selection = selection,
                    restoring = false,
                    message = error.message ?: "恢复失败，原资料库已回滚，可直接重试",
                )
            } finally {
                restoreInFlight = false
            }
        }
    }

    fun dismiss() {
        if (restoreInFlight) return
        discardPrepared()
        _state.value = NativeBackupUiState.Idle
    }

    private fun discardPrepared() {
        prepared?.let(service::discard)
        prepared = null
    }

    override fun onCleared() {
        discardPrepared()
        super.onCleared()
    }
}
