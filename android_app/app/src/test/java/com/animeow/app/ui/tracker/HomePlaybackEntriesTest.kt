package com.animeow.app.ui.tracker

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.playback.PlaybackHistoryEntry
import org.junit.Assert.*
import org.junit.Test

class HomePlaybackEntriesTest {
    @Test fun clickGuardRejectsARecordReplacedAfterTheListWasDrawn() {
        val row = PlaybackHistoryEntry("original", 1, "旧标题", "", "01", 0, null)
        val original = AnimeEntity(id = 1, title = "当前标题")
        assertTrue(playbackEntryMatches(row, original) { "original" })
        assertFalse(playbackEntryMatches(row, original) { "different-identity" })
        assertFalse(playbackEntryMatches(row, original.copy(deletedAt = "2026-10-25")) { "original" })
        assertFalse(playbackEntryMatches(row, original.copy(subjectType = "book")) { "original" })
        assertFalse(playbackEntryMatches(row, null) { "original" })
    }
    @Test fun recentPlaybackUsesCurrentMetadataAndRejectsDeletedBooksAndReusedIdentities() {
        val active = AnimeEntity(id = 1, title = "修改后的标题", coverUrl = "new-cover")
        val deleted = active.copy(id = 2, deletedAt = "2026-10-25")
        val book = active.copy(id = 3, subjectType = "book")
        fun row(id: Long, key: String = "stable-$id") = PlaybackHistoryEntry(key, id, "旧标题", "旧图", "第一集", id, null)
        val shown = activePlaybackEntries(listOf(row(1), row(2), row(3), row(4), row(1, "other-work")), listOf(active, deleted, book)) { "stable-${it.id}" }
        assertEquals(1, shown.size)
        assertEquals(active.title, shown.single().title)
        assertEquals("new-cover", shown.single().cover)
        assertEquals("第一集", shown.single().episode)
    }
}
