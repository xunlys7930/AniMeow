package com.animeow.app.ui.community

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.community.CommunityInboxFilter
import com.animeow.app.data.community.CommunityInboxUnavailableException
import com.animeow.app.data.community.CommunityNotification
import com.animeow.app.data.community.CommunityService
import com.animeow.app.data.community.mergeCommunityInbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CommunityInboxUiState(
    val session: CloudSession? = null,
    val filter: CommunityInboxFilter = CommunityInboxFilter.ALL,
    val items: List<CommunityNotification> = emptyList(),
    val unreadCount: Int = 0,
    val latestId: Long? = null,
    val nextBeforeId: Long? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val markingRead: Boolean = false,
    val available: Boolean = true,
    val error: String? = null,
    val message: String? = null,
)

class CommunityInboxViewModel(application: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val service = CommunityService(application)
    private val mutableState = MutableStateFlow(CommunityInboxUiState(
        session = service.loadSession(),
        filter = CommunityInboxFilter.entries.firstOrNull { it.key == savedStateHandle.get<String>("inbox_filter") } ?: CommunityInboxFilter.ALL,
    ))
    val state = mutableState.asStateFlow()
    private val requests = Mutex()
    private var pageJob: Job? = null
    private var summaryJob: Job? = null
    private var lastSummaryAt = 0L

    private fun session(): CloudSession? {
        val current = service.loadSession()
        if (current?.token != mutableState.value.session?.token) {
            pageJob?.cancel()
            summaryJob?.cancel()
            lastSummaryAt = 0
            mutableState.value = CommunityInboxUiState(session = current, filter = mutableState.value.filter)
        }
        return current
    }

    private fun isCurrent(session: CloudSession) = service.loadSession()?.token == session.token

    fun refreshSummary() {
        val session = session() ?: return
        if (!service.isConfigured || summaryJob?.isActive == true) return
        val cooldown = if (mutableState.value.available) 30_000 else 10 * 60_000
        if (System.currentTimeMillis() - lastSummaryAt < cooldown) return
        lastSummaryAt = System.currentTimeMillis()
        summaryJob = viewModelScope.launch {
            try {
                requests.withLock {
                    if (!isCurrent(session)) return@withLock
                    val summary = service.inboxSummary(session)
                    if (isCurrent(session)) mutableState.update { it.copy(unreadCount = summary.unreadCount, latestId = summary.latestId, available = true) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (!isCurrent(session) || error is CloudSessionExpiredException) session()
                else if (error is CommunityInboxUnavailableException) mutableState.update { it.copy(available = false, unreadCount = 0) }
            }
        }
    }

    fun selectFilter(filter: CommunityInboxFilter) {
        if (filter == mutableState.value.filter) return
        savedStateHandle["inbox_filter"] = filter.key
        mutableState.update { it.copy(filter = filter, items = emptyList(), loaded = false, nextBeforeId = null) }
        refresh()
    }

    fun refresh() = loadPage(reset = true)
    fun loadMore() = loadPage(reset = false)
    private fun loadPage(reset: Boolean) {
        val session = session() ?: return
        if (!service.isConfigured) {
            mutableState.update { it.copy(error = "社区服务暂不可用", available = false) }
            return
        }
        val previous = mutableState.value
        if (!reset && (previous.loading || previous.nextBeforeId == null)) return
        pageJob?.cancel()
        mutableState.update { it.copy(loading = true, error = null) }
        pageJob = viewModelScope.launch {
            try {
                requests.withLock {
                    if (!isCurrent(session)) return@withLock
                    val page = service.listNotifications(session, previous.filter, if (reset) null else previous.nextBeforeId)
                    check(page.nextBeforeId == null || reset || page.nextBeforeId < checkNotNull(previous.nextBeforeId)) { "消息分页暂时不可用，请刷新后重试" }
                    if (isCurrent(session)) mutableState.update { it.copy(
                        items = if (reset) page.items else mergeCommunityInbox(it.items, page.items),
                        nextBeforeId = page.nextBeforeId, unreadCount = page.summary.unreadCount, latestId = page.summary.latestId,
                        loaded = true, loading = false, available = true, error = null,
                    ) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (!isCurrent(session) || error is CloudSessionExpiredException) session()
                else mutableState.update { it.copy(loading = false, error = error.message ?: "消息加载失败，请重试", available = error !is CommunityInboxUnavailableException) }
            }
        }
    }

    fun markRead(notification: CommunityNotification) {
        if (!notification.isRead) markRead(ids = listOf(notification.id))
    }
    fun markAllRead() {
        mutableState.value.latestId?.let { markRead(throughId = it) }
    }
    private fun markRead(ids: List<Long> = emptyList(), throughId: Long? = null) {
        val session = session() ?: return
        viewModelScope.launch {
            requests.withLock {
                if (!isCurrent(session)) return@withLock
                mutableState.update { it.copy(markingRead = true) }
                try {
                    val summary = service.markNotificationsRead(session, ids, throughId)
                    if (isCurrent(session)) mutableState.update { it.copy(
                        items = it.items.map { item -> if (item.id in ids || (throughId != null && item.id <= throughId)) item.copy(isRead = true) else item },
                        unreadCount = summary.unreadCount, latestId = summary.latestId,
                    ) }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (!isCurrent(session) || error is CloudSessionExpiredException) session()
                    else mutableState.update { it.copy(message = "标记已读失败，请稍后重试") }
                } finally {
                    if (isCurrent(session)) mutableState.update { it.copy(markingRead = false) }
                }
            }
        }
    }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }
}
