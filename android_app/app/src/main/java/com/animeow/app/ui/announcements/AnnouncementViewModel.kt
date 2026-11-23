package com.animeow.app.ui.announcements

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.announcements.AnnouncementService
import com.animeow.app.data.announcements.AppAnnouncement
import com.animeow.app.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnnouncementUiState(
    val managing: Boolean = false,
    val refreshing: Boolean = false,
    val saving: Boolean = false,
    val managedItems: List<AppAnnouncement> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val publishedSerial: Int = 0,
)

class AnnouncementViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).announcementRepository
    private val service = AnnouncementService(application)
    val feed = repository.state
    private val _state = MutableStateFlow(AnnouncementUiState())
    val state = _state.asStateFlow()

    fun refresh(manage: Boolean = _state.value.managing) = viewModelScope.launch {
        if (_state.value.refreshing || _state.value.saving) return@launch
        _state.update { it.copy(refreshing = true, error = null) }
        runCatchingCancellable {
            if (manage) {
                val result = service.list(manage = true)
                _state.update { it.copy(managing = result.isDeveloper, managedItems = result.items) }
            } else {
                _state.update { it.copy(managing = false) }
            }
            repository.refresh()
            if (!feed.value.isDeveloper) _state.update { it.copy(managing = false, managedItems = emptyList()) }
        }.onFailure { error ->
            if (manage) { repository.clearDeveloperAccess(); _state.update { it.copy(managing = false) } }
            _state.update { it.copy(error = error.message ?: "公告加载失败，请重试") }
        }
        _state.update { it.copy(refreshing = false) }
    }

    fun markRead(id: Long? = null) = viewModelScope.launch { repository.markRead(id) }
    fun setNotifications(enabled: Boolean) = repository.setNotifications(enabled)
    fun clearMessage() { _state.update { it.copy(message = null) } }

    fun publish(kind: String, title: String, content: String, requestId: String) = viewModelScope.launch {
        if (_state.value.saving) return@launch
        _state.update { it.copy(saving = true, error = null) }
        runCatchingCancellable { service.publish(kind, title, content, requestId) }.fold(
            onSuccess = { _state.update { it.copy(publishedSerial = it.publishedSerial + 1, message = "已发布，用户可在消息与公告中查看") } },
            onFailure = { error -> _state.update { it.copy(error = error.message ?: "发布结果暂未确认，可重试这次发布") } },
        )
        _state.update { it.copy(saving = false) }
        if (_state.value.error == null) refresh()
    }

    fun withdraw(id: Long) = viewModelScope.launch {
        if (_state.value.saving) return@launch
        _state.update { it.copy(saving = true, error = null) }
        runCatchingCancellable { service.withdraw(id) }.fold(
            onSuccess = { _state.update { it.copy(message = "公告已撤回") } },
            onFailure = { error -> _state.update { it.copy(error = error.message ?: "撤回失败，请重试") } },
        )
        _state.update { it.copy(saving = false) }
        if (_state.value.error == null) refresh()
    }
}
