package com.animeow.app.ui.diagnostics

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.local.DiagnosticLogEntity
import com.animeow.app.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val enabled: Boolean = false,
    val logs: List<DiagnosticLogEntity> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
) {
    val copyText: String
        get() = buildString {
            appendLine("# AniMeow 诊断日志")
            logs.forEach { log ->
                appendLine("[${log.timestamp}] [${log.level}] ${log.message}")
                log.stackTrace?.let(::appendLine)
                appendLine("---")
            }
        }
}

class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {
    private val service = (application as AniMeowApplication).operationLog
    private val _state = MutableStateFlow(DiagnosticsUiState(enabled = service.enabled))
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true, message = null)
        _state.value = runCatchingCancellable { service.setEnabled(enabled) }.fold(
            onSuccess = {
                _state.value.copy(
                    enabled = enabled,
                    busy = false,
                    message = if (enabled) "操作日志已开启" else "操作日志已关闭",
                )
            },
            onFailure = { _state.value.copy(busy = false, message = it.message ?: "日志设置保存失败") },
        )
    }

    fun refresh() = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true)
        _state.value = runCatchingCancellable { service.loadLogs() }.fold(
            onSuccess = { _state.value.copy(logs = it, busy = false) },
            onFailure = { _state.value.copy(busy = false, message = it.message ?: "读取日志失败") },
        )
    }

    fun clear() = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true, message = null)
        _state.value = runCatchingCancellable { service.clear() }.fold(
            onSuccess = { _state.value.copy(logs = emptyList(), busy = false, message = "诊断日志已清空") },
            onFailure = { _state.value.copy(busy = false, message = it.message ?: "诊断日志清空失败") },
        )
    }

    fun export(uri: Uri) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true)
        _state.value = runCatchingCancellable { service.export(uri) }.fold(
            onSuccess = { count -> _state.value.copy(busy = false, message = "已导出 $count 条日志") },
            onFailure = { _state.value.copy(busy = false, message = it.message ?: "导出日志失败") },
        )
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
