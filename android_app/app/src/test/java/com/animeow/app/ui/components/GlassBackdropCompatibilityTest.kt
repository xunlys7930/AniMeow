package com.animeow.app.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.animeow.app.ui.theme.GlassQuality
import com.animeow.app.ui.theme.LocalGlassQuality
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28, 30, 31, 36])
class GlassBackdropCompatibilityTest {
    @Test fun lightweightGlassComposesAndDrawsWithoutNewPlatformEffects() = drawGlass(GlassQuality.LOW, withBackdrop = true)

    @Test fun liquidSelectionWithoutBackdropStillDrawsFallback() = drawGlass(GlassQuality.LIQUID)

    @Test
    @Config(sdk = [28, 30])
    fun oldAndroidFallsBackEvenWithLiquidQualityAndBackdrop() = drawGlass(GlassQuality.LIQUID, withBackdrop = true)

    @Test
    @Config(sdk = [36])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun liquidShaderCompilesWithNativeGraphics() {
        assertNotNull(LiquidGlassApi33.createEffect(null, IntSize(480, 128), 5f))
    }

    @Test
    @Config(sdk = [31])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun android12LiquidSelectionUsesItsSupportedBlurPath() =
        drawGlass(GlassQuality.LIQUID, withBackdrop = true, expectsCapture = true)

    private fun drawGlass(quality: GlassQuality, withBackdrop: Boolean = false, expectsCapture: Boolean = false) {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            var composed = false
            var drawn = false
            lateinit var backdrop: GlassBackdrop
            activity.setContent {
                composed = true
                CompositionLocalProvider(LocalGlassQuality provides quality) {
                    backdrop = rememberGlassBackdrop()
                    Box {
                        if (withBackdrop) Box(Modifier.size(240.dp, 64.dp).glassBackdropSource(backdrop))
                        Box(Modifier.size(240.dp, 64.dp)
                            .drawWithContent { drawn = true; drawContent() }
                            .glassBackdropEffect(backdrop.takeIf { withBackdrop }, 0.8f, 22, Color.White))
                    }
                }
            }
            controller.visible()
            val root = activity.window.decorView
            root.measure(
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 480, 320)
            shadowOf(Looper.getMainLooper()).idle()
            val bitmap = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            assertTrue("The glass modifier must actually be composed", composed)
            assertTrue("The glass modifier must actually be drawn", drawn)
            assertEquals("Only supported effect paths may capture the backdrop", expectsCapture, backdrop.ready)
        }
    }
}
