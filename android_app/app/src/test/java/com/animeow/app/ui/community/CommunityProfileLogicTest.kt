package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.community.CommunityBootstrap
import com.animeow.app.data.community.CommunityGroup
import com.animeow.app.data.community.CommunityLimits
import com.animeow.app.data.community.CommunityPost
import com.animeow.app.data.community.CommunityProfile
import com.animeow.app.data.community.CommunityRestriction
import com.animeow.app.data.community.CommunityStatisticsAutoSyncTracker
import com.animeow.app.data.community.normalizeCommunityStatisticPayload
import com.animeow.app.data.community.resolvedCommunityStatisticKeys
import com.animeow.app.data.community.shouldAutoSyncCommunityStatistics
import com.animeow.app.ui.statistics.StatisticsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun sharedDurationUsesEpisodeEstimateAndKeepsMinutePrecision() {
        val state = StatisticsUiState(watchedEpisodes = 3, watchHours = 1, watchMinutes = 74)
        assertEquals(mapOf("estimated_minutes" to 74.0), statisticsPayload(
            state, setOf(CommunityStatisticMetric.ESTIMATED_MINUTES.key),
        ))
        assertTrue(statisticsPayload(state, emptySet()).isEmpty())
        assertEquals("1 小时 14 分钟", communityStatisticValue("estimated_minutes", 74.0))
    }

    @Test
    fun resolvedKeysAndNormalizationHandleCustomAndDefaultMetrics() {
        val customProfile = sampleProfile(
            statistics = mapOf(
                CommunityStatisticMetric.WATCHED_EPISODES.key to 10.0,
                "unknown_key" to 1.0,
            ),
        )
        assertEquals(setOf(CommunityStatisticMetric.WATCHED_EPISODES.key), resolvedCommunityStatisticKeys(customProfile))
        assertEquals(DefaultCommunityStatisticKeys, resolvedCommunityStatisticKeys(sampleProfile(statistics = emptyMap())))

        val normalized = normalizeCommunityStatisticPayload(
            StatisticsUiState(
                totalAnime = 15,
                animeCount = 12,
                bookCount = 3,
                watchedEpisodes = 144,
                watchMinutes = 3456,
                averageRating = 8.46,
            ),
            setOf(
                CommunityStatisticMetric.TOTAL_ITEMS.key,
                CommunityStatisticMetric.AVERAGE_RATING.key,
            ),
        )
        assertEquals(15.0, normalized[CommunityStatisticMetric.TOTAL_ITEMS.key]!!, 0.0001)
        assertEquals(8.5, normalized[CommunityStatisticMetric.AVERAGE_RATING.key]!!, 0.0001)
    }

    @Test
    fun autoSyncGuardsAndTrackerPreventRedundantOrDestructiveUploads() {
        val sessionA = CloudSession(userId = 1L, username = "alice", token = "token-a")
        val sessionB = CloudSession(userId = 2L, username = "bob", token = "token-b")
        val matchingPayload = mapOf("total_items" to 12.0, "average_rating" to 8.6)
        val populatedProfile = sampleProfile(showStatisticsCard = true, statistics = matchingPayload)

        assertFalse(shouldAutoSyncCommunityStatistics(populatedProfile.copy(showStatisticsCard = false), matchingPayload))
        assertFalse(
            shouldAutoSyncCommunityStatistics(
                populatedProfile.copy(restrictions = listOf(CommunityRestriction("profile", "muted", null))),
                mapOf("total_items" to 20.0, "average_rating" to 8.6),
            ),
        )
        assertFalse(shouldAutoSyncCommunityStatistics(populatedProfile, matchingPayload))
        assertFalse(
            shouldAutoSyncCommunityStatistics(
                populatedProfile,
                mapOf("total_items" to 0.0, "average_rating" to 0.0),
            ),
        )

        val updatedPayload = mapOf("total_items" to 13.0, "average_rating" to 8.7)
        assertTrue(shouldAutoSyncCommunityStatistics(populatedProfile, updatedPayload))

        val tracker = CommunityStatisticsAutoSyncTracker()
        assertTrue(tracker.needsSync(sessionA, populatedProfile, updatedPayload))
        tracker.markSynced(sessionA, updatedPayload)
        assertFalse(tracker.needsSync(sessionA, populatedProfile, updatedPayload))
        assertTrue(tracker.needsSync(sessionB, populatedProfile, updatedPayload))
        tracker.reset()
        assertTrue(tracker.needsSync(sessionA, populatedProfile, updatedPayload))
    }

    @Test
    fun applyProfileUpdateMarksSavedAndUpdatesPostsWithoutSelectedProfile() {
        val oldAuthor = sampleProfile(nickname = "旧昵称", statistics = mapOf("total_items" to 5.0))
        val updatedAuthor = oldAuthor.copy(nickname = "新昵称", statistics = mapOf("total_items" to 8.0))
        val post = CommunityPost(
            id = 100L,
            groupId = 1L,
            content = "hello",
            media = emptyList(),
            author = oldAuthor,
            moderationStatus = "visible",
            isQuarantined = false,
            isPinned = false,
            pinnedAt = null,
            isFeatured = false,
            editedAt = null,
            likeCount = 0,
            commentCount = 0,
            likedByViewer = false,
            isFavorited = false,
            canReport = false,
            canDelete = true,
            createdAt = "2026-11-13T10:00:00",
            updatedAt = "2026-11-13T10:00:00",
        )
        val state = CommunityUiState(
            session = CloudSession(1L, "alice", "tok"),
            bootstrap = CommunityBootstrap(
                currentUser = oldAuthor,
                group = CommunityGroup(1L, "茶话会", "", "join", true, true, null),
                limits = CommunityLimits(),
            ),
            posts = listOf(post),
            profilePosts = listOf(post),
            selectedProfile = null,
            profileSaved = false,
            profileSaveError = "之前的错误",
        )
        val next = applyProfileUpdateToCommunityState(state, updatedAuthor, markSaved = true)
        assertTrue(next.profileSaved)
        assertNull(next.profileSaveError)
        assertEquals("新昵称", next.bootstrap?.currentUser?.nickname)
        assertEquals(8.0, next.posts.single().author.statistics["total_items"]!!, 0.0001)
        assertEquals(8.0, next.profilePosts.single().author.statistics["total_items"]!!, 0.0001)
    }

    private fun sampleProfile(
        nickname: String = "Alice",
        showStatisticsCard: Boolean = true,
        statistics: Map<String, Double> = emptyMap(),
        restrictions: List<CommunityRestriction> = emptyList(),
    ) = CommunityProfile(
        userId = 1L,
        username = "alice",
        nickname = nickname,
        signature = "",
        profileVisibility = "members",
        isPrivate = false,
        isAdmin = false,
        isDeveloper = false,
        avatarUrl = null,
        showStatisticsCard = showStatisticsCard,
        statistics = statistics,
        updatedAt = null,
        restrictions = restrictions,
    )
}
