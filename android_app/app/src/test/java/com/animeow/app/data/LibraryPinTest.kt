package com.animeow.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AniMeowDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LibraryPinTest {
    @Test fun pinSurvivesEditingAndCanBeUndoneWithoutChangingOtherData() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = LibraryRepository(db)
            val id = repository.saveAnime(AnimeEntity(title = "放映室", watchedEpisodes = 3), emptySet())
            val change = repository.setPinned(setOf(id), true)
            val stamp = requireNotNull(db.libraryDao().getAnime(id)?.pinnedAt)
            repository.saveAnime(AnimeEntity(id = id, title = "新的标题", watchedEpisodes = 4), emptySet())
            assertEquals(stamp, db.libraryDao().getAnime(id)?.pinnedAt)
            assertTrue(repository.setPinned(setOf(id), true).before.isEmpty())
            repository.restorePins(change)
            assertNull(db.libraryDao().getAnime(id)?.pinnedAt)
            assertEquals("新的标题", db.libraryDao().getAnime(id)?.title)
            assertEquals(4, db.libraryDao().getAnime(id)?.watchedEpisodes)
        } finally { db.close() }
    }

    @Test fun deletedWorksCannotBePinned() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val id = db.libraryDao().insertAnime(AnimeEntity(title = "已移除", deletedAt = "2026-09-13"))
            assertTrue(LibraryRepository(db).setPinned(setOf(id), true).before.isEmpty())
            assertNull(db.libraryDao().getAnime(id)?.pinnedAt)
        } finally { db.close() }
    }

    @Test fun versionEightMigrationPreservesExistingRowsAndDefaultsToUnpinned() {
        val context: Context = RuntimeEnvironment.getApplication()
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                override fun onCreate(db: SupportSQLiteDatabase) { db.execSQL("CREATE TABLE animes(id INTEGER PRIMARY KEY, title TEXT NOT NULL)") }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO animes(id, title) VALUES(1, '保留的作品')")
            AniMeowDatabase.MIGRATION_8_9.migrate(db)
            db.query("SELECT title, pinnedAt FROM animes WHERE id = 1").use {
                assertTrue(it.moveToFirst()); assertEquals("保留的作品", it.getString(0)); assertTrue(it.isNull(1))
            }
        } finally { helper.close() }
    }
}
