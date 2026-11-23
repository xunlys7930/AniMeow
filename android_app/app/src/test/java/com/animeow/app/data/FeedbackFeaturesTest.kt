package com.animeow.app.data

import androidx.room.Room
import com.animeow.app.data.local.*
import com.animeow.app.data.preferences.*
import com.animeow.app.data.remote.*
import com.animeow.app.ui.theme.AppearanceSettings
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class FeedbackFeaturesTest {
    @Test fun visibilityUsesStableIdsAndDoesNotHideUnknownOrRenamedStatusesOrAllEntry() {
        val statuses = listOf(WatchStatusEntity(1, "在看", 1, 0), WatchStatusEntity(2, "自定义", 2, 1))
        assertEquals(listOf("自定义", "未配置状态"), statusOptionNames(statuses, listOf("在看", "未配置状态"), setOf(1)))
        assertEquals(listOf("在看"), statusOptionNames(statuses, emptyList(), setOf(2)))
        assertTrue(visibleWatchStatuses(statuses, setOf(1, 2)).isEmpty())
        assertTrue(visibleWatchStatuses(listOf(statuses[0].copy(name = "已改名")), setOf(1)).isEmpty())
        assertEquals(statuses, visibleWatchStatuses(statuses, setOf(99)))
    }

    @Test fun displaySettingsRoundTripAndOldProfilesKeepDefaults() = runBlocking {
        val tracker = TrackerSettings(hiddenHomeStatusIds = setOf(1, 3), hiddenActionStatusIds = setOf(2),
            progressPlacement = CardProgressPlacement.COVER_BOTTOM)
        val bundle = ConfigurationBundle(AppearanceSettings(), tracker = tracker)
        assertEquals(tracker, ConfigurationBundleCodec.fromJson(ConfigurationBundleCodec.toJson(bundle)).tracker)
        val json = ConfigurationBundleCodec.toJson(bundle)
        json.getJSONObject("tracker").apply { remove("hiddenHomeStatusIds"); remove("hiddenActionStatusIds"); remove("progressPlacement") }
        val old = requireNotNull(ConfigurationBundleCodec.fromJson(json).tracker)
        assertTrue(old.hiddenHomeStatusIds.isEmpty()); assertTrue(old.hiddenActionStatusIds.isEmpty())
        assertEquals(CardProgressPlacement.DEFAULT, old.progressPlacement)
        val preferences = TrackerPreferences(RuntimeEnvironment.getApplication())
        val original = preferences.snapshot()
        try {
            preferences.save(tracker)
            preferences.update { it.copy(infoScale = 1.2f) }
            assertEquals(tracker.hiddenHomeStatusIds, preferences.snapshot().hiddenHomeStatusIds)
            assertEquals(CardProgressPlacement.COVER_BOTTOM, preferences.snapshot().progressPlacement)
        } finally { preferences.save(original) }
        assertEquals(setOf("卡片进度位置", "首页状态栏目", "修改状态菜单"), appearanceChanges(
            bundle.copy(tracker = TrackerSettings()), bundle).map { it.label }.toSet())
    }

    @Test fun batchImportReusesExactRecordsAndPreservesPersonalDataAndSeriesOnConflict() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = LibraryRepository(db); val dao = db.libraryDao()
            val remote = RemoteAnime(RemoteCatalogSource.BANGUMI, "100", "第一季", episodes = 12)
            val first = repo.importBatchItem(remote, "同系列")
            assertTrue(first.second)
            val original = requireNotNull(dao.getAnime(first.first)).copy(watchedEpisodes = 7, status = "在看", rating = 90, review = "保留心得")
            dao.updateAnime(original)
            assertFalse(repo.importBatchItem(remote, "同系列").second)
            assertEquals(original, dao.getAnime(original.id))
            val second = repo.importBatchItem(remote.copy(id = "101", title = "第二季"), "同系列")
            assertEquals(original.seriesId, dao.getAnime(second.first)?.seriesId)
            assertEquals(2, dao.getActiveAnimes().size)
            try { repo.importBatchItem(remote, "其他系列"); fail("must preserve existing series") } catch (_: IllegalArgumentException) { }
            assertNull(dao.getSeriesByName("其他系列"))
            assertEquals(original, dao.getAnime(original.id))
            try { repo.importBatchItem(remote.copy(id = "102"), null); fail("ambiguous same title must be confirmed") } catch (_: IllegalArgumentException) { }
            assertEquals(2, dao.getActiveAnimes().size)
            repo.deleteAnime(original.id)
            try { repo.importBatchItem(remote, null); fail("must not restore deleted item") } catch (_: IllegalArgumentException) { }
        } finally { db.close() }
    }

    @Test fun batchItemTransactionRollsBackFailedGroupingAndRetryDoesNotCreateDuplicates() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = LibraryRepository(db)
            val remote = RemoteAnime(RemoteCatalogSource.BANGUMI, "11", "作品")
            try { repo.importBatchItem(remote, "x".repeat(101)); fail("invalid series") } catch (_: IllegalArgumentException) { }
            assertTrue(db.libraryDao().getActiveAnimes().isEmpty())
            val saved = repo.importBatchItem(remote, null)
            assertEquals(saved.first, repo.importBatchItem(remote, null).first)
            assertEquals(1, db.libraryDao().getActiveAnimes().size)
        } finally { db.close() }
    }

    @Test fun relationTraversalDeduplicatesCyclesAndMarksPartialOrBoundedResults() = runBlocking {
        val first = RemoteAnime(RemoteCatalogSource.BANGUMI, "1", "第一季")
        val second = first.copy(id = "2", title = "第二季")
        val third = first.copy(id = "3", title = "第三季")
        var calls = 0
        val result = collectSeriesCandidates(first) { id, _ -> calls++; when (id) { 1L -> listOf(second); 2L -> listOf(first, third); else -> emptyList() } }
        assertEquals(listOf("1", "2", "3"), result.items.map { it.id }); assertFalse(result.incomplete); assertEquals(3, calls)
        val partial = collectSeriesCandidates(first) { id, _ -> if (id == 1L) listOf(second) else error("offline") }
        assertTrue(partial.incomplete); assertEquals(2, partial.items.size)
        val bounded = collectSeriesCandidates(first) { id, _ -> listOf(first.copy(id = (id + 1).toString())) }
        assertTrue(bounded.incomplete); assertTrue(bounded.items.size <= 60)
        try { collectSeriesCandidates(first.copy(source = RemoteCatalogSource.ANILIST)) { _, _ -> emptyList() }; fail("unsupported origin") } catch (_: IllegalArgumentException) { }
    }
}
