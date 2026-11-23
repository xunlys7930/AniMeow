package com.animeow.app.data.backup

import android.content.Context
import androidx.core.net.toUri
import androidx.room.Room
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AnimeCharacterEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.CharacterRelationEntity
import com.animeow.app.data.local.CharacterEntity
import com.animeow.app.data.maintenance.OrphanCoverCleaner
import com.animeow.app.data.playback.PlaybackCheckpoint
import com.animeow.app.data.playback.PlaybackHistory
import com.animeow.app.data.playback.PlaybackIdentities
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 流式备份（2026-10-01）的行为契约：
 * - 往返恢复与旧实现等价；
 * - 旧版写入格式（org.json 排版 + manifest 在前）仍然可恢复；
 * - 未知列 / 坏行不拖垮恢复；
 * - summarizeCurrent 与导出的指纹一致；
 * - 孤儿封面清理不误删在用/受保护文件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class NativeBackupStreamingTest {
    @Test fun cloudStyleSnapshotRestoresPlaybackHistoryAndResumePosition() = runBlocking {
        val dao = fixture.database.libraryDao()
        val original = AnimeEntity(id = 651, title = "播放历史往返", createdAt = "2026-11-13T12:00:00Z")
        dao.insertAnime(original)
        val history = PlaybackHistory(fixture.context)
        val key = PlaybackIdentities(fixture.context).key(original)
        val latest = PlaybackCheckpoint("test", "work", "episode", 180_000, 1_440_000, listOf(0L..120_000L))
        history.describe(key, original, "第 2 集", 1000)
        history.save(key, latest)
        val checkpointPrefs = fixture.context.getSharedPreferences("playback_history_v1", Context.MODE_PRIVATE)
        val legacyCheckpoint = JSONObject(checkpointPrefs.getString(key, null)!!).apply { remove("savedAt") }
        checkpointPrefs.edit().putString(key, legacyCheckpoint.toString()).commit()
        val file = File(fixture.workDir, "cloud-playback.zip")
        assertEquals(1, history.backupRows(dao.getActiveAnimes().asSequence()).length())
        val summary = fixture.service.export(file, includeFonts = false, includeCovers = false)
        assertEquals("第 2 集", JSONObject(readZipText(file, "settings.json")).getJSONArray("playbackHistory").getJSONObject(0).optString("episode"))
        assertEquals(summary.dataFingerprint, fixture.service.summarizeCurrent(false, false).dataFingerprint)

        fixture.context.getSharedPreferences("playback_history_v1", Context.MODE_PRIVATE).edit().clear().commit()
        fixture.context.getSharedPreferences("playback_history_index_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val settingsOnly = fixture.service.prepareRestore(file)
        try {
            fixture.service.restore(settingsOnly, NativeRestoreSelection(false, false, true))
            assertTrue(history.entries().isEmpty())
        } finally { fixture.service.discard(settingsOnly) }
        dao.updateAnime(original.copy(watchedEpisodes = 4))
        val rejected = fixture.service.prepareRestore(file)
        fixture.service.restoreCheckpoint = { stage ->
            if (stage == NativeRestoreStage.PLAYBACK_HISTORY_WRITTEN) error("合成恢复失败")
        }
        try {
            assertEquals("合成恢复失败", runCatching {
                fixture.service.restore(rejected, NativeRestoreSelection(true, false, false))
            }.exceptionOrNull()?.message)
            assertTrue(history.entries().isEmpty())
            assertEquals(4, dao.getAnime(original.id)?.watchedEpisodes)
        } finally {
            fixture.service.restoreCheckpoint = {}
            fixture.service.discard(rejected)
        }
        val prepared = fixture.service.prepareRestore(file)
        assertEquals(1, prepared.playbackHistory?.length())
        try { fixture.service.restore(prepared, NativeRestoreSelection(true, false, false)) }
        finally { fixture.service.discard(prepared) }
        val restored = history.entries().single()
        assertEquals("第 2 集", restored.episode)
        assertEquals(original.id, restored.animeId)
        assertEquals(latest, restored.checkpoint)
        assertEquals(latest, history.readEpisode(restored.key, "test", "work", "episode"))
        assertEquals(summary.dataFingerprint, fixture.service.summarizeCurrent(false, false).dataFingerprint)
    }

    @Test fun playbackHistoryDuringRestoreMergeFollowsRemappedAnimeId() = runBlocking {
        val dao = fixture.database.libraryDao()
        val id = 652L
        val incoming = AnimeEntity(id = id, title = "远端作品", createdAt = "2026-11-13T12:00:00Z")
        dao.insertAnime(incoming)
        val history = PlaybackHistory(fixture.context)
        val identities = PlaybackIdentities(fixture.context)
        val incomingKey = identities.key(incoming)
        val saved = PlaybackCheckpoint("test", "work", "ep2", 52_000, 120_000)
        history.describe(incomingKey, incoming, "远端第 2 集")
        history.save(incomingKey, saved)
        val file = File(fixture.workDir, "cloud-remap.zip")
        fixture.service.export(file, includeFonts = false, includeCovers = false)
        assertEquals(1, JSONObject(readZipText(file, "settings.json")).getJSONArray("playbackHistory").length())
        history.remove(incomingKey)
        val local = incoming.copy(title = "本机其他作品", createdAt = "2026-11-13T12:01:00Z")
        dao.updateAnime(local)
        val localCheckpoint = PlaybackCheckpoint("test", "local", "ep1", 25_000, 100_000)
        history.describe(identities.key(local), local, "本机第 1 集")
        history.save(identities.key(local), localCheckpoint)
        val prepared = fixture.service.prepareRestore(file)
        try {
            fixture.service.restore(prepared, NativeRestoreSelection(true, false, false), NativeRestoreStrategy.MERGE_KEEP_LOCAL)
        } finally { fixture.service.discard(prepared) }
        val mergedWork = dao.getActiveAnimes().single { it.title == incoming.title }
        assertTrue(mergedWork.id != id)
        assertEquals(saved, history.read(identities.key(mergedWork)))
        assertEquals(localCheckpoint, history.read(identities.key(local)))
    }

    @Test fun bookVolumeAndChapterCountsRoundTripIndependentlyOfLegacyProgress() = runBlocking {
        val dao = fixture.database.libraryDao()
        val id = dao.insertAnime(AnimeEntity(title = "独立卷话书籍", subjectType = "book", watchedEpisodes = 55,
            bookReadVolumes = 0, bookReadChapters = 17))
        val file = File(fixture.workDir, "book-progress.zip")
        fixture.service.export(file, includeFonts = false, includeCovers = false)
        dao.updateAnime(dao.getAnime(id)!!.copy(bookReadVolumes = 8, bookReadChapters = null, watchedEpisodes = 2))
        val prepared = fixture.service.prepareRestore(file)
        try { fixture.service.restore(prepared, NativeRestoreSelection(true, false, false)) }
        finally { fixture.service.discard(prepared) }
        val restored = dao.getAllAnimesIncludingDeleted().single()
        assertEquals(0, restored.bookReadVolumes); assertEquals(17, restored.bookReadChapters)
        assertEquals(55, restored.watchedEpisodes)
    }

    @Test fun mixedSeriesAndRolesSurviveNativeReplaceRestore() = runBlocking {
        val dao = fixture.database.libraryDao()
        dao.insertSeriesItem(
            com.animeow.app.data.local.SeriesEntity(
                id = 91,
                name = "异名改编系列",
                linkedMediaJson = """[{"kind":"GAME","workSyncId":"vn-sync","role":"原作"},{"kind":"UNKNOWN","workSyncId":"bad"}]""",
            ),
        )
        dao.insertAnime(AnimeEntity(title = "原作甲", subjectType = "book", seriesId = 91, seriesRole = "原作", bookReadVolumes = 3, memo = "实体书在书柜第二层"))
        dao.insertAnime(AnimeEntity(title = "动画乙", seriesId = 91, seriesRole = "改编", watchedEpisodes = 6, review = "保留个人记录", memo = "   "))
        val file = File(fixture.workDir, "mixed-series.zip")
        fixture.service.export(file, includeFonts = false, includeCovers = false)
        val prepared = fixture.service.prepareRestore(file)
        try { fixture.service.restore(prepared, NativeRestoreSelection(true, false, false)) }
        finally { fixture.service.discard(prepared) }
        val rows = dao.getAllAnimesIncludingDeleted().associateBy { it.title }
        assertEquals("原作", rows.getValue("原作甲").seriesRole)
        assertEquals(3, rows.getValue("原作甲").bookReadVolumes)
        assertEquals("实体书在书柜第二层", rows.getValue("原作甲").memo)
        assertEquals("改编", rows.getValue("动画乙").seriesRole)
        assertEquals(6, rows.getValue("动画乙").watchedEpisodes)
        org.junit.Assert.assertNull(rows.getValue("动画乙").memo)
        assertEquals(rows.getValue("原作甲").seriesId, rows.getValue("动画乙").seriesId)
        assertEquals(
            listOf(com.animeow.app.data.local.SeriesLinkedMediaItem(kind = "GAME", workSyncId = "vn-sync", role = "原作")),
            com.animeow.app.data.local.parseSeriesLinkedMedia(dao.getSeries(91)!!.linkedMediaJson),
        )
    }

    @Test fun personalizationControlsSurviveSettingsOnlyRestore() = runBlocking {
        val preferences = com.animeow.app.data.preferences.TrackerPreferences(fixture.context)
        val original = preferences.snapshot()
        val settings = original.copy(hiddenHomeStatusIds = setOf(2L), hiddenActionStatusIds = setOf(3L),
            progressPlacement = com.animeow.app.data.preferences.CardProgressPlacement.COVER_BOTTOM,
            statusBadgeBackground = com.animeow.app.data.preferences.StatusBadgeBackground.CUSTOM,
            statusBadgeCustomColor = 0xFF123456, coverProgressAlpha = 0.82f).normalized()
        try {
            preferences.save(settings)
            val file = File(fixture.workDir, "personalization.zip")
            fixture.service.export(file, includeFonts = false, includeCovers = false)
            preferences.save(com.animeow.app.data.preferences.TrackerSettings())
            val prepared = fixture.service.prepareRestore(file)
            try { fixture.service.restore(prepared, NativeRestoreSelection(false, false, true)) }
            finally { fixture.service.discard(prepared) }
            assertEquals(settings, preferences.snapshot())
        } finally { preferences.save(original) }
    }

    @Test fun slowOutputReleasesDatabaseAndKeepsItsOwnResourceSnapshot() = runBlocking {
        val covers = File(fixture.context.filesDir, "covers").apply { mkdirs() }
        val cover = File(covers, "concurrent-cover.png").apply { writeText("captured image") }
        val id = fixture.database.libraryDao().insertAnime(AnimeEntity(title = "备份时的作品",
            watchedEpisodes = 1, totalEpisodes = 12, coverUrl = cover.toUri().toString()))
        val writing = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val bytes = java.io.ByteArrayOutputStream()
        val paused = java.util.concurrent.atomic.AtomicBoolean(false)
        val output = object : java.io.OutputStream() {
            private fun pauseOnce() {
                if (paused.compareAndSet(false, true)) {
                    writing.countDown()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Test output was not released" }
                }
            }
            override fun write(value: Int) { pauseOnce(); bytes.write(value) }
            override fun write(value: ByteArray, offset: Int, count: Int) { pauseOnce(); bytes.write(value, offset, count) }
        }
        val export = async(kotlinx.coroutines.Dispatchers.IO) {
            fixture.service.writeNativeBackup(output, includeFonts = false, includeCovers = true)
        }
        try {
            assertTrue("Export must reach its output", writing.await(10, java.util.concurrent.TimeUnit.SECONDS))
            kotlinx.coroutines.withTimeout(3_000) {
                com.animeow.app.data.LibraryRepository(fixture.database).setProgress(id, 2)
            }
            assertEquals(2, fixture.database.libraryDao().getAnime(id)?.watchedEpisodes)
            assertTrue(cover.delete())
        } finally { release.countDown() }
        val summary = export.await()
        val file = File(fixture.workDir, "concurrent.zip").apply { writeBytes(bytes.toByteArray()) }
        val anime = JSONObject(readZipText(file, "database.json")).getJSONArray("animes").getJSONObject(0)
        assertEquals(1, summary.animeCount); assertEquals(1, anime.getInt("watchedEpisodes"))
        assertEquals("captured image", readZipText(file, "covers/concurrent-cover.png"))
        assertFalse(fixture.context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("native_export_") })
    }

    private data class Fixture(
        val context: Context,
        val database: AniMeowDatabase,
        val service: NativeBackupService,
        val workDir: File,
    )

    private val fixture: Fixture by lazy {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val service = NativeBackupService(context, database)
        val workDir = File(context.cacheDir, "streaming_test_${System.nanoTime()}").apply { mkdirs() }
        Fixture(context, database, service, workDir)
    }

    @After
    fun closeFixture() {
        fixture.database.close()
        fixture.workDir.deleteRecursively()
    }

    @Before
    fun resetState() {
        listOf("playback_history_v1", "playback_history_index_v1", "playback_identities_v1").forEach {
            fixture.context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        File(fixture.context.filesDir, "covers").deleteRecursively()
        File(fixture.context.filesDir, "backup_passthrough").deleteRecursively()
    }

    @Test
    fun filterPlansAndHomeModulesRoundTripAndOldBackupsKeepExistingPlans() = runBlocking {
        val filters = com.animeow.app.data.preferences.CollectionFilterPreferences(fixture.context)
        val tracker = com.animeow.app.data.preferences.TrackerPreferences(fixture.context)
        filters.restore(org.json.JSONArray())
        val row = com.animeow.app.data.preferences.CollectionFilterPlan("portable", "想看的日常", "library",
            com.animeow.app.data.preferences.CollectionFilterConditions(status = "想看", tags = listOf("日常")))
        filters.save(row)
        tracker.save(com.animeow.app.data.preferences.TrackerSettings(showRecentPlayback = false, showHomeCalendar = true,
            homeModuleOrder = listOf(com.animeow.app.data.preferences.HomeModule.CALENDAR, com.animeow.app.data.preferences.HomeModule.PLAYBACK)))
        val backup = File(fixture.workDir, "filter-plans.zip")
        fixture.service.export(backup, includeCovers = false)
        val settings = JSONObject(readZipText(backup, "settings.json"))
        assertEquals("portable", settings.getJSONArray("collectionFilters").getJSONObject(0).getString("id"))
        assertTrue(settings.getJSONObject("tracker").getBoolean("showHomeCalendar"))
        assertFalse(settings.getJSONObject("tracker").getBoolean("showRecentPlayback"))
        filters.restore(org.json.JSONArray())
        tracker.save(com.animeow.app.data.preferences.TrackerSettings())
        val prepared = fixture.service.prepareRestore(backup)
        try { fixture.service.restore(prepared) } finally { fixture.service.discard(prepared) }
        assertEquals(filters.snapshot().toString(), com.animeow.app.data.preferences.collectionFilterPlansToJson(listOf(row)).toString())
        assertFalse(tracker.snapshot().showRecentPlayback)
        settings.remove("collectionFilters")
        val old = File(fixture.workDir, "old-filter-backup.zip")
        ZipOutputStream(old.outputStream()).use { zip ->
            for (name in listOf("manifest.json", "database.json", "configuration_profiles.json")) zip.writeTextEntry(name, readZipText(backup, name))
            zip.writeTextEntry("settings.json", settings.toString())
        }
        val oldPrepared = fixture.service.prepareRestore(old)
        try { fixture.service.restore(oldPrepared) } finally { fixture.service.discard(oldPrepared) }
        assertEquals(1, filters.snapshot().length())
    }

    @Test
    fun exportsAndVerifiesTheDesktopRelayFixture() = runBlocking {
        val db = fixture.database
        val rating = """{"source":"bangumi","id":"253","score":8.5,"count":200,"rank":1,"updatedAt":"2026-10-24T00:00:00Z"}"""
        db.libraryDao().insertAnime(AnimeEntity(title = "跨端合成动画", externalSource = "bangumi", externalId = "253", rating = 60, watchedEpisodes = 7, review = "个人感想", sourceRatingJson = rating))
        val game = com.animeow.app.data.games.GameEntity(syncId = "cross-game", title = "跨端合成游戏", vndbId = "v17", tags = "剧情，合成", playMinutes = 123, review = "游戏感想")
        db.gameDao().insert(game)
        db.audioWorkDao().insert(com.animeow.app.data.audio.AudioWork(syncId = "cross-audio", title = "合成译名", originalTitle = "合成原名", workCode = "RJ123456", listenedSeconds = 90, review = "音声感想"))
        val series = com.animeow.app.data.MediaSeriesRepository(db, com.animeow.app.data.MediaSeriesKind.GAME)
        series.save(null, com.animeow.app.data.mediaSeriesMembersVersion(emptyList()), listOf(com.animeow.app.data.MediaSeriesWork(game.syncId, game.title, game.updatedAt)), "跨端合成系列", listOf(game.syncId))
        val directory = File("../../build/feedback-finish-2026-10-24/cross-platform").apply { mkdirs() }
        val exported = File(directory, "android-original.zip")
        fixture.service.export(exported, includeCovers = false)
        val desktop = File(directory, "windows-relay.zip")
        val input = if (System.getenv("ANIMEOW_VERIFY_DESKTOP_RELAY") == "1") desktop.also { assertTrue("Windows relay fixture must exist", it.isFile) } else exported
        resetState()
        val prepared = fixture.service.prepareRestore(input)
        try { fixture.service.restore(prepared) } finally { fixture.service.discard(prepared) }
        val anime = db.libraryDao().getActiveAnimes().single()
        assertEquals(rating, anime.sourceRatingJson); assertEquals(60, anime.rating); assertEquals(7, anime.watchedEpisodes); assertEquals("个人感想", anime.review)
        val sqlite = db.openHelper.readableDatabase
        sqlite.query("SELECT tags,playMinutes,review FROM games").use { row -> assertTrue(row.moveToFirst()); assertEquals("剧情，合成", row.getString(0)); assertEquals(123, row.getInt(1)); assertEquals("游戏感想", row.getString(2)) }
        sqlite.query("SELECT originalTitle,listenedSeconds,review FROM audio_works").use { row -> assertTrue(row.moveToFirst()); assertEquals("合成原名", row.getString(0)); assertEquals(90, row.getInt(1)); assertEquals("音声感想", row.getString(2)) }
        assertEquals("跨端合成系列", db.mediaSeriesDao().series("GAME").single().name)
        assertEquals("cross-game", db.mediaSeriesDao().members("GAME").single().workSyncId)
    }

    @Test
    fun publicRatingSnapshotSurvivesZipAndOlderMergeWithoutChangingPersonalData() = runBlocking {
        val stats = """{"source":"bangumi","id":"253","score":8.5,"count":200,"rank":1,"updatedAt":"2026-10-24T00:00:00Z"}"""
        val id = fixture.database.libraryDao().insertAnime(AnimeEntity(title = "作品", rating = 60, review = "个人感想", watchedEpisodes = 7, externalSource = "bangumi", externalId = "253", sourceRatingJson = stats))
        val backup = File(fixture.workDir, "source-rating.zip")
        fixture.service.export(backup, includeCovers = false)
        val json = JSONObject(readZipText(backup, "database.json"))
        assertEquals(stats, json.getJSONArray("animes").getJSONObject(0).getString("sourceRatingJson"))
        resetState()
        val prepared = fixture.service.prepareRestore(backup)
        try { fixture.service.restore(prepared) } finally { fixture.service.discard(prepared) }
        val saved = fixture.database.libraryDao().getAnime(id)!!
        assertEquals(stats, saved.sourceRatingJson); assertEquals(60, saved.rating); assertEquals(7, saved.watchedEpisodes)
        val old = JSONObject(json.toString()).apply { getJSONArray("animes").getJSONObject(0).remove("sourceRatingJson") }
        val merged = NativeBackupMerger.merge(json, old, includeCore = true, includeAnalysis = false)
        assertEquals(stats, merged.getJSONArray("animes").getJSONObject(0).getString("sourceRatingJson"))
    }

    @Test
    fun seriesRelationsRoundTripAndOldSnapshotsKeepExistingSeries() = runBlocking {
        val work = com.animeow.app.data.games.GameEntity(title = "系列游戏", playMinutes = 123)
        fixture.database.gameDao().insert(work)
        val repo = com.animeow.app.data.MediaSeriesRepository(fixture.database, com.animeow.app.data.MediaSeriesKind.GAME)
        repo.save(null, com.animeow.app.data.mediaSeriesMembersVersion(emptyList()), listOf(com.animeow.app.data.MediaSeriesWork(work.syncId, work.title, work.updatedAt)), "游戏系列", listOf(work.syncId))
        val backup = File(fixture.workDir, "series.zip")
        fixture.service.export(backup)
        val json = JSONObject(readZipText(backup, "database.json"))
        assertEquals(work.syncId, json.getJSONArray("media_series_members").getJSONObject(0).getString("workSyncId"))
        resetState()
        val prepared = fixture.service.prepareRestore(backup)
        try { fixture.service.restore(prepared) } finally { fixture.service.discard(prepared) }
        val dao = fixture.database.mediaSeriesDao()
        val series = dao.series("GAME").single(); val members = dao.members("GAME")
        assertEquals(series.id, members.single().seriesId)
        assertEquals(123L, fixture.database.gameDao().all().single().playMinutes)
        json.remove("media_series"); json.remove("media_series_members")
        val old = File(fixture.workDir, "pre-series.zip")
        ZipOutputStream(old.outputStream()).use { zip ->
            zip.writeTextEntry("manifest.json", readZipText(backup, "manifest.json"))
            zip.writeTextEntry("database.json", json.toString())
            listOf("settings.json", "configuration_profiles.json").forEach { zip.writeTextEntry(it, readZipText(backup, it)) }
        }
        val oldPrepared = fixture.service.prepareRestore(old)
        try { fixture.service.restore(oldPrepared) } finally { fixture.service.discard(oldPrepared) }
        assertEquals(series, dao.series("GAME").single()); assertEquals(members, dao.members("GAME"))
        for (complete in listOf(false, true)) {
            json.put("media_series", org.json.JSONArray())
            if (complete) json.put("media_series_members", org.json.JSONArray())
            ZipOutputStream(old.outputStream()).use { zip ->
                zip.writeTextEntry("manifest.json", readZipText(backup, "manifest.json"))
                zip.writeTextEntry("database.json", json.toString())
                listOf("settings.json", "configuration_profiles.json").forEach { zip.writeTextEntry(it, readZipText(backup, it)) }
            }
            val updated = fixture.service.prepareRestore(old)
            try { fixture.service.restore(updated) } finally { fixture.service.discard(updated) }
            if (complete) { assertTrue(dao.series("GAME").isEmpty()); assertTrue(dao.members("GAME").isEmpty()) }
            else { assertEquals(series, dao.series("GAME").single()); assertEquals(members, dao.members("GAME")) }
        }
        assertEquals(123L, fixture.database.gameDao().all().single().playMinutes)
    }

    @Test
    fun restoringPreGameBackupKeepsExistingMobileRecordsAndTheirRestoredCovers() = runBlocking {
        val directory = File(fixture.context.filesDir, "covers/restored_mobile_kept").apply { mkdirs() }
        val cover = File(directory, "game.png").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val id = fixture.database.gameDao().insert(com.animeow.app.data.games.GameEntity(title = "保留游戏", coverUrl = cover.toUri().toString(), tags = "保留"))
        val backup = File(fixture.workDir, "before-mobile.zip")
        fixture.service.export(backup, includeCovers = false)
        val json = JSONObject(readZipText(backup, "database.json")).apply { remove("games"); remove("audio_works") }
        val old = File(fixture.workDir, "before-mobile-modules.zip")
        ZipOutputStream(old.outputStream()).use { zip ->
            zip.writeTextEntry("manifest.json", readZipText(backup, "manifest.json"))
            zip.writeTextEntry("database.json", json.toString())
            listOf("settings.json", "configuration_profiles.json").forEach { zip.writeTextEntry(it, readZipText(backup, it)) }
        }
        val prepared = fixture.service.prepareRestore(old)
        try { fixture.service.restore(prepared) } finally { fixture.service.discard(prepared) }
        assertEquals(cover.toUri().toString(), fixture.database.gameDao().get(id)!!.coverUrl)
        assertTrue(cover.isFile)
        assertEquals("保留", fixture.database.gameDao().get(id)!!.tags)
    }

    @Test
    fun gameTagsAndAudioOriginalTitleSurviveBackupsAndOlderSnapshotMerges() = runBlocking {
        val gameId = fixture.database.gameDao().insert(com.animeow.app.data.games.GameEntity(
            title = "游戏", tags = "剧情，治愈", playMinutes = 720, review = "游戏心得"))
        val audioId = fixture.database.audioWorkDao().insert(com.animeow.app.data.audio.AudioWork(
            title = "自定义译名", originalTitle = "原站名称", listenedSeconds = 123, review = "音声心得"))
        val backup = File(fixture.workDir, "mobile-fields.zip")
        fixture.service.export(backup)
        val snapshot = JSONObject(readZipText(backup, "database.json"))
        val old = JSONObject(snapshot.toString())
        old.getJSONArray("games").getJSONObject(0).apply { remove("tags"); put("updatedAt", "2099-01-01T00:00:00Z") }
        old.getJSONArray("audio_works").getJSONObject(0).apply { remove("originalTitle"); put("updatedAt", "2099-01-01T00:00:00Z") }
        val merged = NativeBackupMerger.merge(snapshot, old, true, false)
        assertEquals("剧情，治愈", merged.getJSONArray("games").getJSONObject(0).getString("tags"))
        assertEquals("原站名称", merged.getJSONArray("audio_works").getJSONObject(0).getString("originalTitle"))
        resetState()
        val prepared = fixture.service.prepareRestore(backup)
        try { fixture.service.restore(prepared) } finally { fixture.service.discard(prepared) }
        assertEquals("剧情，治愈", fixture.database.gameDao().get(gameId)!!.tags)
        assertEquals("原站名称", fixture.database.audioWorkDao().get(audioId)!!.originalTitle)
        assertEquals(720L, fixture.database.gameDao().get(gameId)!!.playMinutes)
        assertEquals(123L, fixture.database.audioWorkDao().get(audioId)!!.listenedSeconds)
    }

    @Test
    fun characterImagesAreProtectedBackedUpAndExcludedFromPublicPackages() = runBlocking {
        val root = File(fixture.context.filesDir, "covers/characters").apply { mkdirs() }
        val image = File(root, "character-backup.png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4)); setLastModified(System.currentTimeMillis() - 60 * 60 * 1000)
        }
        val dao = fixture.database.libraryDao()
        val repository = com.animeow.app.data.CharacterRepository(fixture.database)
        val id = repository.saveManualCharacter(CharacterEntity(name = "本地图片角色", imageUrl = image.toUri().toString(), rating = 9, review = "保留评价"))
        val groupId = repository.saveGroup(com.animeow.app.data.local.CharacterGroupEntity(name = "图片组"), listOf(id), emptyList())
        OrphanCoverCleaner(fixture.context, fixture.database).cleanup()
        assertTrue(image.exists())
        val shared = repository.exportGroupPackage(groupId)
        assertFalse(shared.contains("file:"))
        assertFalse(shared.contains("character-backup.png"))
        val withImage = File(fixture.workDir, "character-with-image.zip")
        val withoutImage = File(fixture.workDir, "character-without-image.zip")
        fixture.service.export(withImage, includeCovers = true)
        fixture.service.export(withoutImage, includeCovers = false)
        assertTrue(readZipText(withImage, "database.json").contains("animeow-cover://characters/character-backup.png"))
        image.delete()
        resetState()
        val prepared = fixture.service.prepareRestore(withImage)
        try { fixture.service.restore(prepared) } finally { fixture.service.discard(prepared) }
        val restored = dao.getCharacter(id)!!
        val restoredFile = File(requireNotNull(android.net.Uri.parse(restored.imageUrl).path))
        assertTrue(restoredFile.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertEquals(9, restored.rating)
        assertEquals("保留评价", restored.review)
        resetState()
        val noImages = fixture.service.prepareRestore(withoutImage)
        try { fixture.service.restore(noImages) } finally { fixture.service.discard(noImages) }
        assertEquals("animeow-cover://characters/character-backup.png", dao.getCharacter(id)!!.imageUrl)
    }

    @Test
    fun customCoverSurvivesNoCoverCloudRestoreAndRepeatedRoundtripsWithoutNesting() = runBlocking {
        val nativeDir = File(fixture.context.filesDir, "covers/native").apply { mkdirs() }
        val customCover = File(nativeDir, "custom_sync_cover.jpg").apply {
            writeBytes(byteArrayOf(11, 22, 33, 44))
        }
        val dao = fixture.database.libraryDao()
        val animeId = dao.insertAnime(
            AnimeEntity(title = "自定义封面作品", coverUrl = customCover.toUri().toString()),
        )

        // 1. 仅打包引用封面的导出（云同步口径）必须包含该封面并保持规范路径
        val cloudWithCovers = File(fixture.workDir, "cloud-with-covers.zip")
        val summaryWithCovers = fixture.service.export(
            cloudWithCovers,
            includeFonts = false,
            includeCovers = true,
            onlyReferencedCovers = true,
        )
        assertEquals(1, summaryWithCovers.coverCount)
        assertTrue(readZipText(cloudWithCovers, "database.json").contains("animeow-cover://native/custom_sync_cover.jpg"))

        // 2. 模拟不含封面的旧云端备份恢复：本机磁盘上仍有原封面文件，恢复后不应退化成无法加载的 marker
        val cloudWithoutCovers = File(fixture.workDir, "cloud-no-covers.zip")
        fixture.service.export(cloudWithoutCovers, includeFonts = false, includeCovers = false)
        val prepNoCover = fixture.service.prepareRestore(cloudWithoutCovers)
        try { fixture.service.restore(prepNoCover) } finally { fixture.service.discard(prepNoCover) }
        val afterNoCoverUrl = dao.getAnime(animeId)!!.coverUrl!!
        assertTrue("恢复后应直接指向本机现存封面文件，而不是裸 marker：$afterNoCoverUrl", afterNoCoverUrl.startsWith("file:"))
        assertTrue(File(requireNotNull(android.net.Uri.parse(afterNoCoverUrl).path)).isFile)

        // 3. 连续两次含封面恢复后再导出，不得出现 restored_*/restored_* 嵌套路径
        val prep1 = fixture.service.prepareRestore(cloudWithCovers)
        try { fixture.service.restore(prep1) } finally { fixture.service.discard(prep1) }
        val reExport = File(fixture.workDir, "cloud-reexport.zip")
        fixture.service.export(reExport, includeFonts = false, includeCovers = true, onlyReferencedCovers = true)
        val reExportDb = readZipText(reExport, "database.json")
        assertTrue(reExportDb.contains("animeow-cover://native/custom_sync_cover.jpg"))
        assertFalse(reExportDb.contains("animeow-cover://restored_"))
    }

    @Test
    fun batchTagAssociationsAndManualOrderSurviveBackupRestore() = runBlocking {
        val dao = fixture.database.libraryDao()
        val repository = com.animeow.app.data.LibraryRepository(fixture.database)
        val works = (1..3).map { dao.insertAnime(AnimeEntity(title = "批量作品$it", watchedEpisodes = it)) }.toSet()
        repository.updateTagsForAnimes(works, emptySet(), false, listOf(
            com.animeow.app.data.BatchTagDraft("保留标签", 1),
            com.animeow.app.data.BatchTagDraft("移除标签", 2),
            com.animeow.app.data.BatchTagDraft("额外标签", 3),
        ))
        val ids = dao.getTags().associate { it.name to it.id }
        repository.updateTagsForAnimes(works, setOf(ids.getValue("移除标签")), remove = true)
        val order = listOf(ids.getValue("额外标签"), ids.getValue("移除标签"), ids.getValue("保留标签"))
        repository.reorderTags(order)
        val backup = File(fixture.workDir, "batch_tags.zip")
        fixture.service.export(backup)
        resetState()
        val prepared = fixture.service.prepareRestore(backup)
        try { fixture.service.restore(prepared) } finally { fixture.service.discard(prepared) }
        assertEquals(order, dao.getTags().map { it.id })
        works.forEach { assertEquals(setOf(ids.getValue("保留标签"), ids.getValue("额外标签")), dao.getTagIdsForAnime(it).toSet()) }
        assertEquals(listOf(1, 2, 3), works.map { dao.getAnime(it)?.watchedEpisodes })
    }

    @Test
    fun tagOrderRoundTripAndOldBackupKeepsTagsWithoutOrder() = runBlocking {
        val dao = fixture.database.libraryDao()
        val animeId = dao.insertAnime(AnimeEntity(title = "移动作品", statusChangedAt = 123456789))
        dao.insertTags(listOf(
            com.animeow.app.data.local.TagEntity(id = 1, name = "Beta", sortOrder = 0),
            com.animeow.app.data.local.TagEntity(id = 2, name = "Alpha", sortOrder = 1),
        ))
        val backup = File(fixture.workDir, "tag_order.zip")
        fixture.service.export(backup)
        resetState()
        val prepared = fixture.service.prepareRestore(backup)
        try { fixture.service.restore(prepared) } finally { fixture.service.discard(prepared) }
        assertEquals(listOf(1L, 2L), dao.getTags().map { it.id })
        assertEquals(123456789L, dao.getAnime(animeId)?.statusChangedAt)
        val oldBackup = File(fixture.workDir, "old_tag_order.zip")
        ZipOutputStream(oldBackup.outputStream().buffered()).use { zip ->
            val database = readZipJson(backup, "database.json")
            val tags = database.getJSONArray("tags")
            for (index in 0 until tags.length()) tags.getJSONObject(index).remove("sortOrder")
            val animes = database.getJSONArray("animes")
            for (index in 0 until animes.length()) animes.getJSONObject(index).remove("statusChangedAt")
            zip.writeTextEntry("manifest.json", readZipText(backup, "manifest.json"))
            zip.writeTextEntry("database.json", database.toString())
            listOf("settings.json", "configuration_profiles.json").forEach { entry ->
                zip.writeTextEntry(entry, readZipText(backup, entry))
            }
        }
        resetState()
        val oldPrepared = fixture.service.prepareRestore(oldBackup)
        try { fixture.service.restore(oldPrepared) } finally { fixture.service.discard(oldPrepared) }
        assertEquals(listOf(2L, 1L), dao.getTags().map { it.id })
        assertTrue(dao.getTags().all { it.sortOrder == null })
        assertEquals(null, dao.getAnime(animeId)?.statusChangedAt)
    }

    @Test
    fun explicitPublicRouteSurvivesNativeBackupRestore() = runBlocking {
        val preferences = com.animeow.app.data.preferences.DiscoveryPreferences(fixture.context)
        val original = preferences.snapshot()
        val direct = com.animeow.app.data.preferences.PublicDataRoute.DIRECT
        try {
            preferences.save(original.copy(publicDataRoute = direct))
            val backup = File(fixture.workDir, "public_route.zip")
            fixture.service.export(backup)
            preferences.save(original)
            val prepared = fixture.service.prepareRestore(backup)
            try { fixture.service.restore(prepared) }
            finally { fixture.service.discard(prepared) }
            assertEquals(direct, preferences.snapshot().publicDataRoute)
        } finally { preferences.save(original) }
    }

    @Test
    fun characterRolesAndImageModeRoundTripWithoutTurningBlankIntoLegacyType() = runBlocking {
        val dao = fixture.database.libraryDao()
        val parent = dao.insertCharacter(CharacterEntity(name = "母亲", imageDisplayMode = "fit"))
        val child = dao.insertCharacter(CharacterEntity(name = "女儿", imageDisplayMode = "crop"))
        dao.insertCharacterRelation(CharacterRelationEntity(
            sourceCharacterId = parent, targetCharacterId = child, relationType = "母女",
            sourceRole = "母亲", targetRole = "",
        ))
        val backup = File(fixture.workDir, "character_roles.zip")
        fixture.service.export(backup)
        // Windows still writes native format v10. The schema columns and fingerprint are shared.
        val desktopBackup = File(fixture.workDir, "desktop_character_roles_v10.zip")
        ZipOutputStream(desktopBackup.outputStream().buffered()).use { zip ->
            zip.writeTextEntry("manifest.json", readZipJson(backup, "manifest.json").put("version", 10).toString())
            listOf("database.json", "settings.json", "configuration_profiles.json").forEach { entry ->
                zip.writeTextEntry(entry, readZipText(backup, entry))
            }
        }
        resetState()
        val prepared = fixture.service.prepareRestore(desktopBackup)
        try {
            fixture.service.restore(prepared, NativeRestoreSelection(), NativeRestoreStrategy.REPLACE)
            assertEquals("fit", dao.getCharacter(parent)?.imageDisplayMode)
            assertEquals("crop", dao.getCharacter(child)?.imageDisplayMode)
            assertEquals("母亲", dao.getCharacterRelation(parent, child)?.roleOf(parent))
            assertEquals("", dao.getCharacterRelation(parent, child)?.roleOf(child))
        } finally { fixture.service.discard(prepared) }
    }

    @Test
    fun gameBackupRoundTripAndOlderSnapshotsCannotEraseGames() = runBlocking {
        val db = fixture.database
        val game = com.animeow.app.data.games.GameEntity(title = "游戏", bangumiId = 284100, playMinutes = 125,
            selectedPlatform = "PC", review = "记录", status = "已玩")
        val id = db.gameDao().insert(game)
        val backup = File(fixture.workDir, "games.zip")
        assertEquals(1, fixture.service.export(backup).gameCount)
        db.openHelper.writableDatabase.execSQL("DELETE FROM games")
        var prepared = fixture.service.prepareRestore(backup)
        try { fixture.service.restore(prepared, NativeRestoreSelection(), NativeRestoreStrategy.REPLACE) }
        finally { fixture.service.discard(prepared) }
        assertEquals(125L, db.gameDao().get(id)?.playMinutes)
        assertEquals("记录", db.gameDao().get(id)?.review)
        val legacy = File(fixture.workDir, "before_games.zip")
        val data = readZipJson(backup, "database.json").apply { remove("games") }.toString()
        val settings = readZipText(backup, "settings.json")
        val profiles = readZipText(backup, "configuration_profiles.json")
        ZipOutputStream(legacy.outputStream()).use { zip ->
            zip.writeTextEntry("database.json", data)
            zip.writeTextEntry("settings.json", settings)
            zip.writeTextEntry("configuration_profiles.json", profiles)
            zip.writeTextEntry("manifest.json", readZipJson(backup, "manifest.json")
                .put("dataFingerprint", legacyFingerprintText(data, settings, profiles)).toString())
        }
        prepared = fixture.service.prepareRestore(legacy)
        try { fixture.service.restore(prepared, NativeRestoreSelection(), NativeRestoreStrategy.REPLACE) }
        finally { fixture.service.discard(prepared) }
        assertEquals("游戏", db.gameDao().get(id)?.title)
        assertEquals(125L, db.gameDao().get(id)?.playMinutes)
    }

    @Test
    fun audioBackupRoundTripAndOlderSnapshotsCannotEraseAudio() = runBlocking {
        val db = fixture.database
        val id = db.audioWorkDao().insert(com.animeow.app.data.audio.AudioWork(title = "音声", voiceActors = "声优", circle = "社团",
            workCode = "RJ123", durationSeconds = 3600, listenedSeconds = 1800, review = "保留感想", tags = "睡眠", status = "在听"))
        com.animeow.app.data.audio.AudioWorkRepository(db).recordProgress(requireNotNull(db.audioWorkDao().get(id)), false)
        val backup = File(fixture.workDir, "audio.zip")
        assertEquals(1, fixture.service.export(backup).audioCount)
        db.openHelper.writableDatabase.execSQL("DELETE FROM audio_works")
        var prepared = fixture.service.prepareRestore(backup)
        assertEquals(1, prepared.summary.audioCount)
        try { fixture.service.restore(prepared, NativeRestoreSelection(), NativeRestoreStrategy.REPLACE) }
        finally { fixture.service.discard(prepared) }
        val restored = requireNotNull(db.audioWorkDao().get(id))
        assertEquals(2100L, restored.listenedSeconds); assertEquals("在听", restored.status); assertEquals("保留感想", restored.review)
        assertEquals("RJ123", restored.workCode); assertEquals("声优", restored.voiceActors)
        val legacy = File(fixture.workDir, "before_audio.zip")
        val data = readZipJson(backup, "database.json").apply { remove("audio_works") }.toString()
        val settings = readZipText(backup, "settings.json")
        val profiles = readZipText(backup, "configuration_profiles.json")
        ZipOutputStream(legacy.outputStream()).use { zip ->
            zip.writeTextEntry("database.json", data)
            zip.writeTextEntry("settings.json", settings)
            zip.writeTextEntry("configuration_profiles.json", profiles)
            zip.writeTextEntry("manifest.json", readZipJson(backup, "manifest.json")
                .put("dataFingerprint", legacyFingerprintText(data, settings, profiles)).toString())
        }
        prepared = fixture.service.prepareRestore(legacy)
        try { fixture.service.restore(prepared, NativeRestoreSelection(), NativeRestoreStrategy.REPLACE) }
        finally { fixture.service.discard(prepared) }
        assertEquals(restored, db.audioWorkDao().get(id))
    }

    @Test
    fun roundTripReplaceRestoresRowsStreaming() = runBlocking {
        val database = fixture.database
        val service = fixture.service
        database.libraryDao().insertAnimes(
            listOf(
                AnimeEntity(
                    title = "作品一",
                    subjectType = "anime",
                    externalSource = "bangumi",
                    externalId = "101",
                    status = "在看",
                    voiceActors = "声优甲、声优乙",
                    coverOrientation = "landscape",
                    synopsis = "简介\"引号\"与\n换行与\\反斜杠",
                ),
                AnimeEntity(title = "作品二", subjectType = "novel", externalSource = "bangumi", externalId = "102"),
            ),
        )
        val animeId = database.libraryDao().getActiveAnimes().first { it.externalId == "101" }.id
        val characterId = database.libraryDao().insertCharacter(CharacterEntity(name = "角色"))
        database.libraryDao().insertAnimeCharacter(AnimeCharacterEntity(animeId, characterId, voiceActor = "声优甲"))
        val backup = File(fixture.workDir, "roundtrip.animeow.zip")
        val summary = service.export(backup)

        assertEquals(2, summary.animeCount)
        assertEquals(
            listOf("database.json", "settings.json", "configuration_profiles.json", "manifest.json"),
            zipEntryNames(backup),
        )
        val manifest = readZipJson(backup, "manifest.json")
        assertEquals("animeow-native", manifest.getString("type"))
        assertEquals(11, manifest.getInt("version"))
        assertEquals(legacyFingerprint(backup), manifest.getString("dataFingerprint"))

        resetState()
        val prepared = service.prepareRestore(backup)
        val restored = service.restore(prepared, NativeRestoreSelection(), NativeRestoreStrategy.REPLACE)
        service.discard(prepared)

        assertEquals(2, restored.animeCount)
        val animes = database.libraryDao().getActiveAnimes().sortedBy { it.title }
        assertEquals(listOf("作品一", "作品二"), animes.map { it.title })
        assertEquals("声优甲、声优乙", animes.first { it.externalId == "101" }.voiceActors)
        assertEquals("landscape", animes.first { it.externalId == "101" }.coverOrientation)
        assertEquals("声优甲", database.libraryDao().getAnimeCharacter(animeId, characterId)?.voiceActor)
        assertEquals("简介\"引号\"与\n换行与\\反斜杠", animes.first { it.externalId == "101" }.synopsis)
    }

    @Test
    fun oldWriterFormattingStillRestorable() = runBlocking {
        val database = fixture.database
        val service = fixture.service
        database.libraryDao().insertAnimes(listOf(AnimeEntity(title = "旧版备份", subjectType = "anime")))
        val modern = File(fixture.workDir, "modern.zip")
        service.export(modern)

        // 模拟旧实现产出的备份：manifest 在前、database.json 经 org.json 重序列化（键序/空白不同）、
        // 指纹按旧口径对重排后的原文重算。
        val databaseText = JSONObject(readZipText(modern, "database.json")).toString()
        val settingsText = readZipText(modern, "settings.json")
        val profilesText = readZipText(modern, "configuration_profiles.json")
        val manifest = readZipJson(modern, "manifest.json")
            .put("dataFingerprint", legacyFingerprintText(databaseText, settingsText, profilesText))
        val oldStyle = File(fixture.workDir, "old_style.zip")
        ZipOutputStream(oldStyle.outputStream().buffered()).use { zip ->
            zip.writeTextEntry("manifest.json", manifest.toString())
            zip.writeTextEntry("database.json", databaseText)
            zip.writeTextEntry("settings.json", settingsText)
            zip.writeTextEntry("configuration_profiles.json", profilesText)
        }

        val prepared = service.prepareRestore(oldStyle)
        assertEquals(1, prepared.summary.animeCount)
        val restored = service.restore(prepared, NativeRestoreSelection(), NativeRestoreStrategy.REPLACE)
        service.discard(prepared)

        assertEquals(1, restored.animeCount)
        assertEquals(listOf("旧版备份"), fixture.database.libraryDao().getActiveAnimes().map { it.title })
    }

    @Test
    fun unknownColumnsAndBrokenRowsDoNotAbortRestore() = runBlocking {
        val database = fixture.database
        val service = fixture.service
        database.libraryDao().insertAnimes(listOf(AnimeEntity(title = "现役行", subjectType = "anime")))
        val modern = File(fixture.workDir, "inject.zip")
        service.export(modern)

        val databaseJson = JSONObject(readZipText(modern, "database.json"))
        val animes = databaseJson.getJSONArray("animes")
        animes.put(JSONObject(animes.getJSONObject(0).toString()).put("id", 999).put("title", "未来行").put("brandNewColumn", "未来字段"))
        animes.put("这行不是对象")
        val databaseText = databaseJson.toString()
        val settingsText = readZipText(modern, "settings.json")
        val profilesText = readZipText(modern, "configuration_profiles.json")
        val manifest = readZipJson(modern, "manifest.json")
            .put("dataFingerprint", legacyFingerprintText(databaseText, settingsText, profilesText))
        val injected = File(fixture.workDir, "injected.zip")
        ZipOutputStream(injected.outputStream().buffered()).use { zip ->
            zip.writeTextEntry("manifest.json", manifest.toString())
            zip.writeTextEntry("database.json", databaseText)
            zip.writeTextEntry("settings.json", settingsText)
            zip.writeTextEntry("configuration_profiles.json", profilesText)
        }

        val prepared = service.prepareRestore(injected)
        assertEquals(2, prepared.summary.animeCount)
        val restored = service.restore(prepared, NativeRestoreSelection(), NativeRestoreStrategy.REPLACE)
        service.discard(prepared)

        assertEquals(2, restored.animeCount)
        val titles = database.libraryDao().getActiveAnimes().map { it.title }.sorted()
        assertEquals(listOf("未来行", "现役行"), titles)
    }

    @Test
    fun mergeStrategyStillMergesBothSides() = runBlocking {
        val database = fixture.database
        val service = fixture.service
        database.libraryDao().insertAnimes(
            listOf(AnimeEntity(title = "本地侧", subjectType = "anime", externalSource = "bangumi", externalId = "201")),
        )
        val backup = File(fixture.workDir, "remote.zip")
        resetState()
        database.libraryDao().insertAnimes(
            listOf(AnimeEntity(title = "远端侧", subjectType = "anime", externalSource = "bangumi", externalId = "202")),
        )
        service.export(backup)

        resetState()
        database.libraryDao().insertAnimes(
            listOf(AnimeEntity(title = "本地侧", subjectType = "anime", externalSource = "bangumi", externalId = "201")),
        )
        val prepared = service.prepareRestore(backup)
        service.restore(prepared, NativeRestoreSelection(), NativeRestoreStrategy.MERGE_KEEP_LOCAL)
        service.discard(prepared)

        val titles = database.libraryDao().getActiveAnimes().map { it.title }.sorted()
        assertEquals(listOf("本地侧", "远端侧"), titles)
    }

    @Test
    fun summarizeMatchesExportedFingerprint() = runBlocking {
        fixture.database.libraryDao().insertAnimes(
            listOf(AnimeEntity(title = "云端校验", subjectType = "anime")),
        )
        val summarized = fixture.service.summarizeCurrent(includeFonts = false, includeCovers = false)
        val summarizedAgain = fixture.service.summarizeCurrent(includeFonts = false, includeCovers = false)
        val cloudFile = File(fixture.workDir, "cloud.zip")
        val exported = fixture.service.export(
            cloudFile,
            includeFonts = false,
            includeCovers = false,
        )
        assertEquals(summarized.dataFingerprint, exported.dataFingerprint)
        assertEquals(summarized.animeCount, exported.animeCount)
    }

    @Test
    fun orphanCoverCleanupKeepsReferencedAndProtectedFiles() = runBlocking {
        val context = fixture.context
        val database = fixture.database
        val coversRoot = File(context.filesDir, "covers").apply { mkdirs() }
        val keep = File(coversRoot, "native/keep.jpg").apply { parentFile!!.mkdirs(); writeText("keep") }
        val orphan = File(coversRoot, "native/orphan.jpg").apply { parentFile!!.mkdirs(); writeText("orphan") }
        val insideRestored = File(File(coversRoot, "restored_123"), "x.jpg").apply {
            parentFile!!.mkdirs()
            writeText("x")
        }
        val fresh = File(coversRoot, "native/fresh.jpg").apply { parentFile!!.mkdirs(); writeText("fresh") }
        val oldStamp = System.currentTimeMillis() - 60L * 60 * 1000
        // fresh 保持当前 mtime：落在保护期内，验证 15 分钟年龄护栏
        listOf(keep, orphan, insideRestored).forEach { it.setLastModified(oldStamp) }

        database.libraryDao().insertAnimes(
            listOf(AnimeEntity(title = "有封面", coverUrl = keep.toUri().toString())),
        )

        val report = OrphanCoverCleaner(context, database).cleanup()

        assertTrue(keep.exists())
        assertFalse(orphan.exists())
        assertTrue(insideRestored.exists())
        assertTrue(fresh.exists())
        assertEquals(1, report.deletedFiles)
    }

    @Test
    fun desktopOnlySettingsBackupDoesNotResetMobileAppearanceAndPreservesDesktopSettings() = runBlocking {
        val appearancePrefs = com.animeow.app.data.preferences.AppearancePreferences(fixture.context)
        appearancePrefs.restore(
            com.animeow.app.ui.theme.AppearanceSettings(
                themeMode = com.animeow.app.ui.theme.ThemeMode.DARK,
            ),
        )
        assertEquals(com.animeow.app.ui.theme.ThemeMode.DARK, appearancePrefs.snapshot().themeMode)

        // 构造来自全新桌面端的备份（settings.json 仅含 desktopStudio，不含手机外观字段）
        val desktopOnlyZip = File(fixture.workDir, "desktop_only.animeow.zip")
        val dbText = JSONObject().put("animes", org.json.JSONArray()).toString()
        val settingsText = JSONObject()
            .put(
                "desktopStudio",
                JSONObject()
                    .put("theme", "cassette")
                    .put("upscaleMode", "ai-m")
                    .put("syncDesktopSettings", true),
            )
            .toString()
        val manifestText = JSONObject()
            .put("type", "animeow-native")
            .put("version", 11)
            .put("createdAt", "2026-11-19T06:30:00Z")
            .toString()
        ZipOutputStream(java.io.FileOutputStream(desktopOnlyZip)).use { zip ->
            zip.writeTextEntry("manifest.json", manifestText)
            zip.writeTextEntry("database.json", dbText)
            zip.writeTextEntry("settings.json", settingsText)
            zip.writeTextEntry("configuration_profiles.json", "{}")
        }

        val prepared = fixture.service.prepareRestore(desktopOnlyZip)
        try {
            fixture.service.restore(
                prepared,
                selection = NativeRestoreSelection(),
                strategy = NativeRestoreStrategy.REPLACE,
            )
        } finally {
            fixture.service.discard(prepared)
        }

        // 手机端原有暗色主题未被空白桌面端重置
        assertEquals(com.animeow.app.ui.theme.ThemeMode.DARK, appearancePrefs.snapshot().themeMode)

        // 手机端再次导出备份时，桌面端配置仍原样随 settings.json.desktopStudio 带回
        val reexported = File(fixture.workDir, "mobile_reexport.animeow.zip")
        fixture.service.export(reexported, includeCovers = false)
        val reexportedSettings = readZipJson(reexported, "settings.json")
        val desktopStudio = reexportedSettings.getJSONObject("desktopStudio")
        assertEquals("cassette", desktopStudio.getString("theme"))
        assertEquals("ai-m", desktopStudio.getString("upscaleMode"))
        assertEquals("dark", reexportedSettings.getJSONObject("appearance").getString("themeMode"))
    }

    // ---- helpers ----

    private fun legacyFingerprint(backup: File): String = legacyFingerprintText(
        databaseText = readZipText(backup, "database.json"),
        settingsText = readZipText(backup, "settings.json"),
        profilesText = readZipText(backup, "configuration_profiles.json"),
    )

    /** 旧版校验器口径：database 原文 + 0x00 + settings + 0x00 + 条目名 + 0x00 + profiles。 */
    private fun legacyFingerprintText(databaseText: String, settingsText: String, profilesText: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(databaseText.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(settingsText.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update("configuration_profiles.json".toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(profilesText.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readZipText(backup: File, entry: String): String {
        ZipInputStream(FileInputStream(backup).buffered()).use { zip ->
            while (true) {
                val current = zip.nextEntry ?: error("缺少条目 $entry")
                if (current.name == entry) {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
    }

    private fun readZipJson(backup: File, entry: String): JSONObject = JSONObject(readZipText(backup, entry))

    private fun zipEntryNames(backup: File): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(FileInputStream(backup).buffered()).use { zip ->
            while (true) {
                val current: ZipEntry = zip.nextEntry ?: break
                names.add(current.name)
                zip.closeEntry()
            }
        }
        return names
    }

    private fun ZipOutputStream.writeTextEntry(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
