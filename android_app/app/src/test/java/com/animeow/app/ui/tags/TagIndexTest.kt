package com.animeow.app.ui.tags

import com.animeow.app.data.local.TagEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TagIndexTest {
    @Test
    fun groupsLatinDigitsAndChineseByReadableInitial() {
        assertEquals("A", tagInitial("Anime"))
        assertEquals("0-9", tagInitial("2026 新番"))
        assertEquals("R", tagInitial("热血"))
        assertEquals("Z", tagInitial("治愈"))
        assertEquals("#", tagInitial("✨ 收藏"))
    }

    @Test
    fun calculatesLazyListHeaderTargetsForAlphabetRail() {
        val items = listOf(
            TagIndexItem(TagEntity(id = 1, name = "Action"), 2),
            TagIndexItem(TagEntity(id = 2, name = "Anime"), 1),
            TagIndexItem(TagEntity(id = 3, name = "Battle"), 3),
        )

        assertEquals(mapOf("A" to 3, "B" to 6), tagGroupHeaderIndices(items))
    }
}
