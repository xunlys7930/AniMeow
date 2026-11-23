package com.animeow.app.data.preferences

import android.app.Application
import com.animeow.app.ui.theme.AppearanceSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class CollectionFilterPlansTest {
    private val conditions = CollectionFilterConditions("长标题", "在看", "anime", listOf("治愈", "日常"), true, listOf("2026"), "rating", true).normalized()
    @Test fun conditionsRoundTripByNameAndMissingReferencesNeverBroadenToAll() {
        val row = CollectionFilterPlan("one", "在看的日常", "library", conditions)
        assertEquals(listOf(row), collectionFilterPlansFromJson(collectionFilterPlansToJson(listOf(row))))
        assertEquals(listOf("标签「治愈」"), conditions.missingConditions(listOf("日常", "另一台设备编号相同的其他标签"), listOf("在看")))
        assertTrue(conditions.missingConditions(listOf("治愈", "日常"), listOf("已看")).contains("状态「在看」"))
        assertTrue(conditions.missingConditions(listOf("治愈", "日常"), listOf("在看")).isEmpty())
    }
    @Test fun storeReopensUpdatesRejectsDuplicateNamesAndMergesWithoutDeletingLocalPlans() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = CollectionFilterPreferences(context)
        store.restore(JSONArray())
        val row = CollectionFilterPlan("one", "日常", "library", conditions)
        store.save(row)
        assertEquals(listOf(row), CollectionFilterPreferences(context).plans.first())
        try { store.save(row.copy(id = "two")); fail("duplicate names must not overwrite") } catch (_: IllegalArgumentException) { }
        assertEquals(listOf(row), store.plans.first())
        store.restore(collectionFilterPlansToJson(listOf(row.copy(conditions = conditions.copy(query = "远端")), row.copy(id = "game", name = "想玩", module = "game", conditions = CollectionFilterConditions(status = "想玩")))), merge = true)
        assertEquals(2, store.plans.first().size)
        assertEquals(conditions, store.plans.first().first { it.id == "one" }.conditions)
        store.save(row.copy(name = "新名称", conditions = conditions.copy(query = "更新后的查询")))
        assertEquals("更新后的查询", store.plans.first().first { it.id == "one" }.conditions.query)
        store.delete("one")
        assertEquals(listOf("game"), store.plans.first().map { it.id })
    }
    @Test fun unsupportedGameConditionsAreRejectedInsteadOfBeingSilentlyDropped() {
        val plan = CollectionFilterPlan("invalid", "游戏条件", "game", CollectionFilterConditions(tags = listOf("剧情", "日常")))
        assertThrows(IllegalArgumentException::class.java) { collectionFilterPlansFromJson(collectionFilterPlansToJson(listOf(plan))) }
    }
    @Test fun malformedRestoreCannotReplaceSavedPlansAndDuplicateImportedNamesAreRejected() = runBlocking {
        val store = CollectionFilterPreferences(RuntimeEnvironment.getApplication())
        val plan = CollectionFilterPlan("keep", "保留方案", "library", conditions)
        store.restore(collectionFilterPlansToJson(listOf(plan)))
        val invalid = collectionFilterPlansToJson(listOf(plan.copy(id = "a"), plan.copy(id = "b")))
        try { store.restore(invalid); fail("duplicate imported names must be rejected") } catch (_: IllegalArgumentException) { }
        assertEquals(listOf(plan), store.plans.first())
    }
    @Test fun publicAppearanceBundlesContainOnlyModuleVisibilityAndNeverPrivatePlans() {
        val settings = TrackerSettings(showRecentPlayback = false, homeModuleOrder = listOf(HomeModule.PLAYBACK, HomeModule.CALENDAR))
        val json = ConfigurationBundleCodec.toJson(ConfigurationBundle(AppearanceSettings(), tracker = settings))
        assertFalse(json.toString().contains("collectionFilters"))
        assertFalse(ConfigurationBundleCodec.fromJson(json).tracker!!.showRecentPlayback)
        assertEquals(HomeModule.PLAYBACK, ConfigurationBundleCodec.fromJson(json).tracker!!.homeModuleOrder.first())
        assertTrue(ConfigurationBundleCodec.fromJson(org.json.JSONObject("{\"tracker\":{}}")).tracker!!.showRecentPlayback)
    }
}
