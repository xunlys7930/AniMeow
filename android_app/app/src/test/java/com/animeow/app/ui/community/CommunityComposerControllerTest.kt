package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.community.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CommunityComposerControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val session = CloudSession(1, "synthetic", "synthetic-token")
    private var stored: CloudSession? = session
    private val state = MutableStateFlow(CommunityUiState(session = session,
        bootstrap = CommunityBootstrap(communityTestProfile(), CommunityGroup(1, "合成群", "", "", true, true, null), CommunityLimits()),
        draftText = "待发帖", draftTags = listOf("合成标签")))
    private val saved = mutableListOf<CommunityComposerDraft>()
    private var writes = 0
    private var cleanups = 0
    private var onCreate: suspend () -> Unit = {}
    private var onRelease: suspend () -> Unit = {}
    private val controller = CommunityComposerController(scope, state, { stored },
        compress = { _, _ -> error("No images in these cases") },
        create = { _, _, _, _ -> writes++; onCreate(); communityTestPost(8) },
        saveDraft = { saved += it }, release = { cleanups++; onRelease() },
        failure = { _, fallback -> fallback }, published = { _, _ -> })
    @After fun close() { scope.cancel() }

    @Test fun aReferenceOnlyDraftCanBePublishedAndItsReferenceIsClearedOnlyAfterConfirmation() {
        state.value = state.value.copy(draftText = "", draftWatchReferenceId = 42)
        val pending = CompletableDeferred<Unit>(); onCreate = { pending.await() }
        controller.publish(); assertEquals(1, writes); assertEquals(42L, state.value.draftWatchReferenceId)
        pending.complete(Unit)
        assertNull(state.value.draftWatchReferenceId); assertEquals(CommunityComposerDraft(), saved.last())
    }
    @Test fun aReferenceStillBeingVerifiedCannotPublishAPartialPost() {
        state.value = state.value.copy(watchShareLoading = true)
        controller.publish()
        assertEquals(0, writes); assertFalse(state.value.busy)
    }

    @Test fun duplicateSendIsBlockedAndOnlyTheConfirmedDraftIsCleared() {
        val pending = CompletableDeferred<Unit>(); onCreate = { pending.await() }
        controller.publish(); controller.publish()
        assertTrue(state.value.busy); assertEquals(1, writes); assertTrue(saved.isEmpty())
        pending.complete(Unit)
        assertFalse(state.value.busy); assertEquals("", state.value.draftText)
        assertEquals(listOf(CommunityComposerDraft()), saved); assertEquals(1, cleanups)
        assertEquals(8L, state.value.publishedPostId)
    }

    @Test fun failureKeepsEveryDraftFieldAndDoesNotReleaseImages() {
        onCreate = { throw java.io.IOException("synthetic failure") }
        val draft = state.value.composerDraft(); controller.publish()
        assertEquals(draft, state.value.composerDraft()); assertTrue(saved.isEmpty()); assertEquals(0, cleanups)
        assertFalse(state.value.busy); assertTrue(state.value.message!!.contains("确认帖子是否已出现"))
    }

    @Test fun newerDraftSurvivesAConfirmedOlderPublication() {
        val pending = CompletableDeferred<Unit>(); onCreate = { pending.await() }
        controller.publish(); state.value = state.value.copy(draftText = "后来的草稿", draftTags = listOf("新标签"))
        pending.complete(Unit)
        assertEquals("后来的草稿", state.value.draftText); assertEquals(listOf("新标签"), state.value.draftTags)
        assertTrue(saved.isEmpty()); assertEquals(0, cleanups); assertFalse(state.value.busy)
    }

    @Test fun switchingAccountRejectsOldCompletionAndPreservesNewBusyState() {
        val pending = CompletableDeferred<Unit>(); onCreate = { pending.await() }
        controller.publish(); controller.cancel()
        stored = CloudSession(2, "other", "other-token")
        state.value = state.value.copy(session = stored, draftText = "新账号草稿", busy = true, busyLabel = "新操作")
        pending.complete(Unit)
        assertEquals("新账号草稿", state.value.draftText); assertTrue(state.value.busy)
        assertEquals("新操作", state.value.busyLabel); assertNull(state.value.publishedPostId)
        assertTrue(saved.isEmpty()); assertEquals(0, cleanups)
    }

    @Test fun changedStoredAccountNeverDispatchesARequest() {
        stored = CloudSession(2, "other", "other-token"); controller.publish()
        assertEquals(0, writes); assertFalse(state.value.busy); assertEquals("待发帖", state.value.draftText)
    }

    @Test fun cleanupFailureDoesNotTurnAConfirmedPublicationIntoAFailure() {
        onRelease = { throw java.io.IOException("synthetic cleanup failure") }; controller.publish()
        assertEquals("发布成功", state.value.message); assertEquals(8L, state.value.publishedPostId)
        assertFalse(state.value.busy); assertEquals(1, writes)
    }

    @Test fun publishedPostIsNotInsertedIntoAnUnrelatedFilter() {
        state.value = state.value.copy(feedFeaturedOnly = true); controller.publish()
        assertTrue(state.value.posts.isEmpty()); assertEquals(8L, state.value.publishedPostId)
    }

    @Test fun emptyDraftOrAnImportInProgressNeverPublishes() {
        state.value = state.value.copy(draftText = "", draftTags = emptyList()); controller.publish(); assertEquals(0, writes)
        state.value = state.value.copy(draftText = "内容", importingImages = true); controller.publish(); assertEquals(0, writes)
    }

    @Test fun cancellationKeepsTheDraftAndReleasesOnlyItsOwnBusyState() {
        onCreate = { awaitCancellation() }; controller.publish(); scope.cancel()
        assertFalse(state.value.busy); assertEquals("待发帖", state.value.draftText)
        assertTrue(saved.isEmpty()); assertEquals(0, cleanups)
    }

    @Test fun changedStoredSessionRejectsSuccessBeforeTheScreenHasRefreshed() {
        val pending = CompletableDeferred<Unit>(); onCreate = { pending.await() }
        controller.publish(); stored = session.copy(token = "new-synthetic-token"); pending.complete(Unit)
        assertFalse(state.value.busy); assertEquals("待发帖", state.value.draftText)
        assertNull(state.value.publishedPostId); assertTrue(saved.isEmpty())
    }
}
