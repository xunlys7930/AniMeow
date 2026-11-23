package com.animeow.app.data.audio

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.backup.NativeBackupMerger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class AudioWorkTest {
    @Test fun quickProgressClampsDurationAndPreservesPersonalMetadata() {
        val work = AudioWork(title = "音声", listenedSeconds = 70, durationSeconds = 120, rating = 9, review = "感想", tags = "睡前")
        val added = quickAudioProgress(work, false)
        assertEquals(work.copy(listenedSeconds = 120, status = "在听"), added)
        assertEquals(work.copy(listenedSeconds = 120, status = "已听"), quickAudioProgress(work, true))
        val unknown = work.copy(durationSeconds = 0, status = "搁置")
        assertEquals(unknown.copy(status = "已听"), quickAudioProgress(unknown, true))
        assertEquals("搁置", quickAudioProgress(unknown, false).status)
        assertEquals(31_536_000L, quickAudioProgress(unknown.copy(listenedSeconds = 31_535_900), false).listenedSeconds)
    }

    @Test fun undoRestoresExactProgressButNeverOverwritesLaterEditsOrDeletion() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = AudioWorkRepository(db)
            val id = repo.save(AudioWork(title = "音声", durationSeconds = 600, rating = 8, review = "保留"))
            val original = requireNotNull(db.audioWorkDao().get(id))
            val change = repo.recordProgress(original, false)
            assertEquals(300L, db.audioWorkDao().get(id)?.listenedSeconds)
            repo.undoProgress(change)
            assertEquals(original.copy(updatedAt = db.audioWorkDao().get(id)!!.updatedAt), db.audioWorkDao().get(id))
            val next = repo.recordProgress(requireNotNull(db.audioWorkDao().get(id)), true)
            repo.save(next.after.copy(review = "后续编辑"))
            try { repo.undoProgress(next); fail("Undo must reject later edits") } catch (_: IllegalStateException) { }
            assertEquals("后续编辑", db.audioWorkDao().get(id)?.review)
            try { repo.recordProgress(next.after, false); fail("Stale row must not overwrite edits") } catch (_: IllegalStateException) { }
            val latest = requireNotNull(db.audioWorkDao().get(id))
            val last = repo.recordProgress(latest, true)
            repo.delete(last.after)
            try { repo.undoProgress(last); fail("Undo must not resurrect a deleted work") } catch (_: IllegalStateException) { }
            assertNotNull(db.audioWorkDao().get(id)?.deletedAt)
        } finally { db.close() }
    }
    @Test fun originalTitleIsIndependentAndMissingServerFieldsDoNotEraseIt() {
        val work = AudioWork(title = "自定义译名", originalTitle = "原站名称", listenedSeconds = 90, rating = 8, review = "保留感想")
        val metadata = com.animeow.app.data.remote.AudioMetadata("来源名称", "", "", "", "", "RJ12345678", "", "", "其他", "来源原名")
        val filled = applyAudioMetadata(work, metadata, false)
        assertEquals(work.originalTitle, filled.originalTitle)
        assertEquals(work.title, filled.title)
        val replaced = applyAudioMetadata(work, metadata, true)
        assertEquals("来源原名", replaced.originalTitle)
        assertEquals(90L, replaced.listenedSeconds)
        assertEquals(8, replaced.rating)
        assertEquals(work.review, replaced.review)
        assertEquals(work.originalTitle, applyAudioMetadata(work, metadata.copy(originalTitle = ""), true).originalTitle)
    }

    @Test fun timeAndMetadataValidationPreserveExplicitUserChoices() {
        assertEquals(90L, parseAudioTime("1:30")); assertEquals(120L, parseAudioTime("2")); assertNull(parseAudioTime("1:90"))
        assertNull(parseAudioTime("-1")); assertEquals(0L, parseAudioTime(""))
        val work = normalizeAudioWork(AudioWork(title = " 作品 ", tags = "ASMR,睡眠，ASMR", listenedSeconds = 90, durationSeconds = 120))
        assertEquals("作品", work.title); assertEquals("ASMR，睡眠", work.tags); assertEquals("想听", work.status)
        for (bad in listOf(work.copy(listenedSeconds = 121), work.copy(rating = 11), work.copy(sourceUrl = "javascript:alert(1)"), work.copy(releaseDate = "2026-99-01"))) {
            try { normalizeAudioWork(bad); fail("Invalid metadata must not save") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun saveAndDeletionRetainPortableIdentityAndTombstones() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = AudioWorkRepository(db)
            val id = repo.save(AudioWork(title = "音声", status = "在听", listenedSeconds = 120, tags = "个人标签"))
            val row = requireNotNull(db.audioWorkDao().get(id))
            repo.save(row.copy(rating = 9, review = "感想"))
            assertEquals(row.syncId, db.audioWorkDao().get(id)?.syncId)
            assertEquals("感想", repo.works.first().single().review)
            try { repo.save(row.copy(review = "过期草稿")); fail("A stale draft must not overwrite a newer record") } catch (_: IllegalStateException) { }
            repo.delete(requireNotNull(db.audioWorkDao().get(id)))
            assertTrue(repo.works.first().isEmpty()); assertNotNull(db.audioWorkDao().get(id)?.deletedAt)
        } finally { db.close() }
    }
    @Test fun migrationFrom16PreservesTheExistingLibraryAndAddsAnEmptyAudioLibrary() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "audio-migration-${System.nanoTime()}.db"
        fun open() = Room.databaseBuilder(context, AniMeowDatabase::class.java, name).setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries().addMigrations(*AniMeowDatabase.ALL_MIGRATIONS).build()
        var db = open()
        try {
            db.libraryDao().insertAnime(AnimeEntity(title = "保留动画", watchedEpisodes = 7))
            db.openHelper.writableDatabase.execSQL("ALTER TABLE animes DROP COLUMN updatedAt")
            db.openHelper.writableDatabase.apply { execSQL("DROP TABLE audio_works"); execSQL("DROP INDEX index_games_vndbId"); execSQL("ALTER TABLE games DROP COLUMN vndbId"); execSQL("ALTER TABLE games DROP COLUMN tags"); execSQL("ALTER TABLE series DROP COLUMN linkedMediaJson"); execSQL("ALTER TABLE animes DROP COLUMN memo"); execSQL("ALTER TABLE animes DROP COLUMN seriesRole"); execSQL("ALTER TABLE animes DROP COLUMN bookReadVolumes"); execSQL("ALTER TABLE animes DROP COLUMN bookReadChapters"); execSQL("ALTER TABLE animes DROP COLUMN sourceRatingJson"); version = 16 }
            db.close(); db = open()
            assertEquals(7, db.libraryDao().getActiveAnimes().single().watchedEpisodes)
            assertTrue(db.audioWorkDao().observe().first().isEmpty())
            val id = db.audioWorkDao().insert(AudioWork(title = "新音声"))
            db.close(); db = open(); assertEquals("新音声", db.audioWorkDao().get(id)?.title)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun cloudMergeUsesStableIdentityAndKeepsNewerCorrectionsAndDeletion() {
        fun snapshot(id: Int, updated: String, deleted: String? = null) = JSONObject().put("audio_works", JSONArray().put(
            JSONObject().put("id", id).put("syncId", "same-work").put("title", "音声").put("listenedSeconds", 0)
                .put("updatedAt", updated).put("deletedAt", deleted ?: JSONObject.NULL)))
        val merged = NativeBackupMerger.merge(snapshot(1, "2026-10-17T00:00:00Z"), snapshot(2, "2026-10-18T00:00:00Z", "2026-10-18T00:00:00Z"), true, false)
            .getJSONArray("audio_works")
        assertEquals(1, merged.length()); assertEquals(1, merged.getJSONObject(0).getInt("id")); assertEquals("2026-10-18T00:00:00Z", merged.getJSONObject(0).getString("deletedAt"))
    }
}
