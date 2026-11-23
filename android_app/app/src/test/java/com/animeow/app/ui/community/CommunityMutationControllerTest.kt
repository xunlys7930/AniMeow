package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CommunityMutationControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val session = CloudSession(1, "synthetic", "synthetic-token")
    private var stored: CloudSession? = session
    private val state = MutableStateFlow(CommunityUiState(session = session, draftText = "保留草稿"))
    private val controller = CommunityMutationController(scope, state, { stored }, { _, fallback -> fallback })
    @After fun close() { scope.cancel() }

    @Test fun startsBusySynchronouslyAndPreventsDuplicateMutation() {
        var writes = 0; val pending = CompletableDeferred<Unit>()
        controller.run("正在保存…") { request { writes++; pending.await() }; "保存成功" }
        controller.run("正在保存…") { writes++; "重复" }
        assertTrue(state.value.busy); assertEquals(1, writes)
        pending.complete(Unit); assertFalse(state.value.busy); assertEquals("保存成功", state.value.message)
    }

    @Test fun aLateResponseCannotApplyUiChangesOrClearANewerAccountsBusyState() {
        val pending = CompletableDeferred<Unit>(); var applied = false
        controller.run("旧操作") { request { withContext(NonCancellable) { pending.await() } }; applied = true; "旧结果" }
        controller.cancel(); stored = session.copy(userId = 2, token = "new-synthetic-token")
        state.value = state.value.copy(session = stored, busy = true, busyLabel = "新操作")
        pending.complete(Unit)
        assertFalse(applied); assertTrue(state.value.busy); assertEquals("新操作", state.value.busyLabel)
        assertEquals("保留草稿", state.value.draftText)
    }

    @Test fun aSessionChangedDuringLocalPreparationNeverSendsTheWrite() {
        val prepared = CompletableDeferred<Unit>(); var writes = 0
        controller.run("正在处理图片…") { prepared.await(); request { writes++ }; "完成" }
        stored = session.copy(token = "replacement-synthetic-token"); prepared.complete(Unit)
        assertEquals(0, writes); assertFalse(state.value.busy)
    }

    @Test fun aChangedStoredSessionRejectsTheResponseBeforeUiSessionRefresh() {
        val pending = CompletableDeferred<Unit>(); var applied = false
        controller.run("正在保存…") { request { pending.await() }; applied = true; "完成" }
        stored = session.copy(token = "replacement-synthetic-token"); pending.complete(Unit)
        assertFalse(applied); assertFalse(state.value.busy); assertNull(state.value.message)
    }

    @Test fun confirmedWriteAndFailedRefreshAreReportedSeparately() {
        controller.run("正在加入…") {
            request { Unit }; confirmed("已加入合成群")
            request { throw java.io.IOException("synthetic refresh failure") }
        }
        assertEquals("已加入合成群；列表刷新未完成，请手动刷新", state.value.message)
        assertFalse(state.value.busy); assertEquals("保留草稿", state.value.draftText)
    }

    @Test fun adminCommandsNeedTheCurrentAccountsRoleBeforeAndAfterTheWrite() {
        var writes = 0; var applied = false
        controller.run("管理操作", admin = true) { request { writes++ }; "完成" }; assertEquals(0, writes)
        state.value = state.value.copy(bootstrap = com.animeow.app.data.community.CommunityBootstrap(communityTestProfile().copy(isAdmin = true),
            com.animeow.app.data.community.CommunityGroup(1, "合成", "", "", true, true, null), com.animeow.app.data.community.CommunityLimits()))
        val pending = CompletableDeferred<Unit>()
        controller.run("管理操作", admin = true) { request { writes++; pending.await() }; applied = true; "完成" }
        state.value = state.value.copy(bootstrap = state.value.bootstrap!!.copy(currentUser = communityTestProfile()))
        pending.complete(Unit); assertFalse(applied); assertFalse(state.value.busy)
    }

    @Test fun cancellationAndFailureReleaseTheirOwnLockAndKeepDrafts() {
        controller.run("失败操作") { request { throw java.io.IOException("synthetic") } }
        assertFalse(state.value.busy); assertNotNull(state.value.message)
        controller.run("取消操作") { request { awaitCancellation() } }; controller.cancel()
        assertFalse(state.value.busy); assertEquals("保留草稿", state.value.draftText)
    }
}
