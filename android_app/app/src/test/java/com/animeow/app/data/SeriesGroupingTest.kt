package com.animeow.app.data

import androidx.room.Room
import com.animeow.app.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class SeriesGroupingTest {
    @Test fun createsAndMovesWithoutChangingPersonalRecordsOrOtherMembers() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao(); val repo = LibraryRepository(db)
            val first = dao.insertAnime(AnimeEntity(title = "约会大作战", watchedEpisodes = 7, rating = 88, review = "保留感想"))
            val second = dao.insertAnime(AnimeEntity(title = "约会大作战 第二季", watchedEpisodes = 2))
            val third = dao.insertAnime(AnimeEntity(title = "第三部"))
            val original = dao.getAnime(first)!!
            val series = repo.groupDraggedAnimes(mapOf(first to null, second to null), null, "约会大作战")
            assertEquals(original.copy(seriesId = series), dao.getAnime(first))
            val target = repo.saveSeries(SeriesEntity(name = "已有系列"))
            repo.groupDraggedAnimes(mapOf(first to series), target, "")
            assertEquals(original.copy(seriesId = target), dao.getAnime(first))
            assertEquals(series, dao.getAnime(second)?.seriesId)
            assertNotNull(dao.getSeries(series))
            assertNull(dao.getAnime(third)?.seriesId)
        } finally { db.close() }
    }

    @Test fun staleDeletedOrMissingTargetsCannotPartiallyCreateOrMove() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao(); val repo = LibraryRepository(db)
            val first = dao.insertAnime(AnimeEntity(title = "第一部"))
            val removed = dao.insertAnime(AnimeEntity(title = "已回收", deletedAt = "2026-10-23"))
            suspend fun reject(expected: Map<Long, Long?>, target: Long? = null) {
                try { repo.groupDraggedAnimes(expected, target, "失败的新系列"); fail("must reject stale drop") }
                catch (_: IllegalArgumentException) { }
                assertNull(dao.getSeriesByName("失败的新系列"))
                assertNull(dao.getAnime(first)?.seriesId)
            }
            reject(mapOf(first to null, removed to null))
            reject(mapOf(first to 999L))
            reject(mapOf(first to null), 999L)
        } finally { db.close() }
    }
}
