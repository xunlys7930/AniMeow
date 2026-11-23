package com.animeow.app.ui.tracker

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AnimeTagEntity
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.local.WatchRecordEntity
import com.animeow.app.data.preferences.TrackerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.RemoteCatalogSource
import org.junit.Test

class TrackerFilteringTest {
    @Test fun todayScheduleExcludesStoppedWorksEvenWhenTheirFirstAirDateIsToday() {
        val today = java.time.LocalDate.of(2026, 10, 25)
        val active = AnimeEntity(id = 1, title = "在看", status = "在看", airDate = today.toString())
        val dropped = active.copy(id = 2, status = "弃坑")
        val shelved = active.copy(id = 3, status = "搁置")
        val result = buildTrackerHomeSections(listOf(active, dropped, shelved), emptyList(), today = today)
        assertEquals(listOf(1L), result.todaySchedule.map { it.id })
    }
    @Test fun statusMovementOrderAlsoAppliesWhenSeriesAreGrouped() {
        val series = SeriesEntity(id = 100, name = "系列")
        val moved = AnimeEntity(id = 1, title = "旧作品", status = "在看", seriesId = 100, statusChangedAt = 200)
        val newer = AnimeEntity(id = 2, title = "新作品", status = "在看", statusChangedAt = 100)
        val rules = LibraryDisplayRules(
            BaseLibraryFilters("", "在看", LibrarySort.RECENT, false),
            AdvancedLibraryFilters(), TrackerSettings(groupSeriesOnHome = true),
        )
        val items = buildLibraryDisplayItems(listOf(newer, moved), listOf(newer, moved), listOf(series), rules)
        assertEquals(listOf("series:100", "anime:2"), items.map { it.stableKey })
    }

    @Test
    fun searchingMemberTitleOpensTheWorkWithoutEnteringItsSeries() {
        val member = AnimeEntity(id = 1, title = "旅人第二季", status = "在看", seriesId = 100)
        val series = SeriesEntity(id = 100, name = "旅人系列")
        val items = buildLibraryDisplayItems(listOf(member), listOf(member), listOf(series), LibraryDisplayRules(
            BaseLibraryFilters(query = "第二季", status = "全部", sort = LibrarySort.RECENT, sortAscending = false),
            AdvancedLibraryFilters(), TrackerSettings(groupSeriesOnHome = true)))
        assertEquals(listOf(LibraryDisplayItem.AnimeItem(member)), items)
    }

    @Test
    fun seriesNameSearchStillFindsTheFolder() {
        val member = AnimeEntity(id = 1, title = "旅人第二季", status = "在看", seriesId = 100)
        val series = SeriesEntity(id = 100, name = "旅人系列")
        val items = buildLibraryDisplayItems(emptyList(), listOf(member), listOf(series), LibraryDisplayRules(
            BaseLibraryFilters(query = "旅人系列", status = "全部", sort = LibrarySort.RECENT, sortAscending = false),
            AdvancedLibraryFilters(), TrackerSettings(groupSeriesOnHome = true)))
        assertEquals(listOf(LibraryDisplayItem.SeriesItem(series, listOf(member))), items)
    }

    @Test
    fun automaticMetadataMatchRejectsOtherSeasonsRemakesAndSubjectTypes() {
        val anime = AnimeEntity(id = 1, title = "星海旅人", status = "在看", airDate = "2024-01-01", externalSource = "bangumi", externalId = "23")
        val exact = RemoteAnime(RemoteCatalogSource.BANGUMI, "23", "星海旅人")
        assertEquals(exact, selectBestRemoteMetadataMatch(anime, listOf(exact.copy(id = "24"), exact)))
        assertNull(selectBestRemoteMetadataMatch(anime, listOf(exact.copy(id = "24"))))
        val local = anime.copy(externalSource = null, externalId = null)
        assertNull(selectBestRemoteMetadataMatch(local, listOf(exact.copy(title = "星海旅人 第二季"))))
        assertNull(selectBestRemoteMetadataMatch(local, listOf(exact.copy(subjectType = "book"))))
        assertNull(selectBestRemoteMetadataMatch(local, listOf(exact.copy(airDate = "2026-01-01"))))
        assertNull(selectBestRemoteMetadataMatch(local, listOf(exact, exact.copy(id = "24"))))
        assertEquals(exact, selectBestRemoteMetadataMatch(local.copy(title = "星海·旅人"), listOf(exact)))
    }

    private val source = listOf(
        AnimeEntity(
            id = 1,
            title = "葬送的芙莉莲",
            status = "在看",
            studio = "Madhouse",
            watchedEpisodes = 20,
            rating = 95,
            createdAt = "2026-01-01",
        ),
        AnimeEntity(
            id = 2,
            title = "孤独摇滚！",
            status = "看完",
            watchedEpisodes = 12,
            rating = 90,
            createdAt = "2026-02-01",
        ),
    )

    @Test
    fun searchesAcrossTitleAndStudio() {
        assertEquals(
            listOf(1L),
            filterAndSortAnimes(source, "madhouse", "全部", LibrarySort.RECENT).map { it.id },
        )
    }

    @Test
    fun filtersStatusAndSortsRating() {
        assertEquals(
            listOf(1L),
            filterAndSortAnimes(source, "", "在看", LibrarySort.RATING).map { it.id },
        )
    }

    @Test
    fun sortsTitle() {
        assertEquals(
            listOf(2L, 1L),
            filterAndSortAnimes(source, "", "全部", LibrarySort.TITLE).map { it.id },
        )
    }

