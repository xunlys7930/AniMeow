package com.animeow.app.ui.navigation

import androidx.lifecycle.SavedStateHandle
import com.animeow.app.data.remote.CommunityLaunchRequest
import org.junit.Assert.*
import org.junit.Test

class CharacterShareRequestsTest {
    private val first = CommunityLaunchRequest(shareCode = "ABCDEFGH")
    private val second = CommunityLaunchRequest(communityId = "cg_0123456789abcdef")

    @Test fun approvedPreviewSurvivesRestorationAndConsumedPreviewDoesNotReplay() {
        val saved = SavedStateHandle()
        val requests = CharacterShareRequests(saved)
        assertTrue(requests.request(first))
        fun restore() = CharacterShareRequests(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(first, restore().decode(restore().pending.value))
        assertTrue(requests.consume(first))
        assertNull(restore().pending.value)
    }

    @Test fun oldConsumerCannotClearANewerApprovedShare() {
        val requests = CharacterShareRequests(SavedStateHandle())
        requests.request(first); requests.request(second)
        assertFalse(requests.consume(first))
        assertEquals(second, requests.decode(requests.pending.value))
        assertTrue(requests.consume(second)); assertFalse(requests.consume(second))
    }

    @Test fun navigationEntriesKeepTheirOwnPreviews() {
        val firstEntry = CharacterShareRequests(SavedStateHandle())
        val secondEntry = CharacterShareRequests(SavedStateHandle())
        firstEntry.request(first); secondEntry.request(second)
        firstEntry.consume(first)
        assertEquals(second, secondEntry.decode(secondEntry.pending.value))
    }

    @Test fun malformedRequestsCannotReplaceTheApprovedPreview() {
        val requests = CharacterShareRequests(SavedStateHandle())
        requests.request(first)
        listOf(CommunityLaunchRequest(), CommunityLaunchRequest(shareCode = "ABCDEFGH", communityId = second.communityId),
            CommunityLaunchRequest(shareCode = "ABCDEFGH/extra"), CommunityLaunchRequest(communityId = "cg_invalid"),
            CommunityLaunchRequest(shareCode = "ABCDEFGH?code=BCDEFGHJ"),
        ).forEach { assertFalse(requests.request(it)) }
        assertEquals(first, requests.decode(requests.pending.value))
    }

    @Test fun invalidSavedPreviewsAreDiscarded() {
        val saved = SavedStateHandle()
        CharacterShareRequests(saved).request(first)
        saved.keys().forEach { saved[it] = "share:ABCDEFGH/extra" }
        assertNull(CharacterShareRequests(saved).pending.value)
    }
}
