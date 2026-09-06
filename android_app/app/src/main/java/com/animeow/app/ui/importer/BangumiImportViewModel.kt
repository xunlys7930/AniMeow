package com.animeow.app.ui.importer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.BangumiImportConflictStrategy
import com.animeow.app.data.BangumiImportSummary
import com.animeow.app.data.importer.BangumiCollectionImporter
import com.animeow.app.data.importer.BangumiCollectionPreview
import com.animeow.app.util.runCatchingCancellable
import com.animeow.app.util.withDataTransferLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BangumiImportUiState {
    data object Idle : BangumiImportUiState
    data class Loading(val username: String) : BangumiImportUiState
    data class Ready(
        val preview: BangumiCollectionPreview,
        val strategy: BangumiImportConflictStrategy = BangumiImportConflictStrategy.MERGE,
        val selectedSubjectIds: Set<Long> = preview.candidates.mapTo(linkedSetOf()) { it.item.subjectId },
        val importTags: Boolean = true,
        val message: String? = null,
    ) : BangumiImportUiState
    data class Importing(val request: Ready) : BangumiImportUiState {
        val preview: BangumiCollectionPreview get() = request.preview
    }
    data class Success(val summary: BangumiImportSummary) : BangumiImportUiState
    data class Error(val message: String) : BangumiImportUiState
}

class BangumiImportViewModel(application: Application) : AndroidViewModel(application) {
    private val importer = BangumiCollectionImporter((application as AniMeowApplication).database, application)
    private val _state = MutableStateFlow<BangumiImportUiState>(BangumiImportUiState.Idle)
    val state: StateFlow<BangumiImportUiState> = _state.asStateFlow()

    fun inspect(username: String) {
        val cleanUsername = username.trim()
        if (cleanUsername.isEmpty()) {
            _state.value = BangumiImportUiState.Error("请输入 Bangumi 用户名或 UID")
            return
        }
        viewModelScope.launch {
            _state.value = BangumiImportUiState.Loading(cleanUsername)
            _state.value = runCatchingCancellable { importer.preview(cleanUsername) }
                .fold(
                    onSuccess = { BangumiImportUiState.Ready(it) },
                    onFailure = { BangumiImportUiState.Error(it.userFacingMessage("读取公开收藏失败")) },
                )
        }
    }

    fun selectStrategy(strategy: BangumiImportConflictStrategy) {
        val current = _state.value as? BangumiImportUiState.Ready ?: return
        _state.value = current.copy(strategy = strategy)
    }

    fun toggleSubject(subjectId: Long) {
        val current = _state.value as? BangumiImportUiState.Ready ?: return
        _state.value = current.copy(
            selectedSubjectIds = if (subjectId in current.selectedSubjectIds) {
                current.selectedSubjectIds - subjectId
            } else {
                current.selectedSubjectIds + subjectId
            },
        )
    }

    fun toggleAll() {
        val current = _state.value as? BangumiImportUiState.Ready ?: return
        val allIds = current.preview.candidates.mapTo(linkedSetOf()) { it.item.subjectId }
        _state.value = current.copy(
            selectedSubjectIds = if (current.selectedSubjectIds.size == allIds.size) emptySet() else allIds,
        )
    }

    fun setImportTags(enabled: Boolean) {
        val current = _state.value as? BangumiImportUiState.Ready ?: return
        _state.value = current.copy(importTags = enabled)
    }

    fun confirmImport() {
        val current = _state.value as? BangumiImportUiState.Ready ?: return
        val selectedPreview = current.preview.copy(
            candidates = current.preview.candidates.filter { it.item.subjectId in current.selectedSubjectIds },
        )
        if (selectedPreview.candidates.isEmpty()) return
        viewModelScope.launch {
            val request = current.copy(preview = selectedPreview, message = null)
            _state.value = BangumiImportUiState.Importing(request)
            _state.value = runCatchingCancellable {
                withDataTransferLock {
                    importer.applyImport(selectedPreview, current.strategy, current.importTags)
                }
            }
                .fold(
                    onSuccess = { BangumiImportUiState.Success(it) },
                    onFailure = {
                        current.copy(message = it.userFacingMessage("导入失败，资料库未发生改变"))
                    },
                )
        }
    }

    fun reset() {
        _state.value = BangumiImportUiState.Idle
    }
}

private fun Throwable.userFacingMessage(fallback: String): String =
    message?.substringAfter("message\":\"")?.substringBefore('"')
        ?.takeIf(String::isNotBlank)
        ?: message?.takeIf(String::isNotBlank)
        ?: fallback