    @Test
    fun combinesTypeYearAndAllTagFilters() {
        val detailed = source.mapIndexed { index, anime ->
            anime.copy(
                subjectType = if (index == 0) "anime" else "book",
                airDate = if (index == 0) "2023-09-29" else "2024-01-01",
            )
        }
        val links = listOf(
            AnimeTagEntity(animeId = 1, tagId = 10),
            AnimeTagEntity(animeId = 1, tagId = 11),
            AnimeTagEntity(animeId = 2, tagId = 10),
        )

        assertEquals(
            listOf(1L),
            filterAndSortAnimes(
                source = detailed,
                query = "",
                status = "全部",
                sort = LibrarySort.RECENT,
                subjectType = "anime",
                selectedTagIds = setOf(10, 11),
                matchAllTags = true,
                animeTagLinks = links,
                years = setOf("2023"),
            ).map { it.id },
        )
    }

    @Test
    fun tagFiltersHandleDuplicateLinksMissingTagsAndUntaggedWorks() {
        val animes = source + AnimeEntity(id = 3, title = "无标签", status = "在看")
        val links = listOf(
            AnimeTagEntity(animeId = 1, tagId = 10),
            AnimeTagEntity(animeId = 1, tagId = 10),
            AnimeTagEntity(animeId = 1, tagId = 11),
            AnimeTagEntity(animeId = 2, tagId = 11),
            AnimeTagEntity(animeId = 2, tagId = 99),
        )
        fun filtered(tags: Set<Long>, all: Boolean = false) = filterAndSortAnimes(
            animes, "", "全部", LibrarySort.RECENT,
            selectedTagIds = tags, matchAllTags = all, animeTagLinks = links,
        ).map { it.id }

        assertEquals(listOf(2L, 1L), filtered(setOf(10, 11)))
        assertEquals(listOf(1L), filtered(setOf(10, 11), all = true))
        assertEquals(emptyList<Long>(), filtered(setOf(10, 12), all = true))
        assertEquals(listOf(1L), filtered(setOf(10, 12)))
        assertEquals(listOf(3L, 2L, 1L), filtered(emptySet(), all = true))
    }

    @Test
    fun supportsMultipleYears() {
        val detailed = source.mapIndexed { index, anime ->
            anime.copy(airDate = if (index == 0) "2023-09-29" else "2024-01-01")
        }

        assertEquals(
            listOf(2L, 1L),
            filterAndSortAnimes(
                source = detailed,
                query = "",
                status = "全部",
                sort = LibrarySort.RECENT,
                years = setOf("2023", "2024"),
            ).map { it.id },
        )
    }

    @Test
    fun sortsAirDateInBothDirectionsAndKeepsMissingDatesLast() {
        val dated = listOf(
            source[0].copy(airDate = "2023-09-29"),
            source[1].copy(airDate = null),
            source[1].copy(id = 3, airDate = "2024-01-01"),
        )

        assertEquals(
            listOf(1L, 3L, 2L),
            filterAndSortAnimes(dated, "", "全部", LibrarySort.AIR_DATE, ascending = true).map { it.id },
        )
        assertEquals(
            listOf(3L, 1L, 2L),
            filterAndSortAnimes(dated, "", "全部", LibrarySort.AIR_DATE, ascending = false).map { it.id },
        )
    }

    @Test
    fun homeSectionsKeepReadingOrderWithoutDuplicatingInProgressAnime() {
        val animes = listOf(
            AnimeEntity(id = 1, title = "较早阅读", status = "在看", subjectType = "book", createdAt = "2026-08-11"),
            AnimeEntity(id = 2, title = "最近阅读", status = "在看", subjectType = "manga", createdAt = "2026-08-10"),
            AnimeEntity(id = 3, title = "漫画", status = "看完", subjectType = "manga", createdAt = "2026-08-12"),
            AnimeEntity(id = 4, title = "手动记过进度的动画", status = "在看", createdAt = "2026-08-09"),
        )
        val records = listOf(
            WatchRecordEntity(id = 9, animeId = 2, episode = 3, recordDate = "2026-08-12"),
            WatchRecordEntity(id = 8, animeId = 1, episode = 2, recordDate = "2026-08-11"),
            WatchRecordEntity(id = 10, animeId = 4, episode = 4, recordDate = "2026-08-13"),
        )

        val sections = buildTrackerHomeSections(animes, records)

        assertEquals(listOf(2L, 1L), sections.reading.map { it.id })
        assertEquals(listOf(3L, 1L, 2L, 4L), sections.recentAdded.map { it.id })
        assertEquals(listOf(3L, 1L, 2L), sections.books.map { it.id })
    }

    @Test
    fun groupSeriesInFilteredViewOnlyIncludesMatchedMembers() {
        val series1 = SeriesEntity(id = 100, name = "进击的巨人", createdAt = "2026-01-01")
        val anime1 = AnimeEntity(id = 1, title = "巨人第一季", status = "看完", seriesId = 100, createdAt = "2026-01-01")
        val anime2 = AnimeEntity(id = 2, title = "巨人第二季", status = "在看", seriesId = 100, createdAt = "2026-01-02")
        val allAnimes = listOf(anime1, anime2)
        val allSeries = listOf(series1)

        val rulesFiltered = LibraryDisplayRules(
            filters = BaseLibraryFilters(query = "", status = "在看", sort = LibrarySort.RECENT, sortAscending = false),
            advanced = AdvancedLibraryFilters(),
            settings = TrackerSettings(groupSeriesOnHome = true),
        )

        val filteredItems = buildLibraryDisplayItems(
            filtered = listOf(anime2),
            allAnimes = allAnimes,
            allSeries = allSeries,
            rules = rulesFiltered,
        )

        assertEquals(1, filteredItems.size)
        val seriesItem = filteredItems.first() as LibraryDisplayItem.SeriesItem
        assertEquals(listOf(2L), seriesItem.members.map { it.id })
    }
}
