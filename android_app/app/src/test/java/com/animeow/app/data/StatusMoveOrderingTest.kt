package com.animeow.app.data

import androidx.room.Room
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.WatchStatusEntity
import com.animeow.app.ui.tracker.LibrarySort
import com.animeow.app.ui.tracker.filterAndSortAnimes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class StatusMoveOrderingTest {
    @Test fun movedWorkLeadsItsColumnWithoutRewritingCreationDateOrOverridingExplicitSort() {
        val old = AnimeEntity(id = 1, title = "Old", status = "在看", createdAt = "2020-01-01T00:00:00Z", rating = 1)
        val recent = old.copy(id = 2, title = "Recent", createdAt = "2026-01-01T00:00:00Z", rating = 90)
        val moved = old.copy(status = "未看").withWatchStatusDates("在看")
        assertEquals(old.createdAt, moved.createdAt)
        val source = listOf(moved, recent)
        fun order(status: String, sort: LibrarySort) = filterAndSortAnimes(source, "", status, sort).map { it.id }
        assertEquals(listOf(1L, 2L), order("在看", LibrarySort.RECENT))
        assertEquals(listOf(2L, 1L), order("全部", LibrarySort.RECENT))
        assertEquals(listOf(2L, 1L), order("在看", LibrarySort.RATING))
        val pinned = recent.copy(pinnedAt = 10)
        assertEquals(listOf(2L, 1L), filterAndSortAnimes(listOf(moved, pinned), "", "在看", LibrarySort.RECENT).map { it.id })
        assertSame(moved, moved.withWatchStatusDates("在看"))
    }

    @Test fun batchEditCycleAndCompletionPersistMovementAndUndoRestoresIt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao()
            val repo = LibraryRepository(db)
            dao.insertWatchStatuses(listOf(
                WatchStatusEntity(id = 1, name = "在看", color = 1, sortOrder = 0),
                WatchStatusEntity(id = 2, name = "看完", color = 2, sortOrder = 1),
            ))
            val id = dao.insertAnime(AnimeEntity(title = "作品", status = "未看", createdAt = "2020-01-01T00:00:00Z", totalEpisodes = 1))
            repo.updateStatuses(setOf(id), "在看")
            val moved = requireNotNull(dao.getAnime(id))
            assertNotNull(moved.statusChangedAt)
            repo.saveAnime(moved.copy(title = "只改标题", statusChangedAt = null), emptySet())
            assertEquals(moved.statusChangedAt, dao.getAnime(id)?.statusChangedAt)
            val cycle = requireNotNull(repo.cycleStatus(id))
            assertEquals("看完", dao.getAnime(id)?.status)
            repo.restoreStatus(cycle)
            assertEquals(moved.statusChangedAt, dao.getAnime(id)?.statusChangedAt)
            assertEquals("在看", dao.getAnime(id)?.status)
            dao.updateAnime(moved.copy(watchedEpisodes = 0))
            val progress = requireNotNull(repo.incrementProgress(id, autoCompleteStatus = true))
            assertNotNull(dao.getAnime(id)?.statusChangedAt)
            repo.restoreProgress(progress)
            assertEquals(moved.statusChangedAt, dao.getAnime(id)?.statusChangedAt)
            assertEquals("在看", dao.getAnime(id)?.status)
            repo.saveAnime(moved.copy(status = "弃坑"), emptySet())
            assertNotNull(dao.getAnime(id)?.statusChangedAt)
            assertEquals("2020-01-01T00:00:00Z", dao.getAnime(id)?.createdAt)
        } finally { db.close() }
    }

    @Test fun updatedSortOrdersByLastModifiedWithFallbackAndKeepsPinsFirst() {
        val olderEditedLater = AnimeEntity(
            id = 1, title = "老番但刚改过", status = "在看",
            createdAt = "2020-01-01T00:00:00Z", updatedAt = 1_800_000_000_000L,
        )
        val newerUntouched = AnimeEntity(
            id = 2, title = "新添加未改动", status = "在看",
            createdAt = "2026-01-01T00:00:00Z", statusChangedAt = 1_767_225_600_000L,
        )
        val oldestLegacy = AnimeEntity(
            id = 3, title = "历史旧数据", status = "在看",
            createdAt = "2024-01-01T00:00:00Z",
        )
        val source = listOf(oldestLegacy, newerUntouched, olderEditedLater)
        assertEquals(
            listOf(1L, 2L, 3L),
            filterAndSortAnimes(source, "", "全部", LibrarySort.UPDATED, ascending = false).map { it.id },
        )
        assertEquals(
            listOf(3L, 2L, 1L),
            filterAndSortAnimes(source, "", "全部", LibrarySort.UPDATED, ascending = true).map { it.id },
        )
        val pinnedOld = oldestLegacy.copy(pinnedAt = 100L)
        assertEquals(
            listOf(3L, 1L, 2L),
            filterAndSortAnimes(listOf(pinnedOld, newerUntouched, olderEditedLater), "", "全部", LibrarySort.UPDATED).map { it.id },
        )
    }
}
