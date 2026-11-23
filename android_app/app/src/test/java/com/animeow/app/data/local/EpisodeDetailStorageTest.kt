package com.animeow.app.data.local

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.animeow.app.data.LibraryRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 分集资料的存储层：Room v11 → v12 迁移、DAO 往返，以及软删除与永久删除的不同语义。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class EpisodeDetailStorageTest {
    @Test
    fun searchTitlesExcludeTrashedWorksAndEpisodesWithoutATitle() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao()
            val activeId = dao.insertAnime(AnimeEntity(title = "收藏作品"))
            val deletedId = dao.insertAnime(AnimeEntity(title = "回收站作品", deletedAt = "2026-11-12"))
            dao.upsertEpisodeDetail(EpisodeDetailEntity(animeId = activeId, episode = 1, title = "旅程开始"))
            dao.upsertEpisodeDetail(EpisodeDetailEntity(animeId = activeId, episode = 2, review = "只写了评价"))
            dao.upsertEpisodeDetail(EpisodeDetailEntity(animeId = deletedId, episode = 1, title = "已删除标题"))
            assertEquals(listOf(EpisodeSearchTitle(activeId, 1, "旅程开始")), dao.observeEpisodeSearchTitles().first())
        } finally {
            db.close()
        }
    }

    @Test
    fun durationFlowUpdatesAfterEditingClearingAndMovingToTrash() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        val snapshots = Channel<List<EpisodeDetailEntity>>(Channel.UNLIMITED)
        val repository = LibraryRepository(db)
        val animeId = repository.saveAnime(AnimeEntity(title = "时长统计", watchedEpisodes = 2), emptySet())
        val observation = launch {
            repository.observeEpisodeDetailsWithDuration().collect { snapshots.send(it) }
        }
        suspend fun awaitDurations(expected: List<Int>) = withTimeout(10_000) {
            while (snapshots.receive().mapNotNull { it.durationMinutes }.sorted() != expected) { /* 等待 Room 通知 */ }
        }
        try {
            awaitDurations(emptyList())
            repository.saveEpisodeDetail(animeId, 1, "第一集", 45, "", "", null)
            awaitDurations(listOf(45))
            repository.saveEpisodeDetail(animeId, 1, "第一集", 90, "", "", null)
            awaitDurations(listOf(90))
            repository.saveEpisodeDetail(animeId, 1, "第一集", null, "", "", null)
            awaitDurations(emptyList())
            repository.saveEpisodeDetail(animeId, 2, "第二集", 30, "", "", null)
            awaitDurations(listOf(30))
            repository.deleteAnime(animeId)
            awaitDurations(emptyList())
        } finally {
            observation.cancel()
            snapshots.close()
            db.close()
        }
    }

    @Test
    fun mergingMultipleSourcesPreservesTargetAndAllOriginalRows() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao()
            dao.upsertEpisodeDetail(EpisodeDetailEntity(animeId = 1, episode = 2, title = "目标保留"))
            dao.upsertEpisodeDetail(EpisodeDetailEntity(animeId = 2, episode = 1, title = "来源一"))
            dao.upsertEpisodeDetail(EpisodeDetailEntity(animeId = 3, episode = 1, review = "来源二评价"))
            dao.upsertEpisodeDetail(EpisodeDetailEntity(animeId = 3, episode = 2, title = "不能覆盖目标"))
            dao.upsertEpisodeDetail(EpisodeDetailEntity(animeId = 3, episode = 3, title = "第三集"))
            dao.copyEpisodeDetails(1, listOf(3, 2))
            assertEquals(listOf(1, 2, 3), dao.getEpisodeDetails(1).map { it.episode })
            assertEquals("来源一", dao.getEpisodeDetail(1, 1)?.title)
            assertEquals("目标保留", dao.getEpisodeDetail(1, 2)?.title)
            assertEquals("来源二评价", dao.getEpisodeDetail(3, 1)?.review)
            assertEquals(3, dao.getEpisodeDetails(3).size)
        } finally {
            db.close()
        }
    }

    @Test
    fun versionElevenMigrationAddsEpisodeDetailsAndKeepsExistingRows() {
        val context: Context = RuntimeEnvironment.getApplication()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : SupportSQLiteOpenHelper.Callback(11) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE animes(id INTEGER PRIMARY KEY, title TEXT NOT NULL, " +
                                "watchedEpisodes INTEGER NOT NULL DEFAULT 0)",
                        )
                        db.execSQL(
                            "CREATE TABLE watch_records(id INTEGER PRIMARY KEY, animeId INTEGER NOT NULL, " +
                                "episode INTEGER NOT NULL, status TEXT, recordDate TEXT)",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO animes(id, title, watchedEpisodes) VALUES(1, '保留的作品', 7)")
            db.execSQL(
                "INSERT INTO watch_records(id, animeId, episode, status, recordDate) " +
                    "VALUES(1, 1, 7, 'watched', '2026-10-08T00:00:00Z')",
            )
            AniMeowDatabase.MIGRATION_11_12.migrate(db)

            db.query("SELECT title, watchedEpisodes FROM animes WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("保留的作品", cursor.getString(0))
                assertEquals(7, cursor.getInt(1))
            }
            db.query("SELECT COUNT(*) FROM watch_records").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.execSQL(
                "INSERT INTO episode_details (animeId, episode, title, durationMinutes, synopsis, review, rating, updatedAt) " +
                    "VALUES (1, 7, '第七集', 24, '提要', '感想', 85, '2026-10-08T00:00:00Z')",
            )
            db.query("SELECT title, durationMinutes, rating FROM episode_details WHERE animeId = 1 AND episode = 7")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("第七集", cursor.getString(0))
                    assertEquals(24, cursor.getInt(1))
                    assertEquals(85, cursor.getInt(2))
                }
        } finally {
            helper.close()
        }
    }

    @Test
    fun upsertKeepsOneRowPerEpisodeAndDeletesOnRequest() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao()
            dao.upsertEpisodeDetail(
                EpisodeDetailEntity(animeId = 1L, episode = 3, title = "旧标题", updatedAt = "2026-10-08T00:00:00Z"),
            )
            dao.upsertEpisodeDetail(
                EpisodeDetailEntity(
                    animeId = 1L,
                    episode = 3,
                    title = "新标题",
                    durationMinutes = 24,
                    rating = 85,
                    updatedAt = "2026-10-08T01:00:00Z",
                ),
            )
            val rows = dao.getEpisodeDetails(1L)
            assertEquals(1, rows.size)
            assertEquals("新标题", rows.first().title)
            assertEquals(24, rows.first().durationMinutes)
            assertNull(dao.getEpisodeDetail(1L, 4))

            dao.deleteEpisodeDetail(1L, 3)
            assertTrue(dao.getEpisodeDetails(1L).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun blankDraftDeletesTheStoredRow() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = LibraryRepository(db)
            val animeId = repository.saveAnime(AnimeEntity(title = "分集资料", totalEpisodes = 12), emptySet())
            repository.saveEpisodeDetail(animeId, 2, "第二集", 24, "提要", "感想", 85)
            assertEquals(1, repository.getEpisodeDetails(animeId).size)

            val kept = repository.saveEpisodeDetail(animeId, 2, "  ", null, "", "", null)
            assertNull(kept)
            assertTrue(repository.getEpisodeDetails(animeId).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun trashKeepsEpisodeDetailsAndPermanentDeleteDropsThem() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = LibraryRepository(db)
            val animeId = repository.saveAnime(AnimeEntity(title = "回收站", totalEpisodes = 12), emptySet())
            repository.saveEpisodeDetail(animeId, 5, "第五集", null, "", "", null)

            repository.deleteAnime(animeId)
            assertEquals(1, repository.getEpisodeDetails(animeId).size)

            repository.permanentlyDeleteAnime(animeId)
            assertTrue(repository.getEpisodeDetails(animeId).isEmpty())
        } finally {
            db.close()
        }
    }
}
