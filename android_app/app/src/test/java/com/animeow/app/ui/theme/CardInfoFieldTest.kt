package com.animeow.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class CardInfoFieldTest {
    @Test
    fun missingValueFallsBackToDeclarationOrder() {
        assertEquals(CardInfoField.entries, CardInfoField.parseOrder(null))
        assertEquals(CardInfoField.entries, CardInfoField.parseOrder(""))
    }

    @Test
    fun unknownKeysAreDroppedAndMissingAppended() {
        assertEquals(
            listOf(
                CardInfoField.RATING,
                CardInfoField.TITLE,
                CardInfoField.METADATA,
                CardInfoField.STATUS,
                CardInfoField.PROGRESS,
            ),
            CardInfoField.parseOrder("rating,title,unknown"),
        )
    }

    @Test
    fun duplicateKeysAppearOnce() {
        assertEquals(
            listOf(
                CardInfoField.TITLE,
                CardInfoField.PROGRESS,
                CardInfoField.METADATA,
                CardInfoField.STATUS,
                CardInfoField.RATING,
            ),
            CardInfoField.parseOrder("title,progress,title"),
        )
    }
}
