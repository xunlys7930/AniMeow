package com.animeow.app.data.backup

import android.content.Context
import androidx.core.net.toUri
import androidx.room.Room
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.preferences.AppearancePreferences
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class NativeRestoreFailureTest {
    @Before fun reset() {
        database.clearAllTables()
        File(context.filesDir, "branding").deleteRecursively()
        File(context.filesDir, "covers").deleteRecursively()
        service.restoreCheckpoint = {}
    }

    @Test fun brandingFailureAfterDeletingTheOldDirectoryMustRestoreTheOldFiles() = runBlocking {
        fixture { prepared, old ->
            service.restoreCheckpoint = { if (it == NativeRestoreStage.BRANDING_CLEARED) throw java.io.IOException("synthetic copy failure") }
            expectFailure { service.restore(prepared) }
            assertOld(old)
        }
    }

    @Test fun everyPreCommitStageRollsBackDatabasePreferencesAndResources() = runBlocking {
        val stages = listOf(NativeRestoreStage.COVERS_COPIED, NativeRestoreStage.BRANDING_BACKED_UP, NativeRestoreStage.BRANDING_COPIED,
            NativeRestoreStage.DATABASE_REPLACED, NativeRestoreStage.APPEARANCE_WRITTEN, NativeRestoreStage.ANALYSIS_WRITTEN,
            NativeRestoreStage.DISCOVERY_WRITTEN, NativeRestoreStage.CALENDAR_WRITTEN, NativeRestoreStage.MAINTENANCE_WRITTEN,
            NativeRestoreStage.BRANDING_WRITTEN, NativeRestoreStage.TRACKER_WRITTEN, NativeRestoreStage.FILTERS_WRITTEN,
            NativeRestoreStage.EDITOR_WRITTEN, NativeRestoreStage.CLOUD_WRITTEN, NativeRestoreStage.PROFILES_WRITTEN)
        for (stage in stages) {
            reset()
            fixture { prepared, old ->
                var reached = false
                service.restoreCheckpoint = { if (it == stage) { reached = true; throw java.io.IOException("synthetic failure at $stage") } }
                expectFailure { service.restore(prepared) }
                assertTrue("The selected stage must actually execute: $stage", reached)
                assertOld(old)
                assertFalse(prepared.recoveryRequired)
            }
        }
    }

    @Test fun secondFileCopyFailuresKeepBothOriginalAssetsAndCleanNewCoverDirectories() = runBlocking {
        for (stage in listOf(NativeRestoreStage.COPYING_COVERS, NativeRestoreStage.COPYING_BRANDING_BACKUP, NativeRestoreStage.COPYING_BRANDING)) {
            reset()
            fixture { prepared, old ->
                var copies = 0
                service.restoreCheckpoint = { if (it == stage && ++copies == 2) throw java.io.IOException("synthetic second-file failure") }
                expectFailure { service.restore(prepared) }
                assertEquals(2, copies); assertOld(old); assertFalse(prepared.recoveryRequired)
            }
        }
    }

    @Test fun actualCancellationDuringPreferenceWritesFinishesRollback() = runBlocking {
        fixture { prepared, old ->
            val reached = CompletableDeferred<Unit>()
            service.restoreCheckpoint = { if (it == NativeRestoreStage.APPEARANCE_WRITTEN) { reached.complete(Unit); awaitCancellation() } }
            val task = launch { service.restore(prepared) }
            withTimeout(10000) { reached.await() }; withTimeout(10000) { task.cancelAndJoin() }
            assertOld(old); assertFalse(prepared.recoveryRequired)
        }
    }

    @Test fun cancellationAfterCommitKeepsCommittedPreferencesAndLiveCoverFiles() = runBlocking {
        fixture { prepared, _ ->
            val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            service.restoreCheckpoint = { if (it == NativeRestoreStage.DATABASE_COMMITTED) { reached.complete(Unit); release.await() } }
            val task = launch { service.restore(prepared) }
            withTimeout(10000) { reached.await() }; task.cancel(); release.complete(Unit)
            withTimeout(10000) { task.join() }
            assertIncoming(); assertFalse(prepared.recoveryRequired)
        }
    }

    @Test fun postCommitTaskAndCleanupFailuresNeverUndoAppliedData() = runBlocking {
        for (stage in listOf(NativeRestoreStage.DATABASE_COMMITTED, NativeRestoreStage.BEFORE_REMINDER_REBUILD,
            NativeRestoreStage.BEFORE_CLOUD_REBUILD, NativeRestoreStage.DERIVED_TASKS_REBUILT, NativeRestoreStage.BEFORE_STAGING_CLEANUP)) {
            reset()
            fixture { prepared, _ ->
                var reached = false
                service.restoreCheckpoint = { if (it == stage) { reached = true; throw java.io.IOException("synthetic derived failure") } }
                val result = service.restore(prepared)
                assertTrue("The selected stage must actually execute: $stage", reached)
                assertEquals(1, result.animeCount); assertIncoming(); assertFalse(prepared.recoveryRequired)
            }
        }
    }

    @Test fun failedRollbackPreservesItsResourceSnapshotAndPreventsBlindRetry() = runBlocking {
        fixture { prepared, old ->
            service.restoreCheckpoint = { if (it == NativeRestoreStage.APPEARANCE_WRITTEN || it == NativeRestoreStage.BEFORE_BRANDING_ROLLBACK)
                throw java.io.IOException("synthetic rollback failure") }
            var failure: NativeRestoreRollbackException? = null
            try { service.restore(prepared); fail("Expected incomplete rollback") }
            catch (error: NativeRestoreRollbackException) { failure = error }
            assertTrue(prepared.recoveryRequired); assertNotNull(failure)
            assertEquals(old.rows, database.libraryDao().getAllAnimesIncludingDeleted())
            assertEquals(1.07f, appearance.snapshot().fontScale, 0.001f)
            val saved = File(prepared.stagingDirectory, "_branding_before_restore/restore-test.txt")
            assertEquals("本机原资源", saved.readText())
            service.discard(prepared); assertTrue(saved.exists())
            try { service.restore(prepared); fail("Must not retry an incomplete rollback") }
            catch (error: IllegalStateException) { assertTrue(error.message!!.contains("暂不能直接重试")) }
            assertEquals("本机原资源", saved.readText())
        }
    }

    @Test fun mergeFailureKeepsLocalRowsAndTheirAbsoluteCoverReferences() = runBlocking {
        fixture { prepared, old ->
            service.restoreCheckpoint = { if (it == NativeRestoreStage.TRACKER_WRITTEN) throw java.io.IOException("synthetic merge failure") }
            expectFailure { service.restore(prepared, NativeRestoreSelection(), NativeRestoreStrategy.MERGE_KEEP_LOCAL) }
            assertOld(old)
        }
    }

    @Test fun libraryOnlyRestoreDoesNotReplaceAppearanceOrBranding() = runBlocking {
        fixture { prepared, old ->
            service.restoreCheckpoint = { check(it !in setOf(NativeRestoreStage.BRANDING_CLEARED, NativeRestoreStage.APPEARANCE_WRITTEN)) }
            service.restore(prepared, NativeRestoreSelection(true, false, false))
            assertEquals("备份作品", database.libraryDao().getAllAnimesIncludingDeleted().single().title)
            assertEquals(old.settings, settingsSnapshot().first)
            assertEquals("本机原资源", File(context.filesDir, "branding/restore-test.txt").readText())
        }
    }

    @Test fun cloudStyleSnapshotsKeepLocalFontsWhileFullLocalSnapshotsReplaceThem() = runBlocking {
        for (includeFonts in listOf(false, true)) {
            reset()
            fixture(includeFonts) { prepared, _ ->
                service.restore(prepared, NativeRestoreSelection(false, false, true))
                assertEquals("备份字体", File(context.filesDir, "branding/fonts/incoming.ttf").readText())
                assertEquals(!includeFonts, File(context.filesDir, "branding/fonts/local.ttf").exists())
            }
        }
    }

    private suspend fun expectFailure(action: suspend () -> Unit) {
        try { action(); fail("Expected synthetic failure") } catch (_: java.io.IOException) { }
    }
    private data class RestoreBefore(val rows: List<AnimeEntity>, val settings: String, val profiles: String)
    private suspend fun fixture(includeFonts: Boolean = false, run: suspend (PreparedNativeRestore, RestoreBefore) -> Unit) {
        val covers = File(context.filesDir, "covers").apply { mkdirs() }
        val incomingCover = File(covers, "incoming.png").apply { writeText("备份封面") }
        File(covers, "incoming-extra.png").writeText("备份封面二")
        val asset = File(context.filesDir, "branding/restore-test.txt").apply { parentFile!!.mkdirs(); writeText("备份资源") }
        val secondAsset = File(context.filesDir, "branding/second-test.txt").apply { writeText("备份资源二") }
        File(context.filesDir, "branding/fonts/incoming.ttf").apply { parentFile!!.mkdirs(); writeText("备份字体") }
        appearance.restore(appearance.snapshot().copy(fontScale = 1.23f))
        val id = database.libraryDao().insertAnime(AnimeEntity(title = "备份作品", coverUrl = incomingCover.toUri().toString(),
            watchedEpisodes = 2, rating = 7, review = "备份感想", bookReadVolumes = 0, bookReadChapters = 12))
        val zip = File(context.cacheDir, "restore-failure-${System.nanoTime()}.zip")
        service.export(zip, includeFonts = includeFonts, includeCovers = true)
        val prepared = service.prepareRestore(zip)
        val localCover = File(covers, "local.png").apply { writeText("本机封面") }
        database.libraryDao().updateAnime(database.libraryDao().getAnime(id)!!.copy(title = "本机作品", watchedEpisodes = 8,
            rating = 9, review = "本机感想", coverUrl = localCover.toUri().toString(), bookReadVolumes = 3, bookReadChapters = 45))
        appearance.restore(appearance.snapshot().copy(fontScale = 1.07f)); asset.writeText("本机原资源")
        secondAsset.writeText("本机原资源二")
        File(context.filesDir, "branding/fonts/local.ttf").writeText("本机字体")
        val settings = settingsSnapshot()
        val before = RestoreBefore(database.libraryDao().getAllAnimesIncludingDeleted(), settings.first, settings.second)
        try { run(prepared, before) }
        finally { service.restoreCheckpoint = {}; prepared.recoveryRequired = false; service.discard(prepared); zip.delete() }
    }
    private suspend fun assertOld(before: RestoreBefore) {
        assertEquals(before.rows, database.libraryDao().getAllAnimesIncludingDeleted())
        val settings = settingsSnapshot(); assertEquals(before.settings, settings.first); assertEquals(before.profiles, settings.second)
        assertEquals("本机原资源", File(context.filesDir, "branding/restore-test.txt").readText())
        assertEquals("本机原资源二", File(context.filesDir, "branding/second-test.txt").readText())
        assertEquals("本机字体", File(context.filesDir, "branding/fonts/local.ttf").readText())
        assertEquals("本机封面", File(context.filesDir, "covers/local.png").readText())
        assertFalse(File(context.filesDir, "covers").listFiles().orEmpty().any { it.isDirectory && it.name.startsWith("restored_") })
    }
    private suspend fun assertIncoming() {
        val row = database.libraryDao().getAllAnimesIncludingDeleted().single()
        assertEquals("备份作品", row.title); assertEquals(2, row.watchedEpisodes); assertEquals(7, row.rating)
        assertEquals(0, row.bookReadVolumes); assertEquals(12, row.bookReadChapters)
        assertEquals("备份封面", File(requireNotNull(row.coverUrl).toUri().path!!).readText())
        assertEquals(1.23f, appearance.snapshot().fontScale, 0.001f)
        assertEquals("备份资源", File(context.filesDir, "branding/restore-test.txt").readText())
    }
    private suspend fun settingsSnapshot(): Pair<String, String> {
        val file = File(context.cacheDir, "settings-check-${System.nanoTime()}.zip")
        try {
            service.export(file, includeFonts = false, includeCovers = false)
            val entries = mutableMapOf<String, String>()
            ZipInputStream(file.inputStream()).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break
                    if (entry.name in setOf("settings.json", "configuration_profiles.json")) entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            return entries.getValue("settings.json") to entries.getValue("configuration_profiles.json")
        } finally { file.delete() }
    }
    companion object {
        private val context: Context by lazy { RuntimeEnvironment.getApplication() }
        private val database by lazy { Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build() }
        private val service by lazy { NativeBackupService(context, database) }
        private val appearance by lazy { AppearancePreferences(context) }
        @JvmStatic @AfterClass fun closeDatabase() { database.close() }
    }
}
