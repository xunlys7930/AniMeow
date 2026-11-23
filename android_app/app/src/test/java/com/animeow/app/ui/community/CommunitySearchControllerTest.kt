package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.community.CommunityPost
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CommunitySearchControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val session = CloudSession(1, "synthetic", "synthetic-token")
    private var stored: CloudSession? = session
    private val state = MutableStateFlow(CommunityUiState(session = session, draftText = "保留草稿", busy = true))
    private val queries = mutableListOf<String>()
    private var fetch: suspend (String) -> List<CommunityPost> = { listOf(communityTestPost(1)) }
    private val controller = CommunitySearchController(scope, state, { stored }, { _, query, _, _ -> queries += query; fetch(query) }, { _, fallback -> fallback })
    @After fun close() { scope.cancel() }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(3000) { while (!predicate()) delay(5) }

    @Test fun typingDebouncesAndBlankInputCancelsPendingSearch() = runBlocking {
        controller.search("a", "newest", false); controller.search("ab", "newest", false); controller.search("abc", "likes", true)
        await { !state.value.searching }; assertEquals(listOf("abc"), queries)
        controller.search("later", "newest", false); controller.search("  ", "newest", false); delay(350)
        assertEquals(1, queries.size); assertTrue(state.value.searchResults.isEmpty()); assertTrue(state.value.busy)
    }

    @Test fun olderResultsCannotReplaceANewerQuery() = runBlocking {
        val old = CompletableDeferred<List<CommunityPost>>()
        fetch = { if (it == "old") withContext(NonCancellable) { old.await() } else listOf(communityTestPost(2)) }
        controller.search("old", "newest", false); await { queries.size == 1 }
        controller.search("new", "newest", false); await { !state.value.searching }
        old.complete(listOf(communityTestPost(1)))
        assertEquals(2L, state.value.searchResults.single().id)
    }

    @Test fun closingSearchRejectsLateErrorsAndDoesNotAffectOtherBusyState() = runBlocking {
        val old = CompletableDeferred<List<CommunityPost>>(); fetch = { withContext(NonCancellable) { old.await() } }
        controller.search("old", "newest", false); await { queries.size == 1 }; controller.clear()
        old.completeExceptionally(java.io.IOException("synthetic"))
        assertNull(state.value.searchError); assertFalse(state.value.searching); assertTrue(state.value.busy)
        assertEquals("保留草稿", state.value.draftText)
    }

    @Test fun failuresHaveTheirOwnRetryStateAndDoNotBecomeEmptyResults() = runBlocking {
        fetch = { throw java.io.IOException("synthetic") }; controller.search("query", "newest", false)
        await { !state.value.searching }; assertNotNull(state.value.searchError)
        fetch = { listOf(communityTestPost(3), communityTestPost(3)) }; controller.search("query", "newest", false)
        await { !state.value.searching }; assertNull(state.value.searchError); assertEquals(1, state.value.searchResults.size)
    }

    @Test fun storedSessionChangesDuringDebouncePreventAnyDispatch() = runBlocking {
        controller.search("query", "newest", false); stored = session.copy(token = "new-synthetic-token")
        await { !state.value.searching }; assertTrue(queries.isEmpty()); assertTrue(state.value.searchResults.isEmpty())
    }
}
