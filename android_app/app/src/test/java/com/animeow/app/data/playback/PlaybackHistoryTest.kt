package com.animeow.app.data.playback

import android.app.Application
import androidx.room.Room
import com.animeow.app.data.LibraryRepository
import com.animeow.app.data.bangumi.BangumiSyncQueue
import com.animeow.app.data.bangumi.BangumiEpisodeDelta
import com.animeow.app.data.bangumi.decodeCollectionType
import com.animeow.app.data.cloud.CloudSyncPreferences
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
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class PlaybackHistoryTest {
    @Test fun completedActionsRequireKnownEndAndAContiguousMatchingSourceEpisode() {
        val checkpoint = PlaybackCheckpoint("seven", "work", "1", 99_000, 100_000)
        val first = com.animeow.app.data.remote.PlaybackEpisode("1", "01", 1)
        val second = com.animeow.app.data.remote.PlaybackEpisode("2", "02", 2)
        val association = PlaybackAssociation("seven", com.animeow.app.data.remote.PlaybackWork("work", "作品", ""), "line",
            listOf(com.animeow.app.data.remote.PlaybackLine("line", "线路", listOf(first, second))))
        assertTrue(playbackCompleted(checkpoint))
        assertFalse(playbackCompleted(checkpoint.copy(positionMs = 98_999)))
        assertFalse(playbackCompleted(checkpoint.copy(durationMs = 0)))
        assertFalse(playbackCompleted(checkpoint.copy(positionMs = 0)))
        assertEquals(second, completedPlaybackNext(checkpoint, association))
        assertNull(completedPlaybackNext(checkpoint.copy(source = "giri"), association))
        assertNull(completedPlaybackNext(checkpoint.copy(workId = "other"), association))
        assertNull(completedPlaybackNext(checkpoint, association.copy(lines = listOf(association.lines.first().copy(episodes = listOf(first, second.copy(number = 3)))))))
        assertNull(completedPlaybackNext(checkpoint, association.copy(lines = listOf(association.lines.first().copy(episodes = listOf(first, second.copy(number = null)))))))
    }

    @Test fun removingHistoryClearsAllEpisodePositionsAndLateFlushCannotResurrectIt() {
        val context = RuntimeEnvironment.getApplication()
        val history = PlaybackHistory(context)
        val anime = AnimeEntity(id = 88, title = "历史保护", createdAt = "2026-10-20")
        val first = PlaybackCheckpoint("giri", "123", "01", 30000, 100000)
        history.describe("history-a", anime, "第01集")
        history.save("history-a", first)
        history.save("history-a", first.copy(episodeId = "02"))
        history.save("history-b", first)
        history.remove("history-a")
        history.save("history-a", first) // A previously paused player may flush after this screen closes.
        assertNull(history.read("history-a"))
        assertNull(history.readEpisode("history-a", "giri", "123", "01"))
        assertNull(history.readEpisode("history-a", "giri", "123", "02"))
        assertNotNull(history.read("history-b"))
        assertTrue(history.entries().none { it.key == "history-a" })
        history.importExisting("history-a", anime, "第01集")
        assertTrue(history.entries().none { it.key == "history-a" })
        history.describe("history-a", anime, "第01集") // Explicitly playing again permits a new history.
        history.save("history-a", first)
        assertNotNull(history.read("history-a"))
    }
    @Test fun playedCoverageDoesNotCountSeeksOrDoubleCountRewatching() {
        var ranges = mergePlaybackRanges(emptyList(), 0, 30_000)
        ranges = mergePlaybackRanges(ranges, 80_000, 90_000)
        assertFalse(playbackComplete(ranges, 100_000))
        ranges = mergePlaybackRanges(ranges, 0, 30_000)
        assertEquals(40_000L, ranges.sumOf { it.last - it.first })
        ranges = mergePlaybackRanges(ranges, 20_000, 80_000)
        assertEquals(listOf(0L..90_000L), ranges)
        assertTrue(playbackComplete(ranges, 100_000))
        assertFalse(playbackComplete(listOf(0L..89_999L), 100_000))
        assertFalse(playbackComplete(listOf(0L..10_000L), 0))
        assertFalse(playbackComplete(listOf(0L..10_000L), 10_000))
    }
    @Test fun checkpointsSurviveReopeningAndKeepSeparateEpisodePositionsAndUndoSuppression() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("playback_history_v1", 0).edit().clear().commit()
        val anime = AnimeEntity(id = 10, title = "示例动画", createdAt = "2026-10-18")
        val key = playbackHistoryKey(anime)
        val first = PlaybackCheckpoint("giri", "123", "1-1", 40_000, 100_000, listOf(0L..40_000L), handled = true)
        PlaybackHistory(context).save(key, first)
        val second = first.copy(episodeId = "1-2", positionMs = 5000, ranges = listOf(0L..5000L), handled = false)
        PlaybackHistory(context).save(key, second)
        val reopened = PlaybackHistory(context)
        assertEquals(second, reopened.read(key))
        assertEquals(first, reopened.readEpisode(key, "giri", "123", "1-1"))
        assertNull(reopened.read(playbackHistoryKey(anime.copy(title = "恢复后不同的作品"))))
        assertNull(reopened.readEpisode(key, "seven", "123", "1-1"))
    }
    @Test fun autoProgressUsesExpectedEpisodeAndUndoDoesNotClobberLaterUserEdits() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val id = db.libraryDao().insertAnime(AnimeEntity(title = "播放进度", watchedEpisodes = 0, totalEpisodes = 12))
            val repository = LibraryRepository(db)
            assertNull(repository.incrementProgress(id, expectedProgress = 4))
            val change = requireNotNull(repository.incrementProgress(id, expectedProgress = 0))
            assertNull(repository.incrementProgress(id, expectedProgress = 0))
            assertEquals(1, db.libraryDao().getAnime(id)?.watchedEpisodes)
            assertEquals("在看", db.libraryDao().getAnime(id)?.status)
            assertTrue(repository.restoreProgress(change, syncBangumi = true))
            assertEquals(0, db.libraryDao().getAnime(id)?.watchedEpisodes)
            assertEquals("想看", db.libraryDao().getAnime(id)?.status)
            assertTrue(db.libraryDao().getWatchRecords().isEmpty())
            val next = requireNotNull(repository.incrementProgress(id, expectedProgress = 0))
            repository.setProgress(id, 3)
            assertFalse(repository.restoreProgress(next, syncBangumi = true))
            assertEquals(3, db.libraryDao().getAnime(id)?.watchedEpisodes)
        } finally { db.close() }
    }

    @Test fun batchEpisodeCorrectionsKeepTotalsStatusesBooksTrashAndHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao()
            val short = dao.insertAnime(AnimeEntity(title = "短篇", totalEpisodes = 6, watchedEpisodes = 6))
            val long = dao.insertAnime(AnimeEntity(title = "长篇", totalEpisodes = 24, watchedEpisodes = 24))
            val watching = dao.insertAnime(AnimeEntity(title = "在看", status = "在看", totalEpisodes = 12, watchedEpisodes = 3))
            val book = dao.insertAnime(AnimeEntity(title = "书籍", subjectType = "book", totalEpisodes = 10, watchedEpisodes = 3))
            val trash = dao.insertAnime(AnimeEntity(title = "回收站", deletedAt = "2026-11-10", watchedEpisodes = 2))
            val record = com.animeow.app.data.local.WatchRecordEntity(animeId = long, episode = 24, status = "watched",
                recordDate = "2026-11-10T12:00:00+08:00")
            dao.insertWatchRecord(record)
            val repository = LibraryRepository(db)
            assertEquals(1, repository.updateSelectedEpisodeProgress(setOf(short, long, book, trash), 10))
            assertEquals(6, dao.getAnime(short)?.watchedEpisodes)
            assertEquals(10, dao.getAnime(long)?.watchedEpisodes)
            assertEquals(2, repository.resetUnwatchedEpisodeProgress())
            assertEquals(0, repository.resetUnwatchedEpisodeProgress())
            assertEquals(0, dao.getAnime(short)?.watchedEpisodes)
            assertEquals(24, dao.getAnime(long)?.totalEpisodes)
            assertEquals("想看", dao.getAnime(long)?.status)
            assertEquals(3, dao.getAnime(watching)?.watchedEpisodes)
            assertEquals(3, dao.getAnime(book)?.watchedEpisodes)
            assertEquals(2, dao.getAnime(trash)?.watchedEpisodes)
            assertEquals(24, dao.getWatchRecords().single().episode)
        } finally { db.close() }
    }
    @Test fun batchPreviewIsAtomicAndUndoPreservesLaterChanges() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao()
            val first = dao.insertAnime(AnimeEntity(title = "短篇", totalEpisodes = 6, watchedEpisodes = 4))
            val second = dao.insertAnime(AnimeEntity(title = "长篇", totalEpisodes = 24, watchedEpisodes = 12))
            val book = dao.insertAnime(AnimeEntity(title = "书籍", subjectType = "book", watchedEpisodes = 3))
            val completed = dao.insertAnime(AnimeEntity(title = "完成", status = "看完", totalEpisodes = 12, watchedEpisodes = 12))
            val repository = LibraryRepository(db)
            val preview = repository.previewBatchEpisodeProgress(setOf(first, second, book, completed), 8)
            assertEquals(3, preview.eligibleCount)
            assertEquals(listOf(6, 8), preview.edits.map { it.progress })
            dao.updateAnime(requireNotNull(dao.getAnime(second)).copy(review = "预览后新增感想"))
            try {
                repository.applyBatchEpisodeProgress(preview)
                fail("A stale preview must reject the whole batch")
            } catch (_: IllegalStateException) { }
            assertEquals(4, dao.getAnime(first)?.watchedEpisodes)
            val current = repository.previewBatchEpisodeProgress(setOf(first, second, book, completed), 8)
            val appliedAt = repository.applyBatchEpisodeProgress(current)
            assertEquals(appliedAt, dao.getAnime(first)?.updatedAt)
            dao.updateAnime(requireNotNull(dao.getAnime(second)).copy(watchedEpisodes = 9))
            assertEquals(1, repository.undoBatchEpisodeProgress(current, appliedAt))
            assertEquals(4, dao.getAnime(first)?.watchedEpisodes)
            assertEquals(9, dao.getAnime(second)?.watchedEpisodes)
            val reset = repository.previewBatchEpisodeProgress(null, 0)
            assertEquals(setOf(first, second), reset.edits.map { it.before.id }.toSet())
            val resetAt = repository.applyBatchEpisodeProgress(reset)
            assertEquals(2, repository.undoBatchEpisodeProgress(reset, resetAt))
            assertEquals(9, dao.getAnime(second)?.watchedEpisodes)
            assertEquals(3, dao.getAnime(book)?.watchedEpisodes)
            assertEquals(12, dao.getAnime(completed)?.watchedEpisodes)
            assertTrue(dao.getWatchRecords().isEmpty())
            assertTrue(dao.getBangumiPendingSyncs().isEmpty())
        } finally { db.close() }
    }

    @Test fun playbackUndoCancelsPendingBangumiEpisodesAndRestoresCollectionState() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        CloudSyncPreferences(context).updateBangumiSyncConfiguration(progressSync = true, ratingSync = false, wifiOnly = true)
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val id = db.libraryDao().insertAnime(AnimeEntity(title = "队列验证", status = "在看", totalEpisodes = 1,
                externalSource = "bangumi", externalId = "123"))
            val repository = LibraryRepository(db, bangumiSync = BangumiSyncQueue(db, context))
            val change = requireNotNull(repository.incrementProgress(id, autoCompleteStatus = true, completionStatus = "看完", expectedProgress = 0))
            val pending = db.libraryDao().getBangumiPendingSync(id, "episodes")
            assertEquals(setOf(1), BangumiEpisodeDelta.decode(requireNotNull(pending).payloadJson).add)
            assertEquals(2, decodeCollectionType(requireNotNull(db.libraryDao().getBangumiPendingSync(id, "status")).payloadJson))
            assertTrue(repository.restoreProgress(change, syncBangumi = true))
            assertNull(db.libraryDao().getBangumiPendingSync(id, "episodes"))
            assertEquals(3, decodeCollectionType(requireNotNull(db.libraryDao().getBangumiPendingSync(id, "status")).payloadJson))
            assertEquals("在看", db.libraryDao().getAnime(id)?.status)
            assertEquals(0, db.libraryDao().getAnime(id)?.watchedEpisodes)
        } finally { db.close() }
    }
}
