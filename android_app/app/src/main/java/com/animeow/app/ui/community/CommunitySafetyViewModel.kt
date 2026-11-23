package com.animeow.app.ui.community

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.data.community.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CommunitySafetyUiState(
    val summary: CommunityReviewSummary = CommunityReviewSummary(),
    val images: List<CommunityImageReview> = emptyList(),
    val users: List<CommunitySafetyUser> = emptyList(),
    val imageFilter: String = "pending",
    val userQuery: String = "",
    val nextImageId: Long? = null,
    val nextUserId: Long? = null,
    val userPage: Int = 1,
    val userLastPage: Int? = null,
    val imagePage: Int = 1,
    val imageLastPage: Int? = null,
    val locatingPage: Int? = null,
    val automatic: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val unavailable: Boolean = false,
    val message: String? = null,
)

class CommunitySafetyViewModel @JvmOverloads constructor(application: Application,
    apiBase: String = com.animeow.app.BuildConfig.CLOUD_API_BASE) : AndroidViewModel(application) {
    private val service = CommunityService(application, apiBase = apiBase)
    private val mutableState = MutableStateFlow(CommunitySafetyUiState())
    val state = mutableState.asStateFlow()
    val token: String get() = service.loadSession()?.token.orEmpty()
    private var userPages = CursorPageIndex()
    private var imagePages = CursorPageIndex()
    private var pageJob: Job? = null
    private var pageRequest = 0L
    private var pageSession: String? = null

    fun refreshSummary() = viewModelScope.launch {
        val session = service.loadSession() ?: return@launch
        try {
            val summary = service.reviewSummary(session)
            if (service.loadSession()?.token != session.token) return@launch
            mutableState.value = mutableState.value.copy(summary = summary, unavailable = false)
            CommunityReviewNotifications.markSeen(getApplication(), summary, session.token)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (error is CommunitySafetyUnavailableException) mutableState.value = mutableState.value.copy(unavailable = true)
        }
    }

    fun loadImages(filter: String = mutableState.value.imageFilter, more: Boolean = false) {
        if (mutableState.value.busy) return
        val changed = filter != mutableState.value.imageFilter
        if (changed) {
            imagePages = CursorPageIndex()
            mutableState.value = mutableState.value.copy(imageFilter = filter, imagePage = 1, imageLastPage = null, images = emptyList(), nextImageId = null)
        }
        loadImagePage(if (more) mutableState.value.imagePage + 1 else mutableState.value.imagePage)
    }

    fun loadUsers(query: String = mutableState.value.userQuery, more: Boolean = false) {
        if (mutableState.value.busy) return
        val clean = query.trim().take(64)
        if (clean != mutableState.value.userQuery) {
            userPages = CursorPageIndex()
            mutableState.value = mutableState.value.copy(userQuery = clean, userPage = 1, userLastPage = null, users = emptyList(), nextUserId = null)
        }
        loadUserPage(if (more) mutableState.value.userPage + 1 else mutableState.value.userPage)
    }

    fun loadUserPage(target: Int) = navigate { session ->
        val query = mutableState.value.userQuery
        val pages = userPages
        var next: Long? = null
        val result = pages.load(target, ::locating) { cursor ->
            val response = service.listSafetyUsers(session, query, cursor)
            if (service.loadSession()?.token != session.token) throw CancellationException("Account changed")
            next = response.nextAfterId
            CursorBatch(response.items, next)
        }
        mutableState.value = mutableState.value.copy(users = result.items, userPage = result.number,
            userLastPage = result.lastPage, nextUserId = next, unavailable = false)
    }

    fun loadImagePage(target: Int) = navigate { session -> readImagePage(session, target) }

    private suspend fun readImagePage(session: com.animeow.app.data.cloud.CloudSession, target: Int) {
        val filter = mutableState.value.imageFilter
        val pages = imagePages
        var next: Long? = null
        var automatic = mutableState.value.automatic
        val result = pages.load(target, ::locating) { cursor ->
            val response = service.imageReviews(session, filter, cursor)
            if (service.loadSession()?.token != session.token) throw CancellationException("Account changed")
            next = response.nextBeforeId
            automatic = response.automatic
            CursorBatch(response.items, next)
        }
        mutableState.value = mutableState.value.copy(images = result.items, imagePage = result.number,
            imageLastPage = result.lastPage, nextImageId = next, automatic = automatic, unavailable = false)
        refreshSummary()
    }

    fun cancelPageLoad() {
        pageJob?.cancel()
        pageRequest++
        mutableState.value = mutableState.value.copy(loading = false, locatingPage = null)
    }

    private fun locating(page: Int) { mutableState.value = mutableState.value.copy(locatingPage = page) }

    private fun navigate(action: suspend (com.animeow.app.data.cloud.CloudSession) -> Unit) {
        if (mutableState.value.busy) return
        val session = service.loadSession() ?: run {
            cancelPageLoad()
            userPages = CursorPageIndex(); imagePages = CursorPageIndex()
            mutableState.value = CommunitySafetyUiState(message = "请先登录云账号")
            return
        }
        if (pageSession != session.token) {
            userPages = CursorPageIndex(); imagePages = CursorPageIndex()
            mutableState.value = CommunitySafetyUiState(userQuery = mutableState.value.userQuery, imageFilter = mutableState.value.imageFilter)
            pageSession = session.token
        }
        pageJob?.cancel()
        val request = ++pageRequest
        pageJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, message = null)
            try { action(session) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (request == pageRequest) failure(error) }
            finally { if (request == pageRequest) mutableState.value = mutableState.value.copy(loading = false, locatingPage = null) }
        }
    }

    fun resolveImage(review: CommunityImageReview, approved: Boolean, note: String) = mutate("审核结果已保存") { session ->
        service.resolveImageReview(session, review.id, approved, note)
        if (service.loadSession()?.token != session.token) return@mutate
        readImagePage(session, mutableState.value.imagePage)
    }

    fun restrict(user: CommunitySafetyUser, capabilities: Set<String>, note: String, hours: Int?) = mutate("社区权限已更新，云端功能继续可用") { session ->
        service.updateRestrictions(session, user.profile.userId, capabilities, note, hours)
        val page = service.listSafetyUsers(session, user.profile.userId.toString())
        if (service.loadSession()?.token != session.token) return@mutate
        val updated = page.items.firstOrNull { it.profile.userId == user.profile.userId }
        mutableState.value = mutableState.value.copy(users = mutableState.value.users.map { if (it.profile.userId == updated?.profile?.userId) updated else it })
    }

    fun resetAvatar(user: CommunitySafetyUser, note: String) = mutate("头像已重置，待审头像已取消") { session ->
        service.resetUserAvatar(session, user.profile.userId, note)
        if (service.loadSession()?.token != session.token) return@mutate
        mutableState.value = mutableState.value.copy(users = mutableState.value.users.map { if (it.profile.userId == user.profile.userId) it.copy(profile = it.profile.copy(avatarUrl = null)) else it })
        refreshSummary()
    }

    private fun mutate(message: String, action: suspend (com.animeow.app.data.cloud.CloudSession) -> Unit) = viewModelScope.launch {
        if (mutableState.value.busy || mutableState.value.loading) return@launch
        val session = service.loadSession() ?: return@launch
        mutableState.value = mutableState.value.copy(busy = true, message = null)
        try {
            action(session)
            if (service.loadSession()?.token == session.token) mutableState.value = mutableState.value.copy(message = message)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure(error) }
        finally { mutableState.value = mutableState.value.copy(busy = false) }
    }

    fun clearMessage() { mutableState.value = mutableState.value.copy(message = null) }
    private fun failure(error: Exception) {
        mutableState.value = mutableState.value.copy(message = error.message ?: "社区管理暂时无法连接", unavailable = error is CommunitySafetyUnavailableException)
    }
}
