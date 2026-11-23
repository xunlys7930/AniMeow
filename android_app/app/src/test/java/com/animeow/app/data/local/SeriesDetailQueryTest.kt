package com.animeow.app.data.local

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.animeow.app.data.LibraryRepository
import java.io.File
import kotlin.system.measureNanoTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class SeriesDetailQueryTest {
    @Test fun targetedMembersKeepTheExistingOrderAndExcludeOtherSeriesAndTrash() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).build()
        try {
            val dao = db.libraryDao()
            dao.insertSeriesItem(SeriesEntity(id = 1, name = "系列"))
            val current = dao.insertAnime(AnimeEntity(title = "当前", seriesId = 1))
            dao.insertAnime(AnimeEntity(title = "较早在看", status = "在看", seriesId = 1, createdAt = "2026-10-01"))
            dao.insertAnime(AnimeEntity(title = "较新在看", status = "在看", seriesId = 1, createdAt = "2026-11-01"))
            dao.insertAnime(AnimeEntity(title = "已完成", status = "看完", seriesId = 1))
            dao.insertAnime(AnimeEntity(title = "已删除", seriesId = 1, deletedAt = "2026-11-03"))
            dao.insertAnime(AnimeEntity(title = "其他系列", seriesId = 2))
            val members = dao.observeSeriesMembers(1, current).first()
            assertEquals(dao.observeAnimes().first().filter { it.seriesId == 1L && it.id != current }, members)
            assertEquals(listOf("较新在看", "较早在看", "已完成"), members.map { it.title })
            assertEquals("系列", dao.observeSeries(1).first()?.name)
            assertNull(dao.observeSeries(999).first())
        } finally { db.close() }
    }

    @Test fun detailQueryAndProgressWriteAtOneAndFiveThousandWorks() = runBlocking {
        val results = mutableListOf<String>()
        for (size in listOf(1_000, 5_000)) {
            val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).build()
            try {
                val dao = db.libraryDao()
                db.withTransaction {
                    repeat(size) { n -> dao.insertAnime(AnimeEntity(id = n + 1L, title = "合成作品 $n",
                        seriesId = (n / 50 + 1L), status = "在看", totalEpisodes = 100)) }
                }
                suspend fun median(block: suspend () -> Unit): Double = (1..7).map {
                    measureNanoTime { block() } / 1_000_000.0
                }.sorted()[3]
                val fullMs = median { assertEquals(49, dao.observeAnimes().first().filter { it.seriesId == 1L && it.id != 1L }.size) }
                val targetedMs = median { assertEquals(49, dao.observeSeriesMembers(1, 1).first().size) }
                val repository = LibraryRepository(db)
                val progressMs = median { assertNotNull(repository.incrementProgress(1, false, "看完")) }
                assertEquals(7, dao.getAnime(1)?.watchedEpisodes)
                results += "{\"works\":$size,\"fullReadMedianMs\":$fullMs,\"seriesReadMedianMs\":$targetedMs,\"progressWriteMedianMs\":$progressMs}"
            } finally { db.close() }
        }
        File("../../build/android-maintenance-20261103/database-performance.json").apply {
            parentFile?.mkdirs(); writeText(results.joinToString(",", "[", "]"))
        }
        Unit
    }
}
