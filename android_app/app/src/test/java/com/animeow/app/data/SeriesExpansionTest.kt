package com.animeow.app.data

import android.app.Application
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
@Config(application = Application::class, sdk = [36])
class SeriesExpansionTest {
    @Test fun sourceRelationsKeepDifferentTitlesTypesAndDirectionsWithoutImportingUnsupportedKinds() {
        val rows = parseSeriesSourceWorks("""[{"id":1,"name":"Original","name_cn":"原作甲","type":1,"relation":"原作"},
            {"id":2,"name_cn":"不同名字的动画乙","type":2,"relation":"动画"},
            {"id":3,"name":"真人丙","type":6,"relation":"真人"},
            {"id":4,"name":"游戏丁","type":4,"relation":"游戏"},
            {"id":1,"name":"重复","type":1},{"id":0,"name":"非法"}]""")
        assertEquals(listOf("1", "2", "3", "4"), rows.map { it.id })
        assertEquals(listOf("book", "anime", null, null), rows.map { it.subjectType })
        assertEquals("原作", rows.first().relation)
        assertEquals("https://bgm.tv/subject/3", rows[2].url)
        assertEquals("真人影视", rows[2].typeLabel)
    }

    @Test fun serverWrappedRelationsKeepTheirStaleCacheNotice() {
        val rows = parseSeriesSourceWorks("""{"_animeow_data":[{"id":123,"type":1,"name_cn":"异名原作","relation":"书籍"}],"_animeow_cache":{"stale":true}}""")
        assertEquals("book", rows.single().subjectType)
        assertEquals("书籍", rows.single().relation)
        assertTrue(rows.single().cacheStale)
    }

    @Test fun seriesRolesSurviveOrdinaryEditsAndNeverLeakIntoAnotherSeries() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).build()
        try {
            val repo = LibraryRepository(db); val dao = db.libraryDao()
            val first = repo.saveSeries(SeriesEntity(name = "原系列"))
            val second = repo.saveSeries(SeriesEntity(name = "目标系列"))
            val book = dao.insertAnime(AnimeEntity(title = "原作甲", subjectType = "book", seriesId = first, bookReadVolumes = 3, review = "私人感想"))
            val anime = dao.insertAnime(AnimeEntity(title = "异名动画乙", seriesId = first, watchedEpisodes = 6, rating = 80))
            repo.setSeriesRole(setOf(book), first, "原作")
            repo.setSeriesRole(setOf(anime), first, "改编")
            val before = dao.getAnime(anime)!!
            repo.saveAnime(before.copy(title = "改名后", seriesRole = null), emptySet())
            val renamed = requireNotNull(dao.getAnime(anime))
            assertNotNull(renamed.updatedAt)
            assertEquals(before.copy(title = "改名后", updatedAt = renamed.updatedAt), renamed)
            repo.groupDraggedAnimes(mapOf(anime to first), first, "")
            assertEquals("改编", dao.getAnime(anime)?.seriesRole)
            repo.groupDraggedAnimes(mapOf(anime to first), second, "")
            assertEquals(renamed.copy(seriesId = second, seriesRole = null), dao.getAnime(anime))
            try { repo.setSeriesRole(setOf(book, anime), first, "续作"); fail("stale members") } catch (_: IllegalArgumentException) { }
            assertEquals("原作", dao.getAnime(book)?.seriesRole)
            repo.removeSeriesFromAnimes(setOf(book))
            assertNull(dao.getAnime(book)?.seriesRole)
            assertEquals(3, dao.getAnime(book)?.bookReadVolumes)
            assertEquals("私人感想", dao.getAnime(book)?.review)
        } finally { db.close() }
    }

    @Test fun crossMediaSeriesLinksAndPrivateMemosPersistIndependentlyOfBangumiMerge() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).build()
        try {
            val repo = LibraryRepository(db); val dao = db.libraryDao()
            val seriesId = repo.saveSeries(SeriesEntity(name = "跨类型 IP 系列"))
            repo.addSeriesLinkedMedia(
                seriesId,
                listOf(
                    SeriesLinkedMediaItem(kind = "GAME", workSyncId = "game-sync-1", role = "原作"),
                    SeriesLinkedMediaItem(kind = "AUDIO", workSyncId = "audio-sync-1"),
                ),
            )
            // Renaming series without passing linkedMediaJson must preserve linked media
            repo.saveSeries(SeriesEntity(id = seriesId, name = "改名后的 IP 系列", description = "统一整理"))
            val storedSeries = dao.getSeries(seriesId)!!
            assertEquals("改名后的 IP 系列", storedSeries.name)
            assertEquals(
                listOf(
                    SeriesLinkedMediaItem(kind = "GAME", workSyncId = "game-sync-1", role = "原作"),
                    SeriesLinkedMediaItem(kind = "AUDIO", workSyncId = "audio-sync-1", role = null),
                ),
                parseSeriesLinkedMedia(storedSeries.linkedMediaJson),
            )
            repo.setSeriesLinkedMediaRole(seriesId, setOf("AUDIO:audio-sync-1"), "番外／衍生")
            assertEquals("番外／衍生", parseSeriesLinkedMedia(dao.getSeries(seriesId)!!.linkedMediaJson).last().role)
            repo.removeSeriesLinkedMedia(seriesId, setOf("GAME:game-sync-1"))
            assertEquals(listOf("AUDIO:audio-sync-1"), parseSeriesLinkedMedia(dao.getSeries(seriesId)!!.linkedMediaJson).map { it.key })

            val animeId = dao.insertAnime(
                AnimeEntity(title = "改编动画", externalSource = "bangumi", externalId = "1001", review = "公开评价", memo = "网盘第 3 集含特典，二刷从 OVA 起"),
            )
            val merged = mergeBangumiCollection(
                existing = dao.getAnime(animeId)!!,
                item = BangumiCollectionItem(
                    subjectId = 1001L,
                    collectionType = 2,
                    title = "改编动画",
                    originalTitle = "改编动画",
                    watchedEpisodes = 12,
                    totalEpisodes = 12,
                    userRate = 8,
                    comment = "Bangumi 同步短评",
                ),
                now = "2026-11-11T00:00:00Z",
            )
            assertEquals("网盘第 3 集含特典，二刷从 OVA 起", merged.memo)
            repo.updateAnimeMemo(animeId, "  更新后的私密备注  ")
            assertEquals("更新后的私密备注", dao.getAnime(animeId)?.memo)
            repo.updateAnimeMemo(animeId, "   ")
            assertNull(dao.getAnime(animeId)?.memo)
        } finally { db.close() }
    }
}
