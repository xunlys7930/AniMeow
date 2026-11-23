package com.animeow.app.ui.series

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.animeow.app.data.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaSeriesRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun actualSeriesPagesDrawInBothThemesLargeTextAndLandscape() {
        for (dark in listOf(false, true)) {
            render(dark, "list", 360, 760)
            render(dark, "edit", 360, 760)
            render(dark, "error", 360, 760)
            render(dark, "edit", 760, 360)
        }
    }
    private fun render(dark: Boolean, scene: String, width: Int, height: Int) {
        RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-${if (width > height) "land" else "port"}-xhdpi")
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val config = android.content.res.Configuration(activity.resources.configuration).apply {
                fontScale = 1.3f; screenWidthDp = width; screenHeightDp = height
                orientation = if (width > height) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
            }
            @Suppress("DEPRECATION") activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            val series = MediaSeriesEntity(id = 1, kind = "GAME", name = "用于检查长名称与大字体的示例系列")
            val works = listOf(MediaSeriesWork("one", "系列第一部：用于检查换行与按钮避让的长标题", "v1"),
                MediaSeriesWork("two", "系列第二部", "v2"), MediaSeriesWork("three", "尚未加入的游戏", "v3"))
            val members = listOf(MediaSeriesMember(id = 1, kind = "GAME", workSyncId = "one", seriesId = 1, sortOrder = 0),
                MediaSeriesMember(id = 2, kind = "GAME", workSyncId = "two", seriesId = 1, sortOrder = 1))
            activity.setContent { CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface { MediaSeriesContent(MediaSeriesKind.GAME, MediaSeriesSnapshot(listOf(series), members), works,
                        scene != "list", series.name, listOf("one", "two"), "", false,
                        if (scene == "error") "系列归属已变化，请重新打开。当前草稿仍保留。" else null,
                        true, {}, {}, {}, {}, {}, {}, {}) }
                }
            } }
            controller.visible(); compose.waitForIdle()
            val root = activity.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height * 2, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width * 2, height * 2)
            compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            val bitmap = Bitmap.createBitmap(width * 2, height * 2, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val pixels = IntArray(width * height * 4); bitmap.getPixels(pixels, 0, width * 2, 0, 0, width * 2, height * 2)
            val file = File("../../build/media-series-2.2.1/$scene-${if (dark) "dark" else "light"}-${width}x$height.png")
            file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            assertTrue("Series page must contain actual visible controls", pixels.toSet().size > 20)
            assertTrue("Surface must fill the measured width", (pixels[height * width * 2 + width * 2 - 10] ushr 24) > 0)
        }
        RuntimeEnvironment.setQualifiers("w360dp-h760dp-port-xhdpi")
    }
}
