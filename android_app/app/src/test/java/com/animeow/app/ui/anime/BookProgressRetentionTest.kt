package com.animeow.app.ui.anime

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.mergeAnimeMetadata
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class BookProgressRetentionTest {
    @Test fun ordinaryEditsPreserveIndependentBookCountsIncludingExplicitZero() {
        val original = AnimeEntity(id = 1, title = "旧标题", subjectType = "book", bookReadVolumes = 0, bookReadChapters = 17)
        val saved = AnimeEditorState(title = "新标题", subjectType = "book").toAnime(1, original)
        assertEquals("新标题", saved.title)
        assertEquals(0, saved.bookReadVolumes); assertEquals(17, saved.bookReadChapters)
    }

    @Test fun duplicateMergeKeepsKnownLocalCountsAndOnlyFillsUnknownCounts() {
        val target = AnimeEntity(id = 1, title = "本机", subjectType = "book", bookReadVolumes = 0)
        val source = AnimeEntity(id = 2, title = "合成来源", subjectType = "book", bookReadVolumes = 8, bookReadChapters = 17)
        val merged = mergeAnimeMetadata(target, listOf(source))
        assertEquals(0, merged.bookReadVolumes); assertEquals(17, merged.bookReadChapters)
    }
}
