package com.animeow.app.data.preferences

import com.animeow.app.ui.theme.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class DetailAppearanceCompatibilityTest {
    @Test fun oldProfilesKeepBothAffordancesAndTheirExistingLayout() {
        listOf("classic", "dashboard", "magazine", "minimal").forEach { value ->
            val settings = AppearanceSettingsCodec.fromJson(JSONObject().put("schemaVersion", 15).put("detailLayout", value))
            assertEquals(value, settings.detailLayout.storageKey)
            assertTrue(settings.showSeriesEditButton)
            assertTrue(settings.showPinBadges)
            assertEquals(DetailProgressMode.SEPARATE, settings.detailProgressMode)
        }
    }

    @Test fun sharedProfileRoundTripKeepsNewLayoutHiddenIconsAndModuleOrder() {
        val settings = AppearanceSettings(detailLayout = DetailLayout.THEATER, showSeriesEditButton = false, showPinBadges = false,
            detailModuleOrder = DetailModule.entries.reversed(), hiddenDetailModules = setOf(DetailModule.REMINDER),
            detailCardStyle = DetailCardStyle.OUTLINED, detailProgressMode = DetailProgressMode.COMBINED)
        val restored = AppearanceSettingsCodec.fromJson(AppearanceSettingsCodec.toJson(settings))
        assertEquals(settings, restored)
        val bundle = ConfigurationBundle(settings)
        assertEquals(bundle, ConfigurationBundleCodec.fromJson(ConfigurationBundleCodec.toJson(bundle)))
    }

    @Test fun redundantSearchChoiceMigratesToTheEquivalentVisibleOption() {
        assertEquals(ProfileSearchStyle.INLINE, ProfileSearchStyle.fromStorage("both"))
        assertFalse(ProfileSearchStyle.selectable.contains(ProfileSearchStyle.BOTH))
        assertEquals(ProfileSearchStyle.INLINE, AppearanceSettingsCodec.fromJson(JSONObject().put("profileSearchStyle", "both")).profileSearchStyle)
    }

    @Test fun oldAndFutureProgressValuesKeepTheFamiliarDefault() {
        listOf(JSONObject().put("schemaVersion", 16), JSONObject().put("detailProgressMode", "unknown-future-mode")).forEach {
            assertEquals(DetailProgressMode.SEPARATE, AppearanceSettingsCodec.fromJson(it).detailProgressMode)
        }
    }

    @Test fun suitesAndVisualResetsPreserveInteractionChoiceAndOnlyBehaviorResetChangesIt() {
        val chosen = AppearanceSettings(detailProgressMode = DetailProgressMode.COMBINED, fontScale = 1.2f)
        AppearanceSuite.entries.forEach { assertEquals(DetailProgressMode.COMBINED, it.apply(chosen).detailProgressMode) }
        val bundle = ConfigurationBundle(chosen)
        assertEquals(DetailProgressMode.COMBINED, resetAppearanceCategory(bundle, "visual").appearance.detailProgressMode)
        assertEquals(DetailProgressMode.COMBINED, resetAppearanceCategory(bundle, "layout").appearance.detailProgressMode)
        val reset = resetAppearanceCategory(bundle, "behavior")
        assertEquals(DetailProgressMode.SEPARATE, reset.appearance.detailProgressMode)
        assertEquals(chosen.fontScale, reset.appearance.fontScale)
        assertTrue(appearanceChanges(reset, bundle).any { it.category == "behavior" && it.label == "详情进度操作" && it.after == "合并操作" })
    }
}
