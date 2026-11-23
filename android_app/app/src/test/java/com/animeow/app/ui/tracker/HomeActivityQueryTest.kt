package com.animeow.app.ui.tracker

import android.app.Application
import androidx.room.Room
import com.animeow.app.data.local.*
import com.animeow.app.data.preferences.TrackerSettings
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class HomeActivityQueryTest {
    @Test fun summaryMatchesFullHistoryIncludingDateTiesNullDatesAndUtcBoundary() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao()
            val source = (1L..4).map { AnimeEntity(id = it, title = "作品$it", status = "在看") }
            source.forEach { dao.insertAnime(it) }
            dao.insertWatchRecords(listOf(
                WatchRecordEntity(1, 1, 1, recordDate = "2026-09-01"),
                WatchRecordEntity(2, 1, 2, recordDate = "2026-09-01"),
                WatchRecordEntity(3, 2, 1, recordDate = "2026-09-01"),
                WatchRecordEntity(4, 3, 1), WatchRecordEntity(5, 3, 2, recordDate = ""),
                WatchRecordEntity(6, 4, 1, recordDate = "2026-10-25T18:00:00Z"),
                WatchRecordEntity(7, 1, 3, recordDate = "2026-10-26"),
                WatchRecordEntity(8, 1, 4, recordDate = "2026-08-01"),
            ))
            val full = dao.getWatchRecords()
            val latest = dao.observeLatestWatchRecords().first()
            assertEquals(listOf(7L, 3L, 5L, 6L), latest.sortedBy { it.animeId }.map { it.id })
            val recent = dao.observeWatchRecordsSince("2026-10-25").first()
            assertEquals(setOf(6L, 7L), recent.map { it.id }.toSet())
            val reduced = recent + latest.map { WatchRecordEntity(it.id, it.animeId, 0, recordDate = it.recordDate) }
            val today = LocalDate.of(2026, 10, 26)
            assertEquals(buildTrackerHomeSections(source, full, today = today), buildTrackerHomeSections(source, reduced, today = today))
            dao.deleteWatchRecord(7)
            assertEquals(2L, dao.observeLatestWatchRecords().first().first { it.animeId == 1L }.id)
        } finally { db.close() }
    }

    @Test fun rapidSearchOnlyCalculatesSettledTextAndClearingIsImmediate() = runBlocking {
        fun rule(text: String) = LibraryDisplayRules(BaseLibraryFilters(text, "全部", LibrarySort.RECENT, false), AdvancedLibraryFilters(), TrackerSettings())
        val source = MutableStateFlow(rule(""))
        val received = mutableListOf<String>()
        val job = launch { source.settleSearch().collect { received += it.filters.query } }
        withTimeout(2000) { while (received.isEmpty()) yield() }
        source.value = rule("追"); delay(30)
        source.value = rule("追番"); delay(30)
        source.value = rule("追番喵")
        withTimeout(2000) { while (received.last() != "追番喵") delay(10) }
        assertEquals(listOf("", "追番喵"), received)
        source.value = rule("")
        withTimeout(100) { while (received.last() != "") yield() }
        job.cancelAndJoin()
    }

    @Test fun syntheticHistorySummaryKeepsResultsAndRecordsHostTimings() {
        val results = JSONArray()
        val today = LocalDate.of(2026, 10, 25)
        for (count in listOf(1000, 5000, 10000)) {
            val source = (1..count).map { AnimeEntity(id = it.toLong(), title = "作品$it", status = "在看") }
            val full = source.flatMap { anime -> (1..10).map { episode ->
                WatchRecordEntity(anime.id * 10 + episode, anime.id, episode, recordDate = "2026-09-${episode.toString().padStart(2, '0')}")
            } }.sortedWith(compareByDescending<WatchRecordEntity> { it.recordDate }.thenByDescending { it.id })
            val summary = source.map { anime -> WatchRecordEntity(anime.id * 10 + 10, anime.id, 0, recordDate = "2026-09-10") }
            assertEquals(buildTrackerHomeSections(source, full, today = today), buildTrackerHomeSections(source, summary, today = today))
            fun median(records: List<WatchRecordEntity>): Double {
                buildTrackerHomeSections(source, records, today = today)
                return (1..3).map {
                    val start = System.nanoTime(); buildTrackerHomeSections(source, records, today = today)
                    (System.nanoTime() - start) / 1_000_000.0
                }.sorted()[1]
            }
            results.put(JSONObject().put("works", count).put("fullRows", full.size).put("summaryRows", summary.size)
                .put("fullInputMedianMs", median(full)).put("summaryInputMedianMs", median(summary)))
        }
        File("../../build/2.2.2-polish/home-benchmark.json").apply { parentFile!!.mkdirs(); writeText(results.toString(2)) }
    }
}
