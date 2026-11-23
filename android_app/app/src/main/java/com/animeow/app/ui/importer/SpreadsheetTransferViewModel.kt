package com.animeow.app.ui.importer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.importer.SpreadsheetConflictStrategy
import com.animeow.app.data.importer.SpreadsheetExportSummary
import com.animeow.app.data.importer.SpreadsheetField
import com.animeow.app.data.importer.SpreadsheetImportSummary
import com.animeow.app.data.importer.SpreadsheetPreview
import com.animeow.app.data.importer.SpreadsheetTransferService
import com.animeow.app.util.runCatchingCancellable
import com.animeow.app.util.withDataTransferLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SpreadsheetTransferUiState {
    data object Idle : SpreadsheetTransferUiState
    data object Inspecting : SpreadsheetTransferUiState
    data class Ready(
        val preview: SpreadsheetPreview,
        val mapping: Map<SpreadsheetField, Int>,
        val strategy: SpreadsheetConflictStrategy = SpreadsheetConflictStrategy.MERGE,
        val message: String? = null,
    ) : SpreadsheetTransferUiState
    data class Importing(val request: Ready) : SpreadsheetTransferUiState
    data object Exporting : SpreadsheetTransferUiState
    data class ImportSuccess(val summary: SpreadsheetImportSummary) : SpreadsheetTransferUiState
    data class ExportSuccess(val summary: SpreadsheetExportSummary) : SpreadsheetTransferUiState
    data class Error(val message: String) : SpreadsheetTransferUiState
}

class SpreadsheetTransferViewModel(application: Application) : AndroidViewModel(application) {
    private val service = SpreadsheetTransferService(
        context = application,
        database = (application as AniMeowApplication).database,
    )
    private val _state = MutableStateFlow<SpreadsheetTransferUiState>(SpreadsheetTransferUiState.Idle)
    val state: StateFlow<SpreadsheetTransferUiState> = _state.asStateFlow()

    fun inspect(uri: Uri) = viewModelScope.launch {
        _state.value = SpreadsheetTransferUiState.Inspecting
        _state.value = runCatchingCancellable { service.inspect(uri) }.fold(
            onSuccess = { SpreadsheetTransferUiState.Ready(it, it.suggestedMapping) },
            onFailure = { SpreadsheetTransferUiState.Error(it.message ?: "无法读取表格") },
        )
    }

    fun setMapping(field: SpreadsheetField, column: Int?) {
        val current = _state.value as? SpreadsheetTransferUiState.Ready ?: return
        val updated = current.mapping.toMutableMap().apply {
            if (column == null) remove(field) else put(field, column)
        }
        _state.value = current.copy(mapping = updated)
    }

    fun setStrategy(strategy: SpreadsheetConflictStrategy) {
        val current = _state.value as? SpreadsheetTransferUiState.Ready ?: return
        _state.value = current.copy(strategy = strategy)
    }

    fun importData() {
        val current = _state.value as? SpreadsheetTransferUiState.Ready ?: return
        viewModelScope.launch {
            _state.value = SpreadsheetTransferUiState.Importing(current.copy(message = null))
            _state.value = runCatchingCancellable {
                withDataTransferLock {
                    service.importRows(current.preview, current.mapping, current.strategy)
                }
            }.fold(
                onSuccess = { SpreadsheetTransferUiState.ImportSuccess(it) },
                onFailure = {
                    current.copy(message = it.message ?: "导入失败，资料库未发生改变")
                },
            )
        }
    }

    fun export(uri: Uri) = viewModelScope.launch {
        _state.value = SpreadsheetTransferUiState.Exporting
        _state.value = runCatchingCancellable { withDataTransferLock { service.export(uri) } }.fold(
            onSuccess = { SpreadsheetTransferUiState.ExportSuccess(it) },
            onFailure = { SpreadsheetTransferUiState.Error(it.message ?: "导出失败") },
        )
    }

    fun reset() {
        _state.value = SpreadsheetTransferUiState.Idle
    }
}
