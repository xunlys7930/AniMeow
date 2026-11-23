package com.animeow.app.data.games

import androidx.room.Room
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.cloud.CloudSyncPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class GameSelectionTest {
    @Test fun tagBatchRenameAndRemoveKeepPersonalFieldsAndRollbackIfAnyRowWouldOverflow() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.gameDao()
            val first = dao.insert(GameEntity(title = "游戏", tags = "剧情，旧标签", playMinutes = 120, rating = 9, review = "心得"))
            val other = dao.insert(GameEntity(title = "其他", tags = "其他标签"))
            val repo = GameRepository(context, db)
            repo.updateTags(setOf(first), "治愈，剧情", false)
            val row = dao.get(first)!!
            assertEquals("剧情，旧标签，治愈", row.tags)
            assertEquals(120L, row.playMinutes); assertEquals(9, row.rating); assertEquals("心得", row.review)
            assertEquals("其他标签", dao.get(other)!!.tags)
            repo.updateTags(null, "旧标签", false, "剧情")
            assertEquals("剧情，治愈", dao.get(first)!!.tags)
            repo.updateTags(null, "剧情", true)
            assertEquals("治愈", dao.get(first)!!.tags)
            try { repo.updateTags(setOf(first, other), "长".repeat(1024), false); fail("oversize tags") } catch (_: IllegalArgumentException) { }
            assertEquals("治愈", dao.get(first)!!.tags)
            assertEquals("其他标签", dao.get(other)!!.tags)
        } finally { db.close() }
    }
    @Test fun batchStatusReadsLatestRowsAndPreservesRecordsWhileIgnoringDeletedAndMissingIds() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val sync = CloudSyncPreferences(context)
        val original = sync.snapshot()
        sync.updateBangumiSyncConfiguration(false, false, original.bangumiSyncWifiOnly)
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.gameDao()
            val first = GameEntity(title = "第一款", playMinutes = 123, review = "保留心得", selectedPlatform = "PC", rating = 8)
            val id = dao.insert(first)
            val removed = dao.insert(GameEntity(title = "已移除", deletedAt = "2026-10-16T00:00:00Z"))
            val other = dao.insert(GameEntity(title = "未选择"))
            // Simulate a detail edit after selection: batching must use current database rows.
            dao.update(dao.get(id)!!.copy(playMinutes = 456, finishedOn = "2026-10-17"))
            val repository = GameRepository(context, db)
            assertEquals(1, repository.updateSelection(setOf(id, removed, 99999), status = "已玩"))
            assertEquals(first.copy(id = id, status = "已玩", playMinutes = 456, finishedOn = "2026-10-17", updatedAt = dao.get(id)!!.updatedAt), dao.get(id))
            assertEquals("想玩", dao.get(other)!!.status)
            assertNotNull(dao.get(removed)!!.deletedAt)
            assertEquals(0, repository.updateSelection(setOf(id), status = "已玩"))
        } finally {
            db.close()
            sync.updateBangumiSyncConfiguration(original.bangumiProgressSync, original.bangumiRatingSync, original.bangumiSyncWifiOnly)
        }
    }

    @Test fun batchRemovalIsSoftAndInvalidOperationsDoNotPartiallyChangeRows() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.gameDao()
            val id = dao.insert(GameEntity(title = "保留备份资料", playMinutes = 90, review = "保留"))
            val repository = GameRepository(context, db)
            try { repository.updateSelection(setOf(id), status = "看过"); fail("invalid game status") }
            catch (_: IllegalArgumentException) { }
            assertEquals("想玩", dao.get(id)!!.status)
            assertNull(dao.get(id)!!.deletedAt)
            assertEquals(1, repository.updateSelection(setOf(id), delete = true))
            val row = dao.get(id)!!
            assertNotNull(row.deletedAt)
            assertEquals(90L, row.playMinutes)
            assertEquals("保留", row.review)
            assertEquals(0, repository.updateSelection(setOf(id), delete = true))
        } finally { db.close() }
    }
}
