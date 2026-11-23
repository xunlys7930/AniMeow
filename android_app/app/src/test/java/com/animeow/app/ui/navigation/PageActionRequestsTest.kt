package com.animeow.app.ui.navigation

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class PageActionRequestsTest {
    @Test fun actionsCanOnlyTargetTheirOwnPage() {
        val requests = PageActionRequests(AppDestination.TRACKER, SavedStateHandle())
        assertFalse(requests.request(PageAction.COMPOSE_POST)); assertNull(requests.pending.value)
        assertTrue(requests.request(PageAction.ADD_GAME)); assertTrue(requests.isPending(PageAction.ADD_GAME))
    }

    @Test fun theLatestTapWinsAndAnOlderConsumerCannotClearIt() {
        val requests = PageActionRequests(AppDestination.TRACKER, SavedStateHandle())
        requests.request(PageAction.SEARCH_GAME); requests.request(PageAction.ADD_AUDIO)
        assertFalse(requests.consume(PageAction.SEARCH_GAME)); assertTrue(requests.isPending(PageAction.ADD_AUDIO))
        assertTrue(requests.consume(PageAction.ADD_AUDIO)); assertFalse(requests.consume(PageAction.ADD_AUDIO))
    }

    @Test fun aPendingActionRestoresButAConsumedActionDoesNotReplay() {
        val saved = SavedStateHandle(); val requests = PageActionRequests(AppDestination.COMMUNITY, saved)
        requests.request(PageAction.COMPOSE_POST)
        fun recreate() = PageActionRequests(AppDestination.COMMUNITY, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertTrue(recreate().isPending(PageAction.COMPOSE_POST))
        requests.consume(PageAction.COMPOSE_POST); assertNull(recreate().pending.value)
    }

    @Test fun invalidOrWrongPageSavedActionsAreDiscarded() {
        val saved = SavedStateHandle(); val tracker = PageActionRequests(AppDestination.TRACKER, saved)
        tracker.request(PageAction.ADD_GAME)
        assertNull(PageActionRequests(AppDestination.CALENDAR, saved).pending.value)
        saved.keys().forEach { saved[it] = "unknown-action" }
        assertNull(PageActionRequests(AppDestination.TRACKER, saved).pending.value)
    }

    @Test fun rootsAndShortcutPagesMatchButDetailRoutesCannotReceiveTheseActions() {
        assertEquals(AppDestination.COMMUNITY, pageActionDestination("community"))
        assertEquals(AppDestination.COMMUNITY, pageActionDestination("page/community"))
        assertEquals(AppDestination.TRACKER, pageActionDestination("tracker"))
        assertNull(pageActionDestination("community/post/1")); assertNull(pageActionDestination("profile")); assertNull(pageActionDestination(null))
    }

    @Test fun switchingCollectionCategoryDropsOnlyAnUnrelatedPendingAction() {
        val requests = PageActionRequests(AppDestination.TRACKER, SavedStateHandle())
        requests.request(PageAction.ADD_GAME); requests.discardOtherCollections("game")
        assertTrue(requests.isPending(PageAction.ADD_GAME))
        requests.discardOtherCollections("audio"); assertNull(requests.pending.value)
        requests.request(PageAction.CUSTOMIZE_TRACKER); requests.discardOtherCollections("book")
        assertTrue(requests.isPending(PageAction.CUSTOMIZE_TRACKER))
    }

    @Test fun quickAddBelongsToTheLibraryAndIsNotReopenedAfterDismissal() {
        val saved = SavedStateHandle()
        val requests = PageActionRequests(AppDestination.TRACKER, saved)
        requests.request(PageAction.QUICK_ADD)
        requests.discardOtherCollections("book")
        assertTrue(requests.isPending(PageAction.QUICK_ADD))
        requests.discardOtherCollections("game")
        assertFalse(requests.isPending(PageAction.QUICK_ADD))
        requests.request(PageAction.QUICK_ADD)
        assertTrue(requests.consume(PageAction.QUICK_ADD))
        assertFalse(PageActionRequests(AppDestination.TRACKER, saved).isPending(PageAction.QUICK_ADD))
    }
}
