package com.animeow.app.ui.tracker

import android.app.Application
import androidx.room.Room
import com.animeow.app.data.*
import com.animeow.app.data.local.*
import com.animeow.app.data.remote.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class BatchMetadataControllerTest {
    @Test fun skipDoesNotSearchAliasesAndRetryTouchesOnlyUnresolvedItems() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val dao = db.libraryDao()
            val first = dao.insertAnime(AnimeEntity(title = "第一部", originalTitle = "First", watchedEpisodes = 10, totalEpisodes = 12))
            val second = dao.insertAnime(AnimeEntity(title = "第二部"))
            val personalTag = dao.insertTag(TagEntity(name = "个人标签"))
            dao.insertAnimeTags(listOf(AnimeTagEntity(first, personalTag)))
            val calls = mutableListOf<Long>()
            var releaseFirst = false
            var succeeded = emptySet<Long>()
            val controller = BatchMetadataController(scope, LibraryRepository(db), search = { anime, _ ->
                calls += anime.id
                if (anime.id == first && !releaseFirst) awaitCancellation()
                RemoteFetchResult(listOf(RemoteAnime(RemoteCatalogSource.BANGUMI, anime.id.toString(), anime.title,
                    episodes = 6, summary = "公开简介", tags = listOf("来源标签"))))
            }, onError = { throw it }, onFinished = { succeeded = it })
            controller.startBatchMetadataMatch(BatchMetadataOptions(fields = RemoteMetadataFields(tags = true), overwriteExisting = true), setOf(first, second))
            withTimeout(5000) { while (calls.isEmpty()) delay(10) }
            controller.skipCurrentMetadataMatch()
            withTimeout(5000) { while (controller.state.value.isRunning) delay(10) }
            assertEquals(listOf(first, second), calls)
            assertEquals(BatchMetadataOutcome.SKIPPED, controller.state.value.results.first().outcome)
            assertEquals(setOf(first), controller.state.value.retryIds)
            assertEquals(setOf(second), succeeded)
            releaseFirst = true
            controller.retryBatchMetadataMatch()
            withTimeout(5000) { while (controller.state.value.isRunning) delay(10) }
            assertEquals(listOf(first, second, first), calls)
            assertEquals(10, dao.getAnime(first)?.watchedEpisodes)
            assertEquals(6, dao.getAnime(first)?.totalEpisodes)
            assertTrue(personalTag in dao.getTagIdsForAnime(first))
            assertEquals(2, dao.getTagIdsForAnime(first).size)
            assertTrue(controller.state.value.retryIds.isEmpty())
        } finally { scope.cancel(); db.close() }
    }

    @Test fun networkFailureUnmatchedAndStoppedPendingRowsRemainDistinguishable() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val dao = db.libraryDao()
            val ids = (1..3).map { dao.insertAnime(AnimeEntity(title = "作品$it")) }
            var waiting = false
            val controller = BatchMetadataController(scope, LibraryRepository(db), search = { anime, _ ->
                when (anime.id) {
                    ids[0] -> throw java.io.IOException("offline")
                    ids[1] -> RemoteFetchResult(emptyList())
                    else -> { waiting = true; awaitCancellation() }
                }
            }, onError = {}, onFinished = {})
            controller.startBatchMetadataMatch(BatchMetadataOptions(), ids.toSet())
            withTimeout(5000) { while (!waiting) delay(10) }
            controller.cancelBatchMetadataMatch()
            withTimeout(5000) { while (controller.state.value.isRunning) delay(10) }
            assertEquals(listOf(BatchMetadataOutcome.FAILED, BatchMetadataOutcome.UNMATCHED, BatchMetadataOutcome.PENDING), controller.state.value.results.map { it.outcome })
            assertEquals(2, controller.state.value.completed)
            assertEquals(ids.toSet(), controller.state.value.retryIds)
            assertTrue(controller.state.value.message!!.contains("已停止"))
        } finally { scope.cancel(); db.close() }
    }
}
