package com.animeow.app.data.bangumi

import android.content.Context
import androidx.room.Room
import com.animeow.app.data.LibraryRepository
import com.animeow.app.data.cloud.CloudSyncPreferences
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.WatchStatusEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 五个写入挂点的 Local-First 入队行为（撤销路径 P0 不同步，属已知限制）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class BangumiRepositorySyncHooksTest {
    private lateinit var context: Context
    private lateinit var database: AniMeowDatabase
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LibraryRepository(bangumiSync = BangumiSyncQueue(database, context), database = database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun incrementProgressQueuesEpisodeAndAutoCompletionStatus() = runBlocking {
        enableToggles()
        val id = linkedAnime(watched = 11, total = 12, status = "在看")

        val change = repository.incrementProgress(id, autoCompleteStatus = true)

        assertEquals(12, change?.current)
        assertEquals("看完", database.libraryDao().getAnime(id)?.status)
        val rows = database.libraryDao().getBangumiPendingSyncs()
        assertEquals(setOf(12), BangumiEpisodeDelta.decode(rows.single { it.kind == BangumiSyncKind.EPISODES }.payloadJson).add)
        assertEquals(2, decodeCollectionType(rows.single { it.kind == BangumiSyncKind.STATUS }.payloadJson))
    }

    @Test
    fun plainIncrementQueuesEpisodeOnly() = runBlocking {
        enableToggles()
        val id = linkedAnime(watched = 2, total = 12, status = "在看")

        repository.incrementProgress(id)

        val rows = database.libraryDao().getBangumiPendingSyncs()
        assertEquals(1, rows.size)
        assertEquals(setOf(3), BangumiEpisodeDelta.decode(rows.single().payloadJson).add)
    }

    @Test
    fun setProgressQueuesTheEpisodeDifference() = runBlocking {
        enableToggles()
        val id = linkedAnime(watched = 3, total = 10, status = "在看")

        repository.setProgress(id, 5)

        val rows = database.libraryDao().getBangumiPendingSyncs()
        assertEquals(setOf(4, 5), BangumiEpisodeDelta.decode(rows.single().payloadJson).add)

        repository.setProgress(id, 2)
        val merged = BangumiEpisodeDelta.decode(database.libraryDao().getBangumiPendingSyncs().single().payloadJson)
        assertEquals(setOf<Int>(), merged.add)
        // 4、5 看过又撤销抵消，只有 3 相对基准（原本看过）需要上报撤销
        assertEquals(setOf(3), merged.remove)
    }

    @Test
    fun statusChangesQueueCollectionTypes() = runBlocking {
        enableToggles()
        seedDefaultStatuses()
        val id = linkedAnime(watched = 1, total = 12, status = "在看")

        repository.updateStatuses(setOf(id), "弃坑")
        assertEquals(
            5,
            decodeCollectionType(database.libraryDao().getBangumiPendingSync(id, BangumiSyncKind.STATUS)!!.payloadJson),
        )

        // 循环状态（在默认状态里是 弃坑 → 在看）
        repository.cycleStatus(id)
        assertEquals(
            3,
            decodeCollectionType(database.libraryDao().getBangumiPendingSync(id, BangumiSyncKind.STATUS)!!.payloadJson),
        )
    }

    @Test
    fun ratingSyncsOnlyWhenItActuallyChanged() = runBlocking {
        enableToggles()
        val id = linkedAnime(watched = 1, total = 12, status = "在看")

        repository.saveAnime(anime(id, rating = 85), emptySet())
        assertEquals(
            9,
            decodeRate(database.libraryDao().getBangumiPendingSync(id, BangumiSyncKind.RATING)!!.payloadJson),
        )

        database.libraryDao().deleteBangumiPendingSync(id, BangumiSyncKind.RATING)
        repository.saveAnime(anime(id, rating = 85), emptySet())
        assertTrue(database.libraryDao().getBangumiPendingSyncs().isEmpty())

        repository.saveAnime(anime(id, rating = 95), emptySet())
        assertEquals(
            10,
            decodeRate(database.libraryDao().getBangumiPendingSync(id, BangumiSyncKind.RATING)!!.payloadJson),
        )
    }

    @Test
    fun worksWithoutBangumiIdentityNeverEnqueue() = runBlocking {
        enableToggles()
        val id = repository.saveAnime(
            AnimeEntity(title = "本地作品", status = "在看", watchedEpisodes = 0, totalEpisodes = 12),
            emptySet(),
        )

        repository.incrementProgress(id, autoCompleteStatus = true)
        repository.updateStatuses(setOf(id), "看完")
        repository.saveAnime(AnimeEntity(id = id, title = "本地作品", status = "看完", rating = 90), emptySet())

        assertTrue(database.libraryDao().getBangumiPendingSyncs().isEmpty())
    }

    private suspend fun enableToggles() {
        CloudSyncPreferences(context).updateBangumiSyncConfiguration(
            progressSync = true,
            ratingSync = true,
            wifiOnly = true,
        )
    }

    /** cycleStatus 按库里的观看状态循环；空库循环不出结果，所以先种入默认状态（与 LegacySnapshotMapper 一致）。 */
    private suspend fun seedDefaultStatuses() {
        database.libraryDao().insertWatchStatuses(
            listOf(
                WatchStatusEntity(1, "在看", 0xFF2196F3, 0),
                WatchStatusEntity(2, "看完", 0xFF4CAF50, 1),
                WatchStatusEntity(3, "未看", 0xFFFF9800, 2),
                WatchStatusEntity(4, "弃坑", 0xFFF44336, 3),
            ),
        )
    }

    private suspend fun linkedAnime(watched: Int, total: Int, status: String): Long =
        repository.saveAnime(anime(0, watched, total, status), emptySet())

    private fun anime(id: Long, watched: Int = 1, total: Int = 12, status: String = "在看", rating: Int? = null) = AnimeEntity(
        id = id,
        title = "测试作品",
        status = status,
        watchedEpisodes = watched,
        totalEpisodes = total,
        rating = rating,
        externalSource = "bangumi",
        externalId = "555",
    )
}
