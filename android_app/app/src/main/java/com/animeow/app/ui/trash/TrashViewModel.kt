package com.animeow.app.ui.trash

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.local.AnimeEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TrashOperationType {
    RESTORE,
    PERMANENT_DELETE,
    EMPTY,
}

data class TrashOperation(
    val type: TrashOperationType,
    val animeId: Long? = null,
)

class TrashViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AniMeowApplication
    private val repository = app.libraryRepository
    private val _operation = MutableStateFlow<TrashOperation?>(null)
    val operation: StateFlow<TrashOperation?> = _operation.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    val deletedAnimes: StateFlow<List<AnimeEntity>> = repository.observeDeletedAnimes().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun restore(animeId: Long) {
        launchOperation(TrashOperation(TrashOperationType.RESTORE, animeId)) {
            val restored = repository.restoreAnime(animeId)
                ?: error("作品已不存在，可能已在其他位置被清理")
            val reminderReady = try {
                app.reminderScheduler.schedule(restored)
                true
            } catch (error: Exception) {
                app.operationLog.recordError(error, "回收站 · 恢复提醒")
                false
            }
            if (reminderReady) {
                "已恢复《${restored.title}》"
            } else {
                "作品已恢复；提醒重建失败，请前往提醒管理重新同步"
            }
        }
    }

    fun permanentlyDelete(animeId: Long, onSuccess: () -> Unit = {}) {
        launchOperation(TrashOperation(TrashOperationType.PERMANENT_DELETE, animeId), onSuccess) {
            val deleted = repository.permanentlyDeleteAnime(animeId)
                ?: error("作品已不存在，可能已被清理")
            cancelReminderSafely(animeId, "回收站 · 永久删除后清理提醒")
            "已永久删除《${deleted.title}》"
        }
    }

    fun emptyTrash(onSuccess: () -> Unit = {}) {
        launchOperation(TrashOperation(TrashOperationType.EMPTY), onSuccess) {
            val deletedIds = repository.emptyTrash()
            deletedIds.forEach { animeId ->
                cancelReminderSafely(animeId, "回收站 · 清空后清理提醒")
            }
            if (deletedIds.isEmpty()) "回收站已经是空的" else "已永久清理 ${deletedIds.size} 部作品"
        }
    }

    private fun launchOperation(
        next: TrashOperation,
        onSuccess: () -> Unit = {},
        action: suspend () -> String,
    ) {
        if (_operation.value != null) return
        _operation.value = next
        viewModelScope.launch {
            try {
                _messages.emit(action())
                onSuccess()
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalArgumentException) {
                _messages.emit(error.message?.takeIf(String::isNotBlank) ?: "操作失败")
            } catch (error: IllegalStateException) {
                _messages.emit(error.message?.takeIf(String::isNotBlank) ?: "操作失败")
            } catch (error: Exception) {
                app.operationLog.recordError(error, "回收站")
                _messages.emit("操作失败，请稍后重试")
            } finally {
                _operation.value = null
            }
        }
    }

    private fun cancelReminderSafely(animeId: Long, screen: String) {
        try {
            app.reminderScheduler.cancel(animeId)
        } catch (error: Exception) {
            app.operationLog.recordError(error, screen)
        }
    }
}
