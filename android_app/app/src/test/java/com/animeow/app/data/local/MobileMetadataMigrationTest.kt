package com.animeow.app.data.local

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import com.animeow.app.data.audio.AudioWork
import com.animeow.app.data.games.GameEntity
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
class MobileMetadataMigrationTest {
    @Test fun upgradeFrom18KeepsPersonalRecordsAndGivesNewFieldsIndependentDefaults() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "mobile-metadata-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AniMeowDatabase::class.java, name).allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).addMigrations(*AniMeowDatabase.ALL_MIGRATIONS).build()
        var db = open()
        try {
            val gameId = db.gameDao().insert(GameEntity(title = "游戏", playMinutes = 720, rating = 9, review = "游戏心得"))
            val audioId = db.audioWorkDao().insert(AudioWork(title = "自定义音声名称", listenedSeconds = 123, rating = 8, review = "音声心得"))
            db.openHelper.writableDatabase.apply {
                execSQL("ALTER TABLE games DROP COLUMN tags")
                execSQL("ALTER TABLE animes DROP COLUMN updatedAt")
                execSQL("ALTER TABLE audio_works DROP COLUMN originalTitle")
                execSQL("ALTER TABLE series DROP COLUMN linkedMediaJson"); execSQL("ALTER TABLE animes DROP COLUMN memo"); execSQL("ALTER TABLE animes DROP COLUMN seriesRole"); execSQL("ALTER TABLE animes DROP COLUMN bookReadVolumes"); execSQL("ALTER TABLE animes DROP COLUMN bookReadChapters"); execSQL("ALTER TABLE animes DROP COLUMN sourceRatingJson"); version = 18
            }
            db.close(); db = open()
            val game = db.gameDao().get(gameId)!!
            val audio = db.audioWorkDao().get(audioId)!!
            assertEquals("", game.tags); assertEquals(720L, game.playMinutes); assertEquals(9, game.rating)
            assertEquals("", audio.originalTitle); assertEquals(123L, audio.listenedSeconds); assertEquals("音声心得", audio.review)
            db.gameDao().update(game.copy(tags = "剧情，治愈"))
            db.audioWorkDao().update(audio.copy(originalTitle = "原站名称"))
            db.close(); db = open()
            assertEquals("剧情，治愈", db.gameDao().get(gameId)!!.tags)
            assertEquals("原站名称", db.audioWorkDao().get(audioId)!!.originalTitle)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
