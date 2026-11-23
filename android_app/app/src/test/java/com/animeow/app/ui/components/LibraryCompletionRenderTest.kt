package com.animeow.app.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.ui.screens.BatchMetadataOptionsDialog
import com.animeow.app.ui.screens.BatchMetadataProgressContent
import com.animeow.app.ui.tracker.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryCompletionRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun actualFormsAndResultsDrawWithLargeChineseTextInBothThemes() {
        for (dark in listOf(false, true)) for (scene in listOf("options", "running", "finished", "grouping")) render(scene, dark)
    }

    private fun render(scene: String, dark: Boolean) {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val config = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.3f }
            @Suppress("DEPRECATION") activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        when (scene) {
                            "options" -> {
                                val realWindow = LocalWindowInfo.current
                                val pendingWindow = remember(realWindow) { object : WindowInfo by realWindow {
                                    override val containerSize: IntSize get() = IntSize.Zero
                                } }
                                CompositionLocalProvider(LocalWindowInfo provides pendingWindow) {
                                    BatchMetadataOptionsDialog(12, {}, {})
                                }
                            }
                            "grouping" -> SeriesGroupingHost({ _, _, _ -> }, {}) {
                                val drag = LocalSeriesGroupingDrag.current!!
                                LaunchedEffect(Unit) { drag.pending = AnimeEntity(id = 1, title = "约会大作战") to
                                    LibraryDisplayItem.AnimeItem(AnimeEntity(id = 2, title = "约会大作战 第二季")) }
                            }
                            else -> Surface(Modifier.fillMaxSize()) {
                                val results = BatchMetadataOutcome.entries.filter { it != BatchMetadataOutcome.PENDING }.mapIndexed { index, outcome ->
                                    BatchMetadataResult(index.toLong(), "约会大作战 第${index + 1}季与长标题示例", outcome,
                                        if (outcome == BatchMetadataOutcome.UNMATCHED) "未找到可安全匹配的同一部作品，可在编辑页手动选择" else "Bangumi · ${outcome.label}")
                                } + BatchMetadataResult(8, "待补全的作品")
                                BatchMetadataProgressContent(BatchMetadataState(isRunning = scene == "running", total = 6, completed = 5,
                                    currentTitle = "约会大作战 第二季与长标题示例", currentAnimeId = 8, stage = "正在搜索并核对资料",
                                    results = results, message = "已停止，已保存的资料保留"), {}, {}, {}, {})
                            }
                        }
                    }
                }
            }
            controller.visible(); compose.waitForIdle()
            val activityRoot = activity.window.decorView
            activityRoot.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY))
            activityRoot.layout(0, 0, 720, 1520)
            compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            val dialog = if (scene in listOf("options", "grouping")) requireNotNull(ShadowDialog.getLatestDialog()) else null
            if (dialog != null) assertTrue("$scene dialog must be showing", dialog.isShowing)
            val root = dialog?.window?.decorView ?: activityRoot
            root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 720, 1520)
            compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            val bitmap = Bitmap.createBitmap(720, 1520, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val pixels = IntArray(720 * 1520); bitmap.getPixels(pixels, 0, 720, 0, 0, 720, 1520)
            val file = File("../../build/home-completion-2.2.1/$scene-${if (dark) "dark" else "light"}.png")
            file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            assertTrue("$scene / dark=$dark must contain visible controls", pixels.toSet().size > 20)
            dialog?.dismiss()
        }
    }
}
