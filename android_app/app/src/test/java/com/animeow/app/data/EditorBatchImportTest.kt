package com.animeow.app.data

import androidx.room.Room
import com.animeow.app.data.local.*
import com.animeow.app.data.remote.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class EditorBatchImportTest {
    private suspend fun withDatabase(block: suspend (LibraryRepository, LibraryDao) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try { block(LibraryRepository(db), db.libraryDao()) } finally { db.close() }
    }

    @Test fun fillAndOverwritePreservePersonalProgressRatingsReviewTagsAndIdentity() = runBlocking {
        withDatabase { repo, dao ->
            val remote = RemoteAnime(RemoteCatalogSource.BANGUMI, "100", "第一季", coverUrl = "old-cover", episodes = 8)
            val id = repo.importBatchItem(remote, null).first
            val original = dao.getAnime(id)!!.copy(watchedEpisodes = 8, status = "看完", rating = 90, review = "我的心得", watchFinishDate = "2026-10-16")
            dao.updateAnime(original)
            val tagId = dao.insertTag(TagEntity(name = "个人标签"))
            dao.insertAnimeTags(listOf(AnimeTagEntity(id, tagId)))
            val updated = remote.copy(coverUrl = "new-cover", summary = "新增简介", episodes = 12, score = 1.0, tags = listOf("远端标签"))
            repo.importBatchItem(updated, BatchSeriesDestination(), BatchMetadataMode.FILL_BLANKS)
            val filled = dao.getAnime(id)!!
            assertEquals("old-cover", filled.coverUrl)
            assertEquals("新增简介", filled.synopsis)
            assertEquals(8, filled.watchedEpisodes)
            assertEquals("看完", filled.status)
            assertEquals(90, filled.rating)
            assertEquals("我的心得", filled.review)
            repo.importBatchItem(updated, BatchSeriesDestination(), BatchMetadataMode.OVERWRITE)
            val overwritten = dao.getAnime(id)!!
            assertEquals("new-cover", overwritten.coverUrl)
            assertEquals(12, overwritten.totalEpisodes)
            assertEquals(8, overwritten.watchedEpisodes)
            assertEquals(90, overwritten.rating)
            assertEquals("2026-10-16", overwritten.watchFinishDate)
            assertEquals("我的心得", overwritten.review)
            assertEquals(listOf(tagId), dao.getTagIdsForAnime(id))
            assertEquals("100", overwritten.externalId)
        }
    }

    @Test fun noneAndExistingSeriesAreIndependentChoicesAndNoImplicitSeriesIsCreated() = runBlocking {
        withDatabase { repo, dao ->
            val existingId = dao.insertSeriesItem(SeriesEntity(name = "已有系列", createdAt = "2026-10-17"))
            val first = RemoteAnime(RemoteCatalogSource.BANGUMI, "1", "第一季")
            val second = first.copy(id = "2", title = "第二季")
            val ids = listOf(first, second).map { repo.importBatchItem(it, BatchSeriesDestination(existingSeriesId = existingId), BatchMetadataMode.KEEP).first }
            ids.forEach { assertEquals(existingId, dao.getAnime(it)!!.seriesId) }
            val third = repo.importBatchItem(first.copy(id = "3", title = "独立作品"), BatchSeriesDestination(), BatchMetadataMode.KEEP).first
            assertNull(dao.getAnime(third)!!.seriesId)
            repo.importBatchItem(first, BatchSeriesDestination(), BatchMetadataMode.FILL_BLANKS)
            assertEquals(existingId, dao.getAnime(ids.first())!!.seriesId)
            assertNull(dao.getSeriesByName("独立作品"))
            val missing = first.copy(id = "4", title = "错误目标")
            val failed = repo.importBatchItems(listOf(missing), BatchSeriesDestination(existingSeriesId = 99999), BatchMetadataMode.KEEP)
            assertTrue(failed.succeededKeys.isEmpty())
            assertEquals(3, dao.getActiveAnimes().size)
        }
    }

    @Test fun partialFailuresKeepOtherSuccessesAndRetryDoesNotDuplicateOrMoveExistingMembership() = runBlocking {
        withDatabase { repo, dao ->
            val first = RemoteAnime(RemoteCatalogSource.BANGUMI, "1", "第一季")
            val conflict = first.copy(id = "2", title = "已有另一系列的第二季", coverUrl = "original")
            val conflictId = repo.importBatchItem(conflict, "原系列").first
            val old = dao.getAnime(conflictId)!!
            val selection = listOf(first, conflict.copy(coverUrl = "do-not-overwrite"))
            val report = repo.importBatchItems(selection, BatchSeriesDestination(newSeriesName = "目标系列"), BatchMetadataMode.OVERWRITE)
            assertEquals(setOf(first.uniqueKey), report.succeededKeys)
            assertEquals(setOf(conflict.uniqueKey), report.failures.keys)
            assertEquals("已处理 1 部作品，1 部失败，保留勾选可重试", report.message)
            assertEquals(old, dao.getAnime(conflictId))
            assertEquals(2, dao.getActiveAnimes().size)
            val retry = repo.importBatchItems(selection.filter { it.uniqueKey in report.failures }, BatchSeriesDestination(), BatchMetadataMode.FILL_BLANKS)
            assertEquals(setOf(conflict.uniqueKey), retry.succeededKeys)
            assertTrue(retry.failures.isEmpty())
            assertEquals("已处理 1 部作品，已保存到资料库", retry.message)
            assertEquals(old.seriesId, dao.getAnime(conflictId)!!.seriesId)
            assertEquals("original", dao.getAnime(conflictId)!!.coverUrl)
            assertEquals(2, dao.getActiveAnimes().size)
        }
    }

    @Test fun remoteEpisodeReductionDoesNotReduceProgressAndKeepModeDoesNotReplaceMetadata() = runBlocking {
        withDatabase { repo, dao ->
            val remote = RemoteAnime(RemoteCatalogSource.BANGUMI, "1", "作品", episodes = 12, summary = "原简介")
            val id = repo.importBatchItem(remote, null).first
            val original = dao.getAnime(id)!!.copy(watchedEpisodes = 10)
            dao.updateAnime(original)
            repo.importBatchItem(remote.copy(episodes = 6, summary = "新简介"), BatchSeriesDestination(), BatchMetadataMode.KEEP)
            assertEquals(original, dao.getAnime(id))
            repo.importBatchItem(remote.copy(episodes = 6), BatchSeriesDestination(), BatchMetadataMode.OVERWRITE)
            assertEquals(10, dao.getAnime(id)!!.watchedEpisodes)
            assertEquals(12, dao.getAnime(id)!!.totalEpisodes)
        }
    }
}
