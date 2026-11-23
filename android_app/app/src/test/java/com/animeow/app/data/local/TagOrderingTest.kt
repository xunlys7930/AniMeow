package com.animeow.app.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import com.animeow.app.data.LibraryRepository
import com.animeow.app.data.backup.NativeBackupMerger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class TagOrderingTest {
    @Test fun cloudMergeKeepsLocalOrderAndFillsMissingRemoteOrder() {
        fun snapshot(order: Int?) = JSONObject().put("tags", JSONArray().put(
            JSONObject().put("id", 1).put("name", "标签").put("sortOrder", order ?: JSONObject.NULL),
        ))
        fun order(local: Int?, remote: Int?) = NativeBackupMerger.merge(
            snapshot(local), snapshot(remote), includeCore = true, includeAnalysis = false,
        ).getJSONArray("tags").getJSONObject(0).optInt("sortOrder", -1)
        assertEquals(2, order(2, 8))
        assertEquals(8, order(null, 8))
        assertEquals(2, order(2, null))
    }

    @Test fun savedOrderSurvivesRenameAndNewTagsAndRejectsStaleLists() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao()
            val repo = LibraryRepository(db)
            val a = repo.saveTag(TagEntity(name = "Alpha"))
            val b = repo.saveTag(TagEntity(name = "Beta"))
            assertEquals(listOf(a, b), dao.observeTags().first().map { it.id })
            repo.reorderTags(listOf(b, a))
            repo.saveTag(TagEntity(id = b, name = "Zeta", color = 123))
            val c = repo.saveTag(TagEntity(name = "New"))
            assertEquals(listOf(b, a, c), dao.getTags().map { it.id })
            assertEquals(0, dao.getTag(b)?.sortOrder)
            try { repo.reorderTags(listOf(a, b)); fail("stale order must not partially overwrite tags") }
            catch (_: IllegalArgumentException) { }
            assertEquals(listOf(b, a, c), dao.getTags().map { it.id })
            dao.setTagBlocked(b, true)
            assertEquals(listOf(a, c), dao.observeTags().first().map { it.id })
            assertEquals(listOf(b, a, c), dao.getAllTags().map { it.id })
        } finally { db.close() }
    }

    @Test fun upgradeFrom15PreservesTagsAndLinksAndPersistsOrderAfterReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "tag-order-${System.nanoTime()}.db"
        fun open() = Room.databaseBuilder(context, AniMeowDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(*AniMeowDatabase.ALL_MIGRATIONS).allowMainThreadQueries().build()
        var db = open()
        try {
            val anime = db.libraryDao().insertAnime(AnimeEntity(title = "保留作品", watchedEpisodes = 7))
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("ALTER TABLE animes DROP COLUMN statusChangedAt")
            sqlite.execSQL("ALTER TABLE animes DROP COLUMN updatedAt")
            sqlite.execSQL("DROP TABLE tags")
            sqlite.execSQL("CREATE TABLE tags (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, color INTEGER, blocked INTEGER NOT NULL)")
            sqlite.execSQL("CREATE UNIQUE INDEX index_tags_name ON tags (name)")
            sqlite.execSQL("INSERT INTO tags VALUES (1, 'Beta', 123, 0), (2, 'Alpha', 456, 0), (3, 'Blocked', NULL, 1)")
            sqlite.execSQL("INSERT INTO anime_tags VALUES (?, 1)", arrayOf(anime))
            sqlite.execSQL("DROP INDEX index_games_vndbId")
            sqlite.execSQL("ALTER TABLE games DROP COLUMN vndbId")
            sqlite.execSQL("ALTER TABLE games DROP COLUMN tags")
            sqlite.execSQL("DROP TABLE audio_works")
            sqlite.execSQL("ALTER TABLE series DROP COLUMN linkedMediaJson"); sqlite.execSQL("ALTER TABLE animes DROP COLUMN memo"); sqlite.execSQL("ALTER TABLE animes DROP COLUMN seriesRole"); sqlite.execSQL("ALTER TABLE animes DROP COLUMN bookReadVolumes"); sqlite.execSQL("ALTER TABLE animes DROP COLUMN bookReadChapters"); sqlite.execSQL("ALTER TABLE animes DROP COLUMN sourceRatingJson"); sqlite.version = 15
            db.close()
            db = open()
            assertEquals(listOf(2L, 1L), db.libraryDao().getTags().map { it.id })
            assertEquals(7, db.libraryDao().getAnime(anime)?.watchedEpisodes)
            assertEquals(listOf(1L), db.libraryDao().getTagIdsForAnime(anime))
            assertTrue(requireNotNull(db.libraryDao().getTag(3)).blocked)
            LibraryRepository(db).reorderTags(listOf(1L, 2L))
            db.close()
            db = open()
            assertEquals(listOf(1L, 2L), db.libraryDao().getTags().map { it.id })
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
