package com.animeow.app.data.local

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class DatabaseUpgradePathsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun everyTrackedHistoricalVersionUpgradesWithPersonalDataAndRelations() = runBlocking {
        val baseline = requireNotNull(javaClass.getResourceAsStream("/v8.sql")).bufferedReader().use { it.readText() }
            .lineSequence().filterNot { it.startsWith("--") }.joinToString("\n").split(';').filter { it.isNotBlank() }
        for (version in 8..25) {
            val name = "upgrade-$version-${UUID.randomUUID()}.db"
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name).callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        baseline.forEach { db.execSQL(it) }
                        AniMeowDatabase.ALL_MIGRATIONS.filter { it.startVersion >= 8 && it.endVersion <= version }
                            .forEach { it.migrate(db) }
                        db.execSQL("INSERT INTO series(id,name) VALUES(4,'旧系列')")
                        db.execSQL("INSERT INTO animes(id,title,coverUrl,status,rating,review,seriesId,watchedEpisodes,totalEpisodes,tvEpisodes,spEpisodes,subjectType) VALUES(1,'旧作品','file:///retained/cover.png','在看',87,'个人感想',4,7,12,12,0,'anime')")
                        db.execSQL("INSERT INTO tags(id,name,blocked) VALUES(2,'旧标签',0)")
                        db.execSQL("INSERT INTO anime_tags(animeId,tagId) VALUES(1,2)")
                        db.execSQL("INSERT INTO characters(id,name) VALUES(3,'旧角色')")
                        db.execSQL("INSERT INTO anime_characters(animeId,characterId,roleName,sortOrder) VALUES(1,3,'主角',0)")
                        db.execSQL("INSERT INTO watch_records(animeId,episode,status) VALUES(1,7,'watched')")
                        if (version >= 12) db.execSQL("INSERT INTO episode_details(animeId,episode,review) VALUES(1,7,'分集感想')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
            helper.writableDatabase
            helper.close()
            val upgraded = Room.databaseBuilder(context, AniMeowDatabase::class.java, name)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).addMigrations(*AniMeowDatabase.ALL_MIGRATIONS).build()
            try {
                val dao = upgraded.libraryDao()
                val saved = requireNotNull(dao.getAnime(1))
                assertEquals("v$version", "旧作品", saved.title)
                assertNull(saved.updatedAt)
                assertEquals(7, saved.watchedEpisodes); assertEquals(87, saved.rating)
                assertEquals("个人感想", saved.review); assertEquals("file:///retained/cover.png", saved.coverUrl)
                assertEquals(4L, saved.seriesId); assertEquals(listOf(2L), dao.getTagIdsForAnime(1))
                assertEquals("主角", dao.getAnimeCharacter(1, 3)?.roleName)
                assertEquals(1, dao.getWatchRecords().size)
                if (version >= 12) assertEquals("分集感想", dao.getEpisodeDetail(1, 7)?.review)
            } finally { upgraded.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun exportedSchemaMatchesTheCurrentDatabase() {
        val schema = JSONObject(requireNotNull(javaClass.getResourceAsStream(
            "/com.animeow.app.data.local.AniMeowDatabase/26.json")).bufferedReader().use { it.readText() }).getJSONObject("database")
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).build()
        try {
            val sqlite = db.openHelper.writableDatabase
            assertEquals(schema.getInt("version"), sqlite.version)
            sqlite.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
                assertTrue(it.moveToFirst()); assertEquals(schema.getString("identityHash"), it.getString(0))
            }
        } finally { db.close() }
    }
}
