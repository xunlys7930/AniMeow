package com.animeow.app.ui.character

import com.animeow.app.data.findDuplicateCharacterGroups
import com.animeow.app.data.mergeCharacterMetadata
import com.animeow.app.data.local.CharacterEntity
import com.animeow.app.data.local.CharacterListItem
import com.animeow.app.data.local.CharacterTagEntity
import com.animeow.app.data.local.CharacterTagLinkEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CharacterFilteringTest {
    @Test
    fun filtersByLocalizedNameAndTag() {
        val frieren = item(1, "Frieren", "芙莉莲")
        val fern = item(2, "Fern", "菲伦")
        val tags = listOf(CharacterTagEntity(id = 4, name = "精灵"))
        val links = listOf(CharacterTagLinkEntity(characterId = 1, tagId = 4))

        assertEquals(listOf(frieren), filterCharacters(listOf(frieren, fern), tags, links, "莉莲"))
        assertEquals(listOf(frieren), filterCharacters(listOf(frieren, fern), tags, links, "精灵"))
    }

    @Test
    fun duplicateScanNormalizesWhitespaceAndCase() {
        val first = item(1, "Frieren", null)
        val second = item(2, "  FRI EREN ", null)

        assertEquals(listOf(listOf(first, second)), findDuplicateCharacterGroups(listOf(first, second)))
    }

    @Test
    fun mergePreservesPersonalRatingAndUsesRicherMetadata() {
        val target = CharacterEntity(id = 1, name = "Frieren", rating = 9, review = "喜欢")
        val source = CharacterEntity(
            id = 2,
            bgmId = 123,
            name = "Frieren",
            nameCn = "芙莉莲",
            summary = "一位活了很久的精灵魔法使",
            review = "非常喜欢这个角色",
        )

        val merged = mergeCharacterMetadata(target, listOf(source))

        assertEquals(123L, merged.bgmId)
        assertEquals("芙莉莲", merged.nameCn)
        assertEquals(9, merged.rating)
        assertEquals("非常喜欢这个角色", merged.review)
    }

    private fun item(id: Long, name: String, nameCn: String?) = CharacterListItem(
        character = CharacterEntity(id = id, name = name, nameCn = nameCn),
        workCount = 0,
        relationCount = 0,
        tagCount = 0,
    )
}
