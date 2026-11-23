package com.animeow.app.ui.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.animeow.app.data.preferences.AppearancePreferences
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.AccentGradientSettings
import com.animeow.app.ui.theme.AccentMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IconVectorDrawableTest {
    @Test
    fun everyXmlDesignInflatesAndRendersInsideItsViewport() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resources = mutableSetOf<Int>()
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(96 * 96)
        for (id in AppIconId.entries) for (style in IconStyle.entries) for (variant in IconVariant.entries) {
            for (active in if (id.supportsActive) listOf(false, true) else listOf(false)) {
                val resource = IconDrawables.resource(id, style, variant, active)
                val name = id.storageKey + "/" + style.storageKey + "/" + variant.storageKey + "/" + active
                assertTrue("Each choice needs its own resource: " + name, resources.add(resource))
                val drawable = requireNotNull(context.getDrawable(resource)).mutate()
                drawable.setTint(Color.BLACK)
                drawable.setBounds(0, 0, 96, 96)
                bitmap.eraseColor(Color.TRANSPARENT)
                drawable.draw(Canvas(bitmap))
                bitmap.getPixels(pixels, 0, 96, 0, 0, 96, 96)
                assertTrue("Empty vector: " + name, pixels.any { Color.alpha(it) > 32 })
                for (edge in 0 until 96) {
                    assertTrue("Clipped vector: " + name,
                        Color.alpha(pixels[edge]) == 0 && Color.alpha(pixels[95 * 96 + edge]) == 0 &&
                            Color.alpha(pixels[edge * 96]) == 0 && Color.alpha(pixels[edge * 96 + 95]) == 0)
                }
            }
        }
        bitmap.recycle()
    }

    @Test
    fun appearancePersistenceRestoresGlobalAndIndividualIconChoices() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = AppearancePreferences(context)
        val before = preferences.snapshot()
        val icons = IconSettings(IconStyle.DUO, IconVariant.B, false,
            mapOf(AppIconId.SEARCH to IconOverride(variant = IconVariant.A,
                color = IconColorSettings(IconColorMode.CUSTOM, 0xFF8E74B8L))),
            color = IconColorSettings(IconColorMode.ACCENT))
        val gradient = AccentGradientSettings(AccentMode.FLOW, listOf(0xFF8E74B8L, 0xFFBD778FL), 145f, 16_000)
        try {
            preferences.restore(AppearanceSettings(icons = icons, accentGradient = gradient))
            val restored = AppearancePreferences(context).snapshot()
            assertEquals(icons, restored.icons)
            assertEquals(gradient, restored.accentGradient)
            preferences.restore(AppearanceSettings())
            assertEquals(IconSettings(), preferences.snapshot().icons)
            assertEquals(AccentGradientSettings(), preferences.snapshot().accentGradient)
        } finally {
            preferences.restore(before)
        }
    }
}
