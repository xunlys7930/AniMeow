package com.animeow.app.data.reader

import android.app.Application
import androidx.core.net.toUri
import androidx.room.Room
import androidx.room.RoomDatabase
import com.animeow.app.data.backup.NativeBackupService
import com.animeow.app.data.backup.NativeRestoreSelection
import com.animeow.app.data.backup.NativeRestoreStrategy
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.reader.legado.LegadoBookshelfParser
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class BookReaderBackupAndMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var workDir: File
    private lateinit var db: AniMeowDatabase
    private lateinit var repo: BookReaderRepository
    private lateinit var backupService: NativeBackupService

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("animeow-reader-repo-test").toFile()
        db = Room.databaseBuilder(context, AniMeowDatabase::class.java, "reader-test-${System.nanoTime()}.db")
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        repo = BookReaderRepository(context, db)
        backupService = NativeBackupService(context, db)
    }

    @After
    fun tearDown() {
        val dbName = db.openHelper.databaseName
        db.close()
        if (dbName != null) context.deleteDatabase(dbName)
        workDir.deleteRecursively()
    }

    @Test
    fun importsLocalTxtSavesProgressDeduplicatesDailyFootprintAndRecoversMovedFileByFingerprint() = runBlocking {
        val novelText = buildString {
            appendLine("第一章 启程")
            appendLine("少年背起行囊走出小镇。")
            appendLine("第二章 旅途")
            appendLine("列车穿过漫长的隧道。")
            appendLine("第三章 抵达")
            appendLine("大海在阳光下闪耀。")
        }
        val originalFile = File(workDir, "旅途之书.txt").apply { writeText(novelText, Charsets.UTF_8) }

        val animeId = repo.importLocalEbookAsNewWork(originalFile.toUri())
        val created = requireNotNull(db.libraryDao().getAnime(animeId))
        assertEquals("book", created.subjectType)
        assertEquals("想看", created.status)
        assertEquals(3, created.totalEpisodes)

        // Reading chapter 2 (index 1, block 1) transitions "想看" -> "在看" and writes 1 daily WatchRecordEntity
        repo.saveReadingPosition(
            animeId = animeId,
            chapterIndex = 1,
            chapterTitle = "第二章 旅途",
            totalChapters = 3,
            blockIndex = 1,
            charOffset = 0,
            totalBlocksInChapter = 5,
            autoSyncMainProgress = true,
            autoCompleteLastChapter = true,
        )
        // Saving again in same chapter on same day must deduplicate watch_records!
        repo.saveReadingPosition(
            animeId = animeId,
            chapterIndex = 1,
            chapterTitle = "第二章 旅途",
            totalChapters = 3,
            blockIndex = 3,
            charOffset = 0,
            totalBlocksInChapter = 5,
            autoSyncMainProgress = true,
            autoCompleteLastChapter = true,
        )

        val updatedAnime = requireNotNull(db.libraryDao().getAnime(animeId))
        assertEquals("在看", updatedAnime.status)
        assertEquals(2, updatedAnime.watchedEpisodes)
        assertEquals(2, updatedAnime.bookReadChapters)
        val watchRecords = db.libraryDao().getWatchRecordsForAnime(animeId)
        assertEquals(1, watchRecords.size)
        assertEquals(2, watchRecords.single().episode)

        repo.addBookmark(
            animeId = animeId,
            chapterIndex = 1,
            chapterTitle = "第二章 旅途",
            blockIndex = 1,
            charOffset = 0,
            snippet = "列车穿过漫长的隧道。",
        )
        assertEquals(1, db.bookReadingDao().getBookmarks(animeId).size)

        // Simulate user moving the file to a new path: binding new path with identical content recovers position!
        val movedFile = File(workDir, "relocated_旅途之书.txt").apply { writeText(novelText, Charsets.UTF_8) }
        val reboundState = repo.bindLocalEbook(animeId, movedFile.toUri())
        assertEquals(1, reboundState.chapterIndex)
        assertEquals(3, reboundState.blockIndex)
        assertEquals(583, reboundState.progressPermille)
        assertEquals(movedFile.toUri().toString(), reboundState.localFileUri)
    }

    @Test
    fun stripsLocalFileUriInBackupZipWhileReattachingSameDeviceUriAndProtectingUnitMismatch() = runBlocking {
        // 1. Create a Bangumi 12-volume light novel and apply 360-chapter Legado sync (unit mismatch protected)
        val bgmBookId = db.libraryDao().insertAnime(
            AnimeEntity(
                title = "狼与香辛料",
                subjectType = "book",
                status = "在看",
                watchedEpisodes = 6,
                totalEpisodes = 17,
                bookReadVolumes = 6,
                externalSource = "bangumi",
                externalId = "870",
            ),
        )
        val legadoItems = LegadoBookshelfParser.parseJsonText(
            """
            [{"bookUrl":"https://legado.example/spice","name":"狼与香辛料","author":"支仓冻砂","durChapterIndex":180,"durChapterTitle":"第181幕","durChapterPos":42,"totalChapterNum":320,"durChapterTime":1731100000000}]
            """.trimIndent(),
        )
        val preview = repo.buildLegadoPreview(legadoItems)
        val summary = repo.applyLegadoSync(preview)
        assertEquals(0, summary.addedCount)
        assertEquals(1, summary.updatedCount)
        assertEquals(1, summary.unitProtectedCount)

        // Main volume count (6 / 17) is preserved, while Legado chapter state is recorded in book_reading_states!
        val afterSyncBook = requireNotNull(db.libraryDao().getAnime(bgmBookId))
        assertEquals(6, afterSyncBook.watchedEpisodes)
        assertEquals(17, afterSyncBook.totalEpisodes)
        val syncedState = requireNotNull(db.bookReadingDao().getState(bgmBookId))
        assertEquals("https://legado.example/spice", syncedState.legadoBookUrl)
        assertEquals(180, syncedState.legadoChapterIndex)

        // 2. Also bind a local TXT file to test localFileUri privacy stripping + same-device fingerprint re-attachment
        val txtFile = File(workDir, "spice_extra.txt").apply {
            writeText("番外 旅途的苹果\n赫萝咬了一口红苹果。\n", Charsets.UTF_8)
        }
        repo.bindLocalEbook(bgmBookId, txtFile.toUri())
        repo.saveReadingPosition(
            animeId = bgmBookId,
            chapterIndex = 0,
            chapterTitle = "番外 旅途的苹果",
            totalChapters = 1,
            blockIndex = 3,
            charOffset = 0,
            totalBlocksInChapter = 5,
            autoSyncMainProgress = false,
            autoCompleteLastChapter = false,
        )
        repo.addBookmark(
            animeId = bgmBookId,
            chapterIndex = 0,
            chapterTitle = "番外 旅途的苹果",
            blockIndex = 1,
            charOffset = 0,
            snippet = "赫萝咬了一口红苹果。",
        )

        val backupZip = File(workDir, "reader-backup.animeow.zip")
        backupService.export(backupZip, includeFonts = false, includeCovers = false)

        // Verify exported database.json strips device-private localFileUri!
        ZipFile(backupZip).use { zip ->
            val dbJson = JSONObject(zip.getInputStream(zip.getEntry("database.json")).bufferedReader().readText())
            val stateRow = dbJson.getJSONArray("book_reading_states").getJSONObject(0)
            assertTrue("localFileUri must be null in exported backup", stateRow.isNull("localFileUri"))
            assertNotNull(stateRow.optString("fileFingerprint", null))
            assertEquals("https://legado.example/spice", stateRow.getString("legadoBookUrl"))
            assertEquals(1, dbJson.getJSONArray("book_bookmarks").length())
        }

        // 3. Same-device REPLACE restore re-attaches localFileUri via matching fileFingerprint!
        val preparedReplace = backupService.prepareRestore(backupZip)
        try {
            backupService.restore(
                preparedReplace,
                NativeRestoreSelection(libraryAndCharacters = true, analysisHistory = false, appearanceSettings = false),
                NativeRestoreStrategy.REPLACE,
            )
        } finally {
            backupService.discard(preparedReplace)
        }
        val restoredState = requireNotNull(db.bookReadingDao().getState(bgmBookId))
        assertEquals(txtFile.toUri().toString(), restoredState.localFileUri)
        assertEquals(750, restoredState.progressPermille)
        assertEquals("https://legado.example/spice", restoredState.legadoBookUrl)
        assertEquals(1, db.bookReadingDao().getBookmarks(bgmBookId).size)

        // 4. Cross-device restore into a fresh DB keeps reading progress + Legado state with localFileUri = null
        val otherDb = Room.databaseBuilder(context, AniMeowDatabase::class.java, "other-device-${System.nanoTime()}.db")
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        try {
            val otherService = NativeBackupService(context, otherDb)
            val preparedCross = otherService.prepareRestore(backupZip)
            try {
                otherService.restore(
                    preparedCross,
                    NativeRestoreSelection(libraryAndCharacters = true, analysisHistory = false, appearanceSettings = false),
                    NativeRestoreStrategy.REPLACE,
                )
            } finally {
                otherService.discard(preparedCross)
            }
            val crossBook = otherDb.libraryDao().getAllAnimesIncludingDeleted().single()
            val crossState = requireNotNull(otherDb.bookReadingDao().getState(crossBook.id))
            assertNull(crossState.localFileUri)
            assertEquals(750, crossState.progressPermille)
            assertEquals("https://legado.example/spice", crossState.legadoBookUrl)
            assertEquals(1, otherDb.bookReadingDao().getBookmarks(crossBook.id).size)
        } finally {
            val otherName = otherDb.openHelper.databaseName
            otherDb.close()
            if (otherName != null) context.deleteDatabase(otherName)
        }
    }
}
