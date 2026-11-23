package com.animeow.app.ui.anime

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import com.animeow.app.data.remote.*
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
class BangumiExtrasRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun panelsDrawWithLargeFontLightDarkAndLandscape() {
        for (dark in listOf(false, true)) for (tab in 0..3) render(tab, dark, false)
        render(2, false, true); render(1, true, true)
    }
    private fun render(tab: Int, dark: Boolean, landscape: Boolean) {
        val width = if (landscape) 1520 else 720; val height = if (landscape) 720 else 1520
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val config = android.content.res.Configuration(activity.resources.configuration).apply {
                fontScale = 1.3f; screenWidthDp = width / 2; screenHeightDp = height / 2
                orientation = if (landscape) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
            }
            @Suppress("DEPRECATION") activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            val comment = BangumiComment(1, "示例读者 · 长名称测试", if (tab == 3) "长评标题与来源摘要" else "", "这是合成的界面检查文字。".repeat(40), if (tab == 2) 9 else null, 1792771200, "https://bgm.tv/subject/253/comments")
            val state = BangumiExtrasUiState(error = if (tab == 2) "暂时无法更新，保留已读取内容" else null,
                rating = RemoteAnime(RemoteCatalogSource.BANGUMI, "253", "作品", score = 8.5, ratingCount = 12345, sourceRank = 1),
                staff = BangumiStaffPage(listOf(BangumiPerson(1, "示例制作人员长名称", "导演／分镜／演出", "第 1、3、12 集")), true, null),
                discussions = mapOf((if (tab == 2) "comments" else "reviews") to BangumiDiscussionPage(listOf(comment), 41, 2, true, true, null)))
            activity.setContent { CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.fillMaxSize()) { BangumiExtrasContent(253, "作品资料与较长标题示例", tab, state, {}, {}, {}) }
                }
            } }
            controller.visible(); compose.waitForIdle()
            val root = activity.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height); compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val pixels = IntArray(width * height); bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            assertTrue("panels must draw visible text and controls", pixels.toSet().size > 20)
            val file = File("../../build/feedback-completion-2026-10-24/previews/tab-$tab-${if (dark) "dark" else "light"}-${if (landscape) "landscape" else "portrait"}.png")
            file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
