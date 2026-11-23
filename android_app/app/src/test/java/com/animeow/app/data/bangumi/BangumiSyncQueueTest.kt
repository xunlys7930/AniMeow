package com.animeow.app.data.bangumi

import android.content.Context
import androidx.room.Room
import com.animeow.app.data.cloud.CloudSyncPreferences
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.AnimeEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class BangumiSyncQueueTest {
    private lateinit var context: Context
    private lateinit var database: AniMeowDatabase
    private lateinit var queue: BangumiSyncQueue

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        queue = BangumiSyncQueue(database, context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun episodeDeltasMergeWithLaterWritesWinningPerEpisode() {
        // 集 2 看过又撤销（变回基准）→ 抵消；集 3 是新出现的撤销
        val merged = mergeEpisodeDeltas(
            BangumiEpisodeDelta(add = setOf(1, 2)),
            BangumiEpisodeDelta(remove = setOf(2, 3)),
        )
        assertEquals(setOf(1), merged.add)
        assertEquals(setOf(3), merged.remove)

        // 集 5 撤销又重看（变回基准）→ 抵消；集 6 是新出现的补看
        val rewatch = mergeEpisodeDeltas(
            BangumiEpisodeDelta(remove = setOf(5)),
            BangumiEpisodeDelta(add = setOf(5, 6)),
        )
        assertEquals(setOf(6), rewatch.add)
        assertTrue(rewatch.remove.isEmpty())
    }

    @Test
    fun oppositeDeltasCancelToEmpty() {
        val merged = mergeEpisodeDeltas(
            BangumiEpisodeDelta(add = setOf(1)),
            BangumiEpisodeDelta(remove = setOf(1)),
        )
        assertTrue(merged.isEmpty)
    }

    @Test
    fun payloadCodecRoundTrips() {
        val delta = BangumiEpisodeDelta(add = setOf(2, 1), remove = setOf(3))
        assertEquals(delta, BangumiEpisodeDelta.decode(delta.encode()))
        assertEquals(3, decodeCollectionType(encodeCollectionType(3)))
        assertEquals(9, decodeRate(encodeRate(9)))
        assertNull(decodeCollectionType("{}"))
        assertNull(decodeRate("not json"))
    }

    @Test
    fun statusMapsToBangumiCollectionTypes() {
        assertEquals(1, bangumiCollectionType("想看"))
        assertEquals(2, bangumiCollectionType("看完"))
        assertEquals(2, bangumiCollectionType("已完成"))
        assertEquals(2, bangumiCollectionType("完成"))
        assertEquals(3, bangumiCollectionType("在看"))
        assertEquals(4, bangumiCollectionType("搁置"))
        assertEquals(5, bangumiCollectionType("弃坑"))
        assertNull(bangumiCollectionType("未看"))
        assertNull(bangumiCollectionType("已补完"))
    }

    @Test
    fun localRatingConvertsToBangumiRate() {
        assertEquals(0, bangumiRate(null))
        assertEquals(0, bangumiRate(0))
        assertEquals(9, bangumiRate(85))
        assertEquals(8, bangumiRate(84))
        assertEquals(10, bangumiRate(100))
        assertEquals(0, bangumiRate(4))
    }

    @Test
    fun onlyBangumiLinkedAnimesJoinSync() {
        assertEquals(33L, bangumiSubjectIdOf(linkedAnime(source = "bangumi", id = "33")))
        assertNull(bangumiSubjectIdOf(linkedAnime(source = "anilist", id = "33")))
        assertNull(bangumiSubjectIdOf(linkedAnime(source = "bangumi", id = "abc")))
        assertNull(bangumiSubjectIdOf(linkedAnime(source = null, id = "33")))
    }

    @Test
    fun enqueueMergesEpisodeDeltasIntoOneRow() = runBlocking {
        setToggles(progress = true, rating = true)
        assertTrue(queue.enqueueEpisodes(animeId = 1, subjectId = 100, add = setOf(1, 2), remove = emptySet()))
        assertTrue(queue.enqueueEpisodes(animeId = 1, subjectId = 100, add = setOf(3), remove = setOf(2)))

        val rows = database.libraryDao().getBangumiPendingSyncs()
        assertEquals(1, rows.size)
        val delta = BangumiEpisodeDelta.decode(rows.single().payloadJson)
        assertEquals(setOf(1, 3), delta.add)
        // 集 2 看过又撤销 → 变回基准，抵消后不再上报
        assertEquals(setOf<Int>(), delta.remove)
    }

    @Test
    fun cancellingDeltasDropTheRowEntirely() = runBlocking {
        setToggles(progress = true, rating = true)
        assertTrue(queue.enqueueEpisodes(animeId = 2, subjectId = 100, add = setOf(4), remove = emptySet()))
        assertFalse(queue.enqueueEpisodes(animeId = 2, subjectId = 100, add = emptySet(), remove = setOf(4)))
        assertTrue(database.libraryDao().getBangumiPendingSyncs().isEmpty())
    }

    @Test
    fun statusAndRatingRowsKeepLatestIntent() = runBlocking {
        setToggles(progress = true, rating = true)
        queue.enqueueStatus(animeId = 3, subjectId = 100, type = 3)
        queue.enqueueStatus(animeId = 3, subjectId = 100, type = 2)
        queue.enqueueRating(animeId = 3, subjectId = 100, rate = 7)
        queue.enqueueRating(animeId = 3, subjectId = 100, rate = 9)

        val dao = database.libraryDao()
        assertEquals(2, dao.getBangumiPendingSyncs().size)
        assertEquals(2, decodeCollectionType(dao.getBangumiPendingSync(3, BangumiSyncKind.STATUS)!!.payloadJson))
        assertEquals(9, decodeRate(dao.getBangumiPendingSync(3, BangumiSyncKind.RATING)!!.payloadJson))
    }

    @Test
    fun disabledTogglesRefuseEnqueue() = runBlocking {
        setToggles(progress = false, rating = false)
        assertFalse(queue.enqueueEpisodes(animeId = 4, subjectId = 100, add = setOf(1), remove = emptySet()))
        assertFalse(queue.enqueueStatus(animeId = 4, subjectId = 100, type = 2))
        assertFalse(queue.enqueueRating(animeId = 4, subjectId = 100, rate = 8))
        assertTrue(database.libraryDao().getBangumiPendingSyncs().isEmpty())

        setToggles(progress = false, rating = true)
        assertFalse(queue.enqueueEpisodes(animeId = 4, subjectId = 100, add = setOf(1), remove = emptySet()))
        assertTrue(queue.enqueueRating(animeId = 4, subjectId = 100, rate = 8))
    }

    private suspend fun setToggles(progress: Boolean, rating: Boolean) {
        CloudSyncPreferences(context).updateBangumiSyncConfiguration(
            progressSync = progress,
            ratingSync = rating,
            wifiOnly = true,
        )
    }

    private fun linkedAnime(source: String?, id: String?) = AnimeEntity(
        title = "测试作品",
        externalSource = source,
        externalId = id,
    )
}
