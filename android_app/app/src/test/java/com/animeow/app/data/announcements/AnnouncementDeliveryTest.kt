package com.animeow.app.data.announcements

import org.junit.Assert.*
import org.junit.Test

class AnnouncementDeliveryTest {
    @Test fun seenOldAndWithdrawnAnnouncementsDoNotNotifyAgain() {
        val old = AppAnnouncement(4, "announcement", "旧公告", "正文", "2026-09-01T00:00:00Z")
        val unread = old.copy(id = 6, title = "新公告")
        val seen = old.copy(id = 7)
        val withdrawn = old.copy(id = 8, withdrawn = true)
        assertEquals(listOf(unread), announcementsToNotify(listOf(old, unread, seen, withdrawn), setOf(7), 5))
        assertTrue(announcementsToNotify(listOf(unread), emptySet(), 6).isEmpty())
    }
}
