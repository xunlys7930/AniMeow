package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.CloudSessionExpiredException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class CommunityMutation internal constructor(val session: CloudSession, private val current: () -> Boolean) {
    internal var confirmedMessage: String? = null
        private set
    fun confirmed(message: String) { confirmedMessage = message }
    suspend fun <T> request(call: suspend () -> T): T {
        if (!current()) throw CancellationException("Community operation no longer belongs to this session")
        val result = call()
        if (!current()) throw CancellationException("Community response no longer belongs to this session")
        return result
    }
}

/** Member mutations own the shared composer lock; unrelated reads never clear it. */
internal class CommunityMutationController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<CommunityUiState>,
    private val loadSession: () -> CloudSession?,
    private val failure: (Throwable, String) -> String,
) {
    private var generation = 0L
    private var job: Job? = null
    fun cancel() { generation++; job?.cancel(); state.update { it.copy(busy = false, busyLabel = null) } }

    fun run(label: String, admin: Boolean = false, action: suspend CommunityMutation.() -> String?) {
        val snapshot = state.value
        val session = snapshot.session ?: run { state.update { it.copy(message = "请先登录云账号") }; return }
        if (snapshot.busy || loadSession() != session) return
        fun authorized() = !admin || state.value.currentUser?.let { it.userId == session.userId && (it.isAdmin || it.isDeveloper) } == true
        if (!authorized()) return
        val ticket = ++generation
        fun owner() = ticket == generation && state.value.session == session
        fun current() = owner() && loadSession() == session && authorized()
        val operation = CommunityMutation(session, ::current)
        state.update { it.copy(busy = true, busyLabel = label, message = null) }
        job = scope.launch {
            try {
                if (!current()) return@launch
                val message = operation.action()
                if (current()) state.update { it.copy(message = message) }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) {
                val expired = error is CloudSessionExpiredException && error.isFor(session) && loadSession() == null
                if (owner() && (loadSession() == session || expired)) {
                    val message = operation.confirmedMessage?.let { "$it；列表刷新未完成，请手动刷新" }
                        ?: failure(error, "操作结果未确认，请刷新后核对")
                    if (owner()) state.update { it.copy(message = communityFailureMessage(message, "操作未完成，请稍后重试")) }
                }
            } finally { if (owner()) state.update { it.copy(busy = false, busyLabel = null) } }
        }
    }
}
