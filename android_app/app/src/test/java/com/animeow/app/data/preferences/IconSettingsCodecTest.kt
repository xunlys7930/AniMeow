package com.animeow.app.data.preferences

import com.animeow.app.ui.icons.AppIconId
import com.animeow.app.ui.icons.IconOverride
import com.animeow.app.ui.icons.IconSettings
import com.animeow.app.ui.icons.IconStyle
import com.animeow.app.ui.icons.IconVariant
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.ThemeMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class IconSettingsCodecTest {
    private val custom = IconSettings(
        style = IconStyle.SOFT, variant = IconVariant.B, animated = false,
        overrides = mapOf(
            AppIconId.SEARCH to IconOverride(IconStyle.DUO, IconVariant.A),
            AppIconId.CALENDAR to IconOverride(variant = IconVariant.A),
        ),
    )

    @Test
    fun roundTripsThroughAppearanceAndPortableProfiles() {
        val source = ConfigurationBundle(AppearanceSettings(icons = custom, themeMode = ThemeMode.DARK))
        val json = ConfigurationBundleCodec.toJson(source)
        val restored = ConfigurationBundleCodec.fromJson(JSONObject(json.toString()))
        assertEquals(source, restored)
        val serialized = json.getJSONObject("appearance").getJSONObject("icons")
        assertTrue(serialized.getJSONObject("overrides").has("search"))
        assertFalse(serialized.toString().contains("drawable"))
        assertFalse(serialized.toString().contains("R."))
    }

    @Test
    fun olderProfilesReceiveTheDefaultIconSuite() {
        val restored = AppearanceSettingsCodec.fromJson(JSONObject("""{"schemaVersion":14,"themeMode":"dark"}"""))
        assertEquals(IconSettings(), restored.icons)
        assertEquals(ThemeMode.DARK, restored.themeMode)
    }

    @Test
    fun malformedAndUnknownChoicesFallBackWithoutDroppingValidEntries() {
        val restored = IconSettingsCodec.fromJson(JSONObject("""
            {"style":"future","variant":"z","animated":false,"overrides":{
              "search":{"style":"duo","variant":"b"},
              "calendar":{"style":"future","variant":"b"},
              "heart":{"style":null,"variant":"bad"},
              "profile":"not an object",
              "../../file":{"style":"soft","variant":"a"}
            }}
        """))
        assertEquals(IconStyle.LINE, restored.style)
        assertEquals(IconVariant.A, restored.variant)
        assertFalse(restored.animated)
        assertEquals(setOf(AppIconId.SEARCH, AppIconId.CALENDAR), restored.overrides.keys)
        assertEquals(IconOverride(variant = IconVariant.B), restored.overrides[AppIconId.CALENDAR])
    }

    @Test
    fun damagedOrOversizedStoredSettingsAreSafeToRead() {
        assertEquals(IconSettings(), IconSettingsCodec.fromStorage("{broken"))
        assertEquals(IconSettings(), IconSettingsCodec.fromStorage(" ".repeat(70_000)))
        assertEquals(custom, IconSettingsCodec.fromStorage(IconSettingsCodec.toJson(custom).toString()))
    }

    @Test
    fun iconCategoryResetPreservesOtherAppearanceAndReportsChanges() {
        val source = ConfigurationBundle(AppearanceSettings(icons = custom, themeMode = ThemeMode.DARK, gridColumns = 5))
        val restored = resetAppearanceCategory(source, "icons")
        assertEquals(IconSettings(), restored.appearance.icons)
        assertEquals(ThemeMode.DARK, restored.appearance.themeMode)
        assertEquals(5, restored.appearance.gridColumns)
        val changes = appearanceChanges(source, restored)
        assertEquals(listOf("icons"), changes.map { it.category })
        assertTrue(changes.single().before.contains("单独设置"))
    }
}
