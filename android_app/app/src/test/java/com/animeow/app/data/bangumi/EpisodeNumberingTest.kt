package com.animeow.app.data.bangumi

import org.junit.Assert.*
import org.junit.Test

class EpisodeNumberingTest {
    private fun rows(first: Int = 129, total: Int = 52) =
        (0 until total).map { EpisodeNumberMetadata(it + 1L, 0, (first + it).toDouble()) }

    @Test fun continuousSeasonRoundTripsWithoutChangingCount() {
        val numbering = requireNotNull(EpisodeNumbering.fromEpisodes(rows().reversed(), 52))
        assertTrue(numbering.usesContinuousNumbering)
        assertEquals(129, numbering.firstNumber)
        assertEquals(180, numbering.lastNumber)
        assertEquals(175, numbering.numberForWatchedCount(47))
        assertEquals(47, numbering.watchedCountForNumber(175))
        assertEquals(48, numbering.watchedCountForNumber(176))
        assertEquals(180, numbering.numberForWatchedCount(52))
    }

    @Test fun ordinarySeasonKeepsCountNumbering() {
        val numbering = requireNotNull(EpisodeNumbering.fromEpisodes(rows(1, 12), 12))
        assertFalse(numbering.usesContinuousNumbering)
        assertEquals(5, numbering.numberForWatchedCount(5))
    }

    @Test fun unstartedAndOutOfRangeProgressHaveNoEpisodeLabel() {
        val numbering = requireNotNull(EpisodeNumbering.fromEpisodes(rows(), 52))
        listOf(-1, 0, 53, 175).forEach { assertNull(numbering.numberForWatchedCount(it)) }
        listOf(0, 47, 128, 181).forEach { assertNull(numbering.watchedCountForNumber(it)) }
    }

    @Test fun incompleteOrChangedLocalTotalFallsBack() {
        assertNull(EpisodeNumbering.fromEpisodes(rows().dropLast(1), 52))
        assertNull(EpisodeNumbering.fromEpisodes(rows(), 51))
        assertNull(EpisodeNumbering.fromEpisodes(emptyList(), 0))
    }

    @Test fun malformedMetadataNeverGuessesOffset() {
        val originals = rows()
        val badFirstRows = listOf(
            originals.first().copy(id = originals[1].id),
            originals.first().copy(id = 0),
            originals.first().copy(type = 1),
            originals.first().copy(sort = 128.0), // gap
            originals.first().copy(sort = 130.0), // duplicate label
            originals.first().copy(sort = 129.5),
            originals.first().copy(sort = 0.0),
            originals.first().copy(sort = Double.NaN),
            originals.first().copy(sort = Double.POSITIVE_INFINITY),
            originals.first().copy(sort = Int.MAX_VALUE.toDouble() + 1),
        )
        badFirstRows.forEach { assertNull(EpisodeNumbering.fromEpisodes(listOf(it) + originals.drop(1), 52)) }
    }
}
