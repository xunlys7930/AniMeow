package com.animeow.app.ui.duplicates

import com.animeow.app.data.local.AnimeEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateCleanupTest {
    @Test
    fun normalizationMatchesLegacyWhitespaceBehavior() {
        assertEquals("葬送のフリーレン", normalizeDuplicateTitle(" 葬送 の　フリーレン "))
    }

    @Test
    fun groupingKeepsSubjectTypesSeparate() {
        val groups = findDuplicateAnimeGroups(
            listOf(
                AnimeEntity(id = 1, title = "86 - Eighty Six", subjectType = "anime"),
                AnimeEntity(id = 2, title = "86 -  Eighty Six", subjectType = "anime"),
                AnimeEntity(id = 3, title = "86 - Eighty Six", subjectType = "book"),
            ),
        )

        assertEquals(1, groups.size)
        assertEquals(listOf(2L, 1L), groups.single().items.map { it.id })
    }

    @Test
    fun groupingTreatsLegacyReadingValuesAsBooks() {
        val groups = findDuplicateAnimeGroups(
            listOf(
                AnimeEntity(id = 1, title = "同一本书", subjectType = "book"),
                AnimeEntity(id = 2, title = "同一本书", subjectType = "manga"),
                AnimeEntity(id = 3, title = "同一本书", subjectType = "novel"),
            ),
        )

        assertEquals(1, groups.size)
        assertEquals("book", groups.single().subjectType)
        assertEquals(listOf(3L, 2L, 1L), groups.single().items.map { it.id })
    }
}
