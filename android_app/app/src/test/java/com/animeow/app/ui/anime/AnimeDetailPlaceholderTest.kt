package com.animeow.app.ui.anime

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimeDetailPlaceholderTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun missingAndFailedStatesDrawAtLargeTextAndKeepTheirActions() {
        for (dark in listOf(false, true)) for (missing in listOf(false, true)) {
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
                var backs = 0; var retries = 0
                controller.get().setContent {
                    CompositionLocalProvider(LocalDensity provides Density(2f, 1.4f)) {
                        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                            AnimeDetailPlaceholder(if (missing) AnimeDetailState.Missing else AnimeDetailState.Failed,
                                onBack = { backs++ }, onRetry = { retries++ })
                        }
                    }
                }
                controller.visible(); compose.waitForIdle()
                val root = controller.get().window.decorView
                root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 720, 1520)
                compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
                val bitmap = Bitmap.createBitmap(720, 1520, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File("../../build/android-maintenance-20261103/detail-${if (missing) "missing" else "failed"}-${if (dark) "dark" else "light"}.png")
                    .apply { parentFile?.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                bitmap.recycle()
                compose.onNodeWithText(if (missing) "作品不存在或已移入回收站" else "作品加载失败，请重试").assertIsDisplayed()
                compose.onNodeWithText(if (missing) "返回上一页" else "重新加载").assertIsDisplayed().performClick()
                assertEquals(if (missing) 1 else 0, backs); assertEquals(if (missing) 0 else 1, retries)
                compose.onNodeWithContentDescription("返回").performClick()
                assertEquals(if (missing) 2 else 1, backs)
            }
        }
    }
}
