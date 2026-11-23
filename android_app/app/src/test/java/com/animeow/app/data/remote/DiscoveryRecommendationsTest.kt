package com.animeow.app.data.remote

import com.animeow.app.data.local.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DiscoveryRecommendationsTest {
    private fun item(id: String, score: Double, count: Int?, tags: List<String>) = RemoteAnime(
        RemoteCatalogSource.BANGUMI, id, id, score = score, ratingCount = count, tags = tags, airDate = "2025-01-01")

    @Test fun preferenceChangesRecommendationWithoutChangingPublicRatingAndRejectsSingleVoteFutureAndOwned() {
        val high = item("high", 9.2, 10000, listOf("动作"))
        val preferred = item("preferred", 8.0, 2000, listOf("Slice of Life"))
        val low = item("single-vote", 10.0, 1, listOf("日常"))
        val unknown = item("unknown", 10.0, null, listOf("日常"))
        val future = preferred.copy(id = "future", airDate = "2029-01-01")
        val owned = preferred.copy(id = "owned")
        val profile = DiscoveryPreferenceProfile(mapOf("日常" to 3.0), setOf("bangumi:owned"))
        val result = recommendedDiscoveryItems(listOf(high, preferred, low, unknown, future, owned), profile, LocalDate.of(2026, 10, 24))
        assertEquals(listOf(preferred, high), result)
        assertEquals(8.0, result.first().score!!, 0.0)
        assertEquals(listOf(high, preferred), recommendedDiscoveryItems(listOf(high, preferred), DiscoveryPreferenceProfile()))
    }

    @Test fun profileUsesMatchingMediaAndGoodPersonalRatingsAndKeepsSourceIdentity() {
        val works = listOf(AnimeEntity(id = 1, title = "喜欢", rating = 90, externalSource = "bangumi", externalId = "123"),
            AnimeEntity(id = 2, title = "不喜欢", rating = 20), AnimeEntity(id = 3, title = "书", subjectType = "book", rating = 100),
            AnimeEntity(id = 4, title = "低等级", ratingGrade = "D"), AnimeEntity(id = 5, title = "未评分", rating = 0))
        val tags = listOf(TagEntity(1, "日常"), TagEntity(2, "恐怖"), TagEntity(3, "剧情"), TagEntity(4, "屏蔽", blocked = true))
        val links = listOf(AnimeTagEntity(1, 1), AnimeTagEntity(2, 2), AnimeTagEntity(3, 3), AnimeTagEntity(1, 4), AnimeTagEntity(4, 2), AnimeTagEntity(5, 3))
        val result = discoveryPreferenceProfile(works, tags, links, "anime")
        assertEquals(mapOf("日常" to 2.0, "剧情" to 0.75), result.tags)
        assertEquals(setOf("bangumi:123"), result.owned)
    }

    @Test fun excludesOwnedWorksByNormalizedTitleAndDeduplicatesAcrossSources() {
        val works = listOf(
            AnimeEntity(
                id = 1150,
                title = "败犬女主太多了！",
                originalTitle = "負けヒロインが多すぎる！",
                status = "想看",
                subjectType = "anime",
            ),
        )
        val profile = discoveryPreferenceProfile(works, emptyList(), emptyList(), "anime")
        val sameTitleHalfWidth = RemoteAnime(
            RemoteCatalogSource.BANGUMI, "464376", "败犬女主太多了!",
            originalTitle = "負けヒロインが多すぎる!",
            score = 8.2, ratingCount = 5000, tags = listOf("校园"), airDate = "2024-07-13",
        )
        val sameOriginalTitle = RemoteAnime(
            RemoteCatalogSource.ANILIST, "171457", "Too Many Losing Heroines!",
            originalTitle = "負けヒロインが多すぎる！",
            score = 8.1, ratingCount = 4000, tags = listOf("校园"), airDate = "2024-07-13",
        )
        val dupA = RemoteAnime(
            RemoteCatalogSource.BANGUMI, "1001", "葬送的芙莉莲",
            originalTitle = "葬送のフリーレン",
            score = 9.0, ratingCount = 12000, tags = listOf("奇幻"), airDate = "2023-09-29",
        )
        val dupB = RemoteAnime(
            RemoteCatalogSource.ANILIST, "154587", "Frieren",
            originalTitle = "葬送のフリーレン",
            score = 8.9, ratingCount = 11000, tags = listOf("奇幻"), airDate = "2023-09-29",
        )
        val result = recommendedDiscoveryItems(
            listOf(sameTitleHalfWidth, sameOriginalTitle, dupA, dupB),
            profile,
            LocalDate.of(2026, 11, 19),
        )
        assertEquals(listOf(dupA), result)
    }
}
