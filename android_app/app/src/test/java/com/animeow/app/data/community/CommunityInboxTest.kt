package com.animeow.app.data.community

import org.junit.Assert.*
import org.junit.Test

class CommunityInboxTest {
    private val actor = CommunityProfile(2, "friend", "喵友", "", "members", false, false, false, null, false, emptyMap(), null)
    private fun notification(id: Long, read: Boolean = false) = CommunityNotification(id, "post_like", 10, null, actor, "片单", "", "2026-09-12T00:00:00Z", read)

    @Test fun overlappingPagesPreserveReadStateAndRefreshVisibleDetails() {
        val result = mergeCommunityInbox(listOf(notification(4, true), notification(3)),
            listOf(notification(4).copy(postExcerpt = "更新的片单"), notification(3, true), notification(2)))
        assertEquals(listOf(4L, 3L, 2L), result.map { it.id })
        assertTrue(result[0].isRead)
        assertTrue(result[1].isRead)
        assertFalse(result[2].isRead)
        assertEquals("更新的片单", result[0].postExcerpt)
    }
}
