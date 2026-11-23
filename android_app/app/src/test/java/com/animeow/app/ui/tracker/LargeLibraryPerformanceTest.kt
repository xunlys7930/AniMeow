package com.animeow.app.ui.tracker

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.preferences.TrackerSettings
import java.io.File
import kotlin.system.measureNanoTime
import org.junit.Assert.*
import org.junit.Test

class LargeLibraryPerformanceTest {
    @Test fun searchAndGroupedSortingStayAccurateAtOneAndFiveThousandWorks() {
        val results = mutableListOf<String>()
        for (size in listOf(1_000, 5_000)) {
            val source = (1..size).map { n -> AnimeEntity(id = n.toLong(), title = "合成作品 $n",
                status = "在看", watchedEpisodes = n % 12, totalEpisodes = 12,
                seriesId = ((n - 1) / 50 + 1).toLong(), rating = n % 100,
                pinnedAt = if (n == 1) 100L else null) }
            val series = (1..size / 50).map { SeriesEntity(id = it.toLong(), name = "合成系列 $it") }
            val rules = LibraryDisplayRules(BaseLibraryFilters("", "全部", LibrarySort.RATING, false),
                AdvancedLibraryFilters(), TrackerSettings(groupSeriesOnHome = true))
            var grouped = emptyList<LibraryDisplayItem>()
            val sortMs = (1..9).map {
                measureNanoTime { grouped = buildLibraryDisplayItems(source, source, series, rules) } / 1_000_000.0
            }.sorted()[4]
            assertEquals(size / 50, grouped.size)
            assertEquals(size, grouped.flatMap { it.memberAnimeIds }.toSet().size)
            assertEquals("series:1", grouped.first().stableKey)
            var found = emptyList<AnimeEntity>()
            val searchMs = (1..9).map {
                measureNanoTime {
                    found = filterAndSortAnimes(source, "合成作品 50", "全部", LibrarySort.RECENT)
                } / 1_000_000.0
            }.sorted()[4]
            assertTrue(found.isNotEmpty()); assertTrue(found.all { it.title.contains("合成作品 50") })
            results += "{\"works\":$size,\"groupedSortMedianMs\":$sortMs,\"searchMedianMs\":$searchMs}"
        }
        val path = System.getProperty("animeow.performanceReport") ?: "../../build/android-maintenance-20261103/performance.json"
        File(path).apply { parentFile?.mkdirs(); writeText(results.joinToString(",", "[", "]")) }
    }
}
