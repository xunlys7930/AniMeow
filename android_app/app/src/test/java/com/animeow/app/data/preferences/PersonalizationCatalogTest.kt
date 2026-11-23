package com.animeow.app.data.preferences

import android.app.Application
import androidx.datastore.preferences.core.Preferences
import com.animeow.app.ui.preferences.customizationSection
import com.animeow.app.ui.preferences.searchCustomizationTargets
import com.animeow.app.ui.theme.*
import com.animeow.app.ui.icons.IconSettings
import java.lang.reflect.Modifier
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class PersonalizationCatalogTest {
    private fun fields(type: Class<*>) = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
    private fun keys(json: JSONObject): Set<String> = json.keys().asSequence().toSet()

    @Test fun everyModelFieldHasExactlyOnePolicyAndBothCodecsCoverAllFields() {
        for ((type, definitions, json) in listOf(
            Triple(AppearanceSettings::class.java, appearanceFields, AppearanceSettingsCodec.toJson(AppearanceSettings())),
            Triple(TrackerSettings::class.java, trackerFields, TrackerSettingsCodec.toJson(TrackerSettings())),
        )) {
            val expected = fields(type).map { it.name }.toSet()
            assertEquals(type.simpleName, expected, definitions.map { it.key }.toSet())
            assertEquals(definitions.size, definitions.map { it.key }.distinct().size)
            assertEquals(expected, keys(json) - "schemaVersion")
            val storageKeys = definitions.flatMap { listOf(it.storageKey) + it.additionalStorageKeys }
            assertEquals(storageKeys.size, storageKeys.distinct().size)
            definitions.forEach { field ->
                assertTrue(field.key, field.label.isNotBlank() && field.entryPoint.isNotBlank())
                if (!field.resettable) assertFalse(field.key, field.preservationReason.isNullOrBlank())
                field.searchSection?.let { section -> assertNotNull(field.key, customizationSection(section)) }
            }
        }
    }

    @Test fun registeredStorageKeysMatchTheExistingStoresAndSearchFindsRegisteredSettings() {
        for ((type, definitions) in listOf(AppearancePreferences.Keys::class.java to appearanceFields, TrackerPreferences.Keys::class.java to trackerFields)) {
            val names = type.declaredFields.filter { Preferences.Key::class.java.isAssignableFrom(it.type) }.map {
                it.isAccessible = true; (it.get(null) as Preferences.Key<*>).name
            }.toSet()
            assertEquals(type.name, names, definitions.flatMap { listOf(it.storageKey) + it.additionalStorageKeys }.map { it.name }.toSet())
        }
        personalizationFields.values.flatten().filter { it.searchSection != null }.forEach { field ->
            assertTrue(field.key, searchCustomizationTargets(field.label).any { it.section == field.searchSection })
        }
    }

    @Test fun pageSettingsAreComparedWhileCategoryResetKeepsTheirIndependentValues() {
        val before = ConfigurationBundle(AppearanceSettings(), TrackerSettings())
        val after = before.copy(appearance = before.appearance.copy(characterShowRating = false,
            statisticsLayoutStyle = StatisticsLayoutStyle.DASHBOARD, detailModuleOrder = DetailModule.entries.reversed(),
            frontendModeChoiceMade = true), tracker = TrackerSettings(coverProgressAlpha = 0.8f))
        val changes = appearanceChanges(before, after)
        assertTrue(changes.any { it.category == "character" && it.label == "角色评分显示" })
        assertTrue(changes.any { it.label == "统计布局" && it.after == "档案仪表盘" })
        assertTrue(changes.any { it.label == "详情模块顺序" })
        assertTrue(changes.any { it.label == "封面进度不透明度" && it.after == "80%" })
        assertFalse(changes.any { it.label == "首次界面选择标记" })
        assertTrue(appearanceChanges(before, before.copy(appearance = before.appearance.copy(frontendModeChoiceMade = true))).isEmpty())
        for (category in appearanceSections.keys + trackerSections.keys) {
            val reset = resetAppearanceCategory(after, category)
            assertFalse(reset.appearance.characterShowRating)
            assertEquals(after.appearance.detailModuleOrder, reset.appearance.detailModuleOrder)
            assertEquals(after.appearance.statisticsLayoutStyle, reset.appearance.statisticsLayoutStyle)
            assertTrue(reset.appearance.frontendModeChoiceMade)
            assertEquals(0.8f, reset.tracker!!.coverProgressAlpha, 0.0001f)
        }
    }

    @Test fun optionalSectionsAreComparedWithoutShowingPrivateValuesOrResettingAbsentSections() {
        val before = ConfigurationBundle(AppearanceSettings(), discovery = DiscoveryNetworkSettings())
        val after = before.copy(discovery = DiscoveryNetworkSettings(aniListProxyUrl = "https://private.test/token"))
        val changes = appearanceChanges(before, after)
        assertTrue(changes.any { it.category == "discovery" })
        assertFalse(changes.any { "private.test" in it.after || "token" in it.after })
        assertTrue(appearanceChanges(before, ConfigurationBundle(AppearanceSettings())).isEmpty())
    }

    @Test fun spacingSurvivesProfilesAndUnrelatedCategoryResetAndLegacyDefaultsStillWork() {
        val source = ConfigurationBundle(AppearanceSettings(sectionSpacing = SectionSpacing.RELAXED, fontScale = 1.2f))
        val restored = ConfigurationBundleCodec.fromJson(ConfigurationBundleCodec.toJson(source))
        assertEquals(SectionSpacing.RELAXED, restored.appearance.sectionSpacing)
        val reset = resetAppearanceCategory(source, "size")
        assertEquals(1f, reset.appearance.fontScale, .0001f)
        assertEquals(SectionSpacing.RELAXED, reset.appearance.sectionSpacing)
        assertEquals(SectionSpacing.COMPACT, AppearanceSettingsCodec.fromJson(JSONObject()).sectionSpacing)
    }

    @Test fun everySettingSurvivesStoreWritesFreshReadersAndProfileRoundTrip() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val appearance = AppearancePreferences(context); val tracker = TrackerPreferences(context)
        val originalAppearance = appearance.snapshot(); val originalTracker = tracker.snapshot()
        try {
            val chosenAppearance = AppearanceSettingsCodec.fromJson(AppearanceSettingsCodec.toJson(alternate(AppearanceSettings())))
            val chosenTracker = alternate(TrackerSettings()).normalized()
            appearance.restore(chosenAppearance); tracker.save(chosenTracker)
            assertEquals(chosenAppearance, AppearancePreferences(context).snapshot())
            assertEquals(chosenTracker, TrackerPreferences(context).snapshot())
            val bundle = ConfigurationBundle(chosenAppearance, chosenTracker)
            assertEquals(bundle, ConfigurationBundleCodec.fromJson(ConfigurationBundleCodec.toJson(bundle)))
        } finally { appearance.restore(originalAppearance); tracker.save(originalTracker) }
    }

    // Synthetic fixtures only: vary each constructor field, so a new unsupported type fails explicitly.
    private fun <T : Any> alternate(value: T): T {
        fields(value.javaClass).forEach { field ->
            field.isAccessible = true
            val current = field.get(value)
            val next: Any = when (current) {
                is Boolean -> !current
                is Enum<*> -> requireNotNull(field.type.enumConstants).firstOrNull { it != current } ?: current
                is Float -> if (field.name in setOf("fontScale", "cornerScale", "infoScale")) 1.2f else 0.65f
                is Int -> if (field.name == "tierListCount") 4 else current + 1
                is Long -> 0xFF456789L
                is String -> when (field.name) {
                    "customFontId" -> "a".repeat(64)
                    "lastSubjectType" -> "book"
                    "lastSortKey" -> "rating"
                    "defaultStartStatus", "lastSelectedStatus" -> "想看"
                    "startDestination" -> "profile"
                    else -> "合成设置"
                }
                is List<*> -> if (field.name == "tierDisplayLabels") listOf("甲", "乙", "丙", "丁", "戊") else current.reversed()
                is Set<*> -> when (field.name) {
                    "hiddenDetailModules" -> setOf(DetailModule.REMINDER)
                    "hiddenStatisticsModules" -> setOf(StatisticsModule.ANALYSIS)
                    "hiddenStatisticsMetrics" -> setOf(StatisticsMetric.STREAK)
                    else -> setOf(2L, 9L)
                }
                is Map<*, *> -> mapOf("anime" to ContentDensity.COMPACT)
                is IconSettings -> current.copy(animated = !current.animated)
                is AccentGradientSettings -> current.copy(angleDegrees = 75f)
                null -> if (field.type == String::class.java) {
                    if (field.name == "customFontId") "a".repeat(64) else "/synthetic/wallpaper.png"
                } else 0xFF456789L
                else -> error("No synthetic value for ${field.name}: ${field.type}")
            }
            field.set(value, next)
        }
        return value
    }
}
