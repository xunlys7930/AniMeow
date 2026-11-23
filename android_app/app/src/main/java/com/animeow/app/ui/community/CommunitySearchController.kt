package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.community.CommunityPost
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class CommunitySearchController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<CommunityUiState>,
    private val loadSession: () -> CloudSession?,
    private val search: suspend (CloudSession, String, String, Boolean) -> List<CommunityPost>,
    private val failure: (Throwable, String) -> String,
) {
    private var version = 0L
    private var job: Job? = null
    fun clear() {
        version++; job?.cancel()
        state.update { it.copy(searchResults = emptyList(), searching = false, searchError = null) }
    }
    fun search(keyword: String, sort: String, featured: Boolean) {
        clear()
        val query = keyword.trim(); if (query.isEmpty()) return
        val session = state.value.session ?: return
        if (session != loadSession()) return
        val ticket = version
        fun owner() = ticket == version && state.value.session == session
        fun current() = owner() && loadSession() == session
        state.update { it.copy(searching = true) }
        job = scope.launch {
            try {
                delay(300)
                if (!current()) return@launch
                val results = search(session, query, sort, featured)
                if (current()) state.update { it.copy(searchResults = results.distinctBy(CommunityPost::id)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) {
                if (owner() && (loadSession() == session || error is CloudSessionExpiredException && error.isFor(session) && loadSession() == null)) {
                    val message = communityFailureMessage(failure(error, "搜索暂时不可用，请重试"), "搜索暂时不可用，请重试")
                    if (owner()) state.update { it.copy(searchError = message) }
                }
            } finally { if (ticket == version) state.update { it.copy(searching = false) } }
        }
    }
}
