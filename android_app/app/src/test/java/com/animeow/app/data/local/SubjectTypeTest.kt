package com.animeow.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectTypeTest {
    @Test
    fun normalizesTopLevelTypesToAnimeOrBook() {
        assertEquals(SUBJECT_TYPE_ANIME, normalizeSubjectType("anime"))
        assertEquals(SUBJECT_TYPE_BOOK, normalizeSubjectType("book"))
        assertEquals(SUBJECT_TYPE_BOOK, normalizeSubjectType("manga"))
        assertEquals(SUBJECT_TYPE_BOOK, normalizeSubjectType(" NOVEL "))
        assertEquals(SUBJECT_TYPE_ANIME, normalizeSubjectType(null))
        assertEquals(SUBJECT_TYPE_ANIME, normalizeSubjectType("unknown"))
    }

    @Test
    fun normalizesLegacyFilterValuesWithoutResettingToAll() {
        assertEquals(SUBJECT_TYPE_ALL, normalizeSubjectTypeFilter("all"))
        assertEquals(SUBJECT_TYPE_BOOK, normalizeSubjectTypeFilter("manga"))
        assertEquals(SUBJECT_TYPE_BOOK, normalizeSubjectTypeFilter("novel"))
        assertEquals(SUBJECT_TYPE_ALL, normalizeSubjectTypeFilter("unknown"))
    }

    @Test
    fun discardsLegacySubtypeAndKeepsOnlyBook() {
        val manga = AnimeEntity(title = "漫画", subjectType = "manga").withNormalizedSubjectType()
        val novel = AnimeEntity(title = "小说", subjectType = "novel").withNormalizedSubjectType()
        val custom = AnimeEntity(
            title = "自定义",
            subjectType = "manga",
            mediaFormat = "单行本",
        ).withNormalizedSubjectType()

        assertEquals(SUBJECT_TYPE_BOOK, manga.subjectType)
        assertEquals(null, manga.mediaFormat)
        assertEquals(SUBJECT_TYPE_BOOK, novel.subjectType)
        assertEquals(null, novel.mediaFormat)
        assertEquals("单行本", custom.mediaFormat)
        assertTrue(manga.subjectType.isBookSubjectType())
        assertFalse(SUBJECT_TYPE_ANIME.isBookSubjectType())
    }
}
