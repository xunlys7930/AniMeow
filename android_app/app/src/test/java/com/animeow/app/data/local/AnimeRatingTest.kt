package com.animeow.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimeRatingTest {
    @Test
    fun mapsGradeRatingsToLegacyNumericOrder() {
        assertEquals(100, animeRatingSortValue(AnimeEntity(title = "A", ratingGrade = "a")))
        assertEquals(80, animeRatingSortValue(AnimeEntity(title = "B", ratingGrade = "B")))
        assertEquals(60, animeRatingSortValue(AnimeEntity(title = "C", ratingGrade = " C ")))
        assertEquals(40, animeRatingSortValue(AnimeEntity(title = "D", ratingGrade = "D")))
    }

    @Test
    fun prefersValidGradeAndFallsBackToNumericRating() {
        assertEquals(100, animeRatingSortValue(AnimeEntity(title = "grade", rating = 55, ratingGrade = "A")))
        assertEquals(85, animeRatingSortValue(AnimeEntity(title = "score", rating = 85)))
        assertNull(animeRatingSortValue(AnimeEntity(title = "empty")))
    }

    @Test
    fun formatsCompactAndDetailedLabels() {
        val grade = AnimeEntity(title = "grade", ratingGrade = "B")
        val score = AnimeEntity(title = "score", rating = 85)

        assertEquals("B", animeRatingCompactLabel(grade))
        assertEquals("B 级", animeRatingSummary(grade))
        assertEquals("8.5", animeRatingCompactLabel(score))
        assertEquals("8.5 / 10", animeRatingSummary(score))
    }
}
