package com.animeow.app.data

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import com.animeow.app.data.audio.AudioWork
import com.animeow.app.data.games.GameEntity
import com.animeow.app.data.local.AniMeowDatabase
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class MediaSeriesRepositoryTest {
    @Test fun groupsReordersMovesAndDetachesWithoutChangingPersonalRecords() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val game = GameEntity(syncId = "shared", title = "游戏一", playMinutes = 420, rating = 9, review = "保留")
            val second = GameEntity(title = "游戏二")
            val audio = AudioWork(syncId = "shared", title = "音声", listenedSeconds = 123, review = "感想")
            val gameId = db.gameDao().insert(game); db.gameDao().insert(second)
            val audioId = db.audioWorkDao().insert(audio)
            val gameRepo = MediaSeriesRepository(db, MediaSeriesKind.GAME)
            val audioRepo = MediaSeriesRepository(db, MediaSeriesKind.AUDIO)
            val works = listOf(game, second).map { MediaSeriesWork(it.syncId, it.title, it.updatedAt) }
            val firstId = gameRepo.save(null, mediaSeriesMembersVersion(emptyList()), works, "同名系列", listOf(second.syncId, game.syncId))
            audioRepo.save(null, mediaSeriesMembersVersion(emptyList()), listOf(MediaSeriesWork(audio.syncId, audio.title, audio.updatedAt)), "同名系列", listOf(audio.syncId))
            val dao = db.mediaSeriesDao()
            gameRepo.save(dao.get(firstId), mediaSeriesMembersVersion(dao.members("GAME")), works, "改名", listOf(game.syncId, second.syncId))
            assertEquals(listOf(game.syncId, second.syncId), dao.members("GAME").map { it.workSyncId })
            val nextId = gameRepo.save(null, mediaSeriesMembersVersion(dao.members("GAME")), works, "另一系列", listOf(second.syncId))
            assertEquals(nextId, dao.members("GAME").first { it.workSyncId == second.syncId }.seriesId)
            gameRepo.delete(dao.get(firstId)!!, mediaSeriesMembersVersion(dao.members("GAME")))
            assertNull(dao.members("GAME").first { it.workSyncId == game.syncId }.seriesId)
            assertNotNull(dao.get(firstId)!!.deletedAt)
            assertEquals(game.copy(id = gameId), db.gameDao().get(gameId))
            assertEquals(audio.copy(id = audioId), db.audioWorkDao().get(audioId))
            assertNotNull(dao.members("AUDIO").single().seriesId)
        } finally { db.close() }
    }

    @Test fun rejectsStaleMembershipDeletedWorksWrongModuleAndUnknownIdentitiesAtomically() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val work = GameEntity(title = "作品")
            val id = db.gameDao().insert(work)
            val repo = MediaSeriesRepository(db, MediaSeriesKind.GAME)
            val works = listOf(MediaSeriesWork(work.syncId, work.title, work.updatedAt))
            val seriesId = repo.save(null, mediaSeriesMembersVersion(emptyList()), works, "系列", listOf(work.syncId))
            val dao = db.mediaSeriesDao(); val series = dao.get(seriesId)!!; val members = dao.members("GAME")
            assertTrue(runCatching { repo.save(series, mediaSeriesMembersVersion(emptyList()), works, "旧草稿", emptyList()) }.isFailure)
            assertTrue(runCatching { repo.save(null, mediaSeriesMembersVersion(members), works, "假成员", listOf("unknown")) }.isFailure)
            assertTrue(runCatching { MediaSeriesRepository(db, MediaSeriesKind.AUDIO).delete(series, mediaSeriesMembersVersion(emptyList())) }.isFailure)
            db.gameDao().update(work.copy(id = id, deletedAt = "2099-01-01T00:00:00Z"))
            assertTrue(runCatching { repo.save(series, mediaSeriesMembersVersion(members), works, "已删除成员", listOf(work.syncId)) }.isFailure)
            assertEquals(series, dao.get(seriesId)); assertEquals(members, dao.members("GAME"))
            assertEquals(1, dao.series("GAME").size)
        } finally { db.close() }
    }

    @Test fun migrationFrom19AddsSeriesWithoutLosingWorkFields() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "series-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AniMeowDatabase::class.java, name).allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).addMigrations(*AniMeowDatabase.ALL_MIGRATIONS).build()
        var db = open()
        try {
            val gameId = db.gameDao().insert(GameEntity(title = "游戏", tags = "标签", playMinutes = 123))
            val audioId = db.audioWorkDao().insert(AudioWork(title = "音声", originalTitle = "原名", listenedSeconds = 456))
            db.openHelper.writableDatabase.apply {
                execSQL("ALTER TABLE animes DROP COLUMN updatedAt")
                execSQL("DROP TABLE media_series_members"); execSQL("DROP TABLE media_series"); execSQL("ALTER TABLE series DROP COLUMN linkedMediaJson"); execSQL("ALTER TABLE animes DROP COLUMN memo"); execSQL("ALTER TABLE animes DROP COLUMN seriesRole"); execSQL("ALTER TABLE animes DROP COLUMN bookReadVolumes"); execSQL("ALTER TABLE animes DROP COLUMN bookReadChapters"); execSQL("ALTER TABLE animes DROP COLUMN sourceRatingJson"); version = 19
            }
            db.close(); db = open()
            assertEquals("标签", db.gameDao().get(gameId)!!.tags)
            assertEquals(456L, db.audioWorkDao().get(audioId)!!.listenedSeconds)
            assertEquals("原名", db.audioWorkDao().get(audioId)!!.originalTitle)
            assertTrue(db.mediaSeriesDao().series("GAME").isEmpty())
            assertTrue(db.mediaSeriesDao().members("AUDIO").isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
