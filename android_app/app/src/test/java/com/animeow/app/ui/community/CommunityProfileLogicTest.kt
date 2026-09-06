package com.animeow.app.ui.community

import com.animeow.app.ui.statistics.StatisticsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityProfileLogicTest {
    @Test
    fun defaultCardDoesNotExposeStreak() {
        assertFalse(CommunityStatisticMetric.STREAK_DAYS.key in DefaultCommunityStatisticKeys)
        assertTrue(CommunityStatisticMetric.TOTAL_ITEMS.key in DefaultCommunityStatisticKeys)
        assertTrue(CommunityStatisticMetric.AVERAGE_RATING.key in DefaultCommunityStatisticKeys)
    }

    @Test
    fun payloadContainsOnlySelectedSummaryFields() {
        val payload = statisticsPayload(
            StatisticsUiState(
                totalAnime = 12,
                animeCount = 9,
                bookCount = 3,
                watchedEpisodes = 100,
                watchHours = 40,
                averageRating = 8.6,
                currentStreakDays = 7,
            ),
            setOf(
                CommunityStatisticMetric.TOTAL_ITEMS.key,
                CommunityStatisticMetric.ESTIMATED_MINUTES.key,
            ),
        )
        assertEquals(mapOf("total_items" to 12.0, "estimated_minutes" to 2400.0), payload)
        assertFalse(CommunityStatisticMetric.STREAK_DAYS.key in payload)
    }

    @Test
    fun reportLabelsAndTimeFallbackRemainUserFriendly() {
        assertEquals("广告或引流", communityReportReasonLabel("advertising"))
        assertEquals("其他违规", communityReportReasonLabel("unknown"))
        assertEquals("2026-08-12 09:30", formatCommunityTime("2026-08-12T09:30:00"))
    }
}
