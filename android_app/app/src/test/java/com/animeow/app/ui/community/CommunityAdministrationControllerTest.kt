package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.community.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CommunityAdministrationControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val session = CloudSession(1, "synthetic", "synthetic-token")
    private var storedSession: CloudSession? = session
    private val bootstrap = CommunityBootstrap(communityTestProfile().copy(isDeveloper = true), CommunityGroup(1, "合成", "", "", true, true, null), CommunityLimits())
    private val ordinary = communityTestProfile().copy(userId = 2, username = "synthetic-other")
    private val state = MutableStateFlow(CommunityUiState(session = session, bootstrap = bootstrap, draftText = "保留草稿", users = listOf(ordinary)))
    private val writes = mutableListOf<String>()
    private val wordPages = mutableListOf<Int>()
    private var users: suspend (String?) -> List<CommunityProfile> = { listOf(ordinary) }
    private var words: suspend (Int, String) -> SensitiveWordPage = { page, _ -> SensitiveWordPage(emptyList(), 0, page, 50) }
    private var add: suspend () -> Unit = {}
    private val api = object : CommunityAdministrationApi {
        override suspend fun users(session: CloudSession, query: String?, role: String?) = users(query)
        override suspend fun words(session: CloudSession, page: Int, search: String): SensitiveWordPage { wordPages += page; return words(page, search) }
        override suspend fun logs(session: CloudSession) = emptyList<CommunityModerationLogEntry>()
        override suspend fun reviews(session: CloudSession) = emptyList<CommunityResolvedReview>()
        override suspend fun setAdmin(session: CloudSession, userId: Long, admin: Boolean) { writes += "admin:$userId:$admin" }
        override suspend fun addWord(session: CloudSession, word: String) { writes += "add:$word"; add() }
        override suspend fun deleteWord(session: CloudSession, id: Long) { writes += "delete:$id" }
        override suspend fun importWords(session: CloudSession, words: List<String>): Int { writes += words.joinToString(); return words.size }
        override suspend fun overrideReview(session: CloudSession, id: Long, violation: Boolean, note: String) { writes += "override:$id" }
    }
    private var feedFailure = false
    private val controller = CommunityAdministrationController(scope, state, api, { storedSession }, { error, _ -> error.message.orEmpty() },
        refreshFeed = { _, _ -> if (feedFailure) throw java.io.IOException("synthetic failure") })
    @After fun close() { scope.cancel() }

    @Test fun oldUserSearchCannotOverwriteTheNewFilter() = runBlocking {
        val old = CompletableDeferred<List<CommunityProfile>>()
        users = { query -> if (query == "old") withContext(NonCancellable) { old.await() } else listOf(ordinary.copy(username = "new-result")) }
        controller.users("old"); controller.users("new")
        old.complete(listOf(ordinary.copy(username = "old-result")))
        assertEquals("new-result", state.value.users.single().username); assertFalse(state.value.usersLoading)
    }

    @Test fun closingTheDialogRejectsItsLateResponseAndKeepsOtherState() = runBlocking {
        val pending = CompletableDeferred<List<CommunityProfile>>()
        users = { withContext(NonCancellable) { pending.await() } }
        controller.users(); controller.closeUsers(); pending.complete(listOf(ordinary))
        assertTrue(state.value.users.isEmpty()); assertFalse(state.value.usersLoading)
        assertEquals("保留草稿", state.value.draftText)
    }

    @Test fun losingDeveloperPermissionClearsPrivilegedDataAndStopsNewOperations() {
        controller.users()
        state.value = state.value.copy(bootstrap = bootstrap.copy(currentUser = communityTestProfile().copy(isDeveloper = false)))
        assertTrue(state.value.users.isEmpty())
        controller.setAdmin(2, true); controller.addWord("合成"); controller.importWords(listOf("合成")); controller.users()
        assertTrue(writes.isEmpty()); assertFalse(state.value.usersLoading)
    }

    @Test fun accountChangeDuringAWriteCannotChangeTheNewAccountsBusyStateOrData() = runBlocking {
        val pending = CompletableDeferred<Unit>(); add = { pending.await() }
        controller.addWord("旧账号操作")
        val replacement = CloudSession(3, "new-synthetic", "new-synthetic-token")
        storedSession = replacement
        state.value = state.value.copy(session = replacement, bootstrap = bootstrap.copy(currentUser = communityTestProfile().copy(userId = 3, isDeveloper = true)),
            users = listOf(ordinary.copy(username = "should-clear")))
        assertFalse(state.value.administrationBusy); assertTrue(state.value.users.isEmpty())
        state.value = state.value.copy(busy = true, busyLabel = "新账号其他操作")
        pending.complete(Unit)
        assertTrue(state.value.busy); assertEquals("新账号其他操作", state.value.busyLabel)
        assertNull(state.value.message)
    }

    @Test fun aConfirmedRoleWriteInvalidatesAnOlderUserListResponse() = runBlocking {
        val pending = CompletableDeferred<List<CommunityProfile>>()
        users = { withContext(NonCancellable) { pending.await() } }
        controller.users(); controller.setAdmin(2, true)
        pending.complete(listOf(ordinary))
        assertTrue(state.value.users.single().isAdmin); assertFalse(state.value.usersLoading)
        assertEquals(listOf("admin:2:true"), writes)
    }

    @Test fun aDeletedLastPageFetchesTheActualLastPageWithoutOverflow() {
        words = { page, _ -> SensitiveWordPage(listOf(SensitiveWord(page.toLong(), "合成$page", "")), 55, page, 50) }
        controller.wordPage(5)
        assertEquals(listOf(5, 2), wordPages); assertEquals(2, state.value.sensitiveWordsPage)
        assertEquals("合成2", state.value.sensitiveWords.single().word)
        wordPages.clear()
        words = { page, _ -> SensitiveWordPage(emptyList(), Int.MAX_VALUE, page, 50) }
        controller.wordPage(1)
        assertEquals(listOf(1), wordPages); assertEquals(Int.MAX_VALUE, state.value.sensitiveWordsTotal)
    }

    @Test fun repeatedManagementWritesAreBlockedAndDoNotUseTheComposerBusyFlag() = runBlocking {
        val pending = CompletableDeferred<Unit>(); add = { pending.await() }
        controller.addWord("合成"); controller.addWord("合成")
        assertEquals(1, writes.size); assertTrue(state.value.administrationBusy); assertFalse(state.value.busy)
        assertEquals("保留草稿", state.value.draftText)
        pending.complete(Unit)
        assertFalse(state.value.administrationBusy); assertEquals("敏感词已添加", state.value.message)
    }

    @Test fun serverConfirmedChangesAreNotMisreportedAsWriteFailuresWhenOnlyRefreshFails() {
        feedFailure = true
        controller.overrideReview(1, true, "")
        assertEquals(listOf("override:1"), writes)
        assertEquals("处理结果已修改；列表刷新未完成，请手动刷新", state.value.message)
        assertFalse(state.value.administrationBusy)
    }

    @Test fun managementReadFailureHasItsOwnErrorWithoutClearingOtherBusyState() {
        users = { throw java.io.IOException("读取失败 /private/token") }
        state.value = state.value.copy(busy = true, busyLabel = "其他操作")
        controller.users()
        assertEquals("读取用户列表失败，请重试", state.value.usersError)
        assertTrue(state.value.busy); assertEquals("其他操作", state.value.busyLabel)
    }
}
