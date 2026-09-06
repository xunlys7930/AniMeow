package com.animeow.app.ui.importer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.importer.LegacyImportUiState
import com.animeow.app.data.importer.PreparedLegacyImport
import com.animeow.app.util.withDataTransferLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LegacyImportViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val importer = (application as AniMeowApplication).legacyBackupImporter
    private val _state = MutableStateFlow<LegacyImportUiState>(LegacyImportUiState.Idle)
    val state: StateFlow<LegacyImportUiState> = _state.asStateFlow()

    private var preparedImport: PreparedLegacyImport? = null
    private var importInFlight = false

    fun inspect(uri: Uri) {
        discardPreparedImport()
        _state.value = LegacyImportUiState.Inspecting(
            sourceName = importer.queryDisplayName(uri) ?: "原版备份",
        )
        viewModelScope.launch {
            try {
                val prepared = importer.prepare(uri)
                preparedImport = prepared
                _state.value = LegacyImportUiState.Ready(prepared.summary)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.value = LegacyImportUiState.Error(
                    error.message ?: "无法读取这个原版备份",
                )
            }
        }
    }

    fun confirmImport() {
        if (importInFlight) return
        val prepared = preparedImport ?: return
        importInFlight = true
        _state.value = LegacyImportUiState.Importing(prepared.summary)
        viewModelScope.launch {
            try {
                val result = withDataTransferLock { importer.importPrepared(prepared) }
                preparedImport = null
                _state.value = LegacyImportUiState.Success(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                preparedImport = prepared
                _state.value = LegacyImportUiState.Ready(
                    summary = prepared.summary,
                    message = error.message ?: "导入失败，当前资料库没有被修改，可直接重试",
                )
            } finally {
                importInFlight = false
            }
        }
    }

    fun dismiss() {
        if (importInFlight) return
        discardPreparedImport()
        _state.value = LegacyImportUiState.Idle
    }

    private fun discardPreparedImport() {
        preparedImport?.let(importer::discard)
        preparedImport = null
    }

    override fun onCleared() {
        discardPreparedImport()
        super.onCleared()
    }
}
