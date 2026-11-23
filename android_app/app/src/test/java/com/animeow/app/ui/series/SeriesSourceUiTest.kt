package com.animeow.app.ui.series

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.remote.SeriesSourceWork
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
@Config(application = Application::class, sdk = [36], qualifiers = "w400dp-h860dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeriesSourceUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun mixedSourceCandidatesRequireConfirmationAndKeepUnsupportedWorksReadOnly() {
        var saved = 0
        var value by mutableStateOf(SeriesSourceState(items = listOf(
            SeriesSourceWork("1", "异名原作小说", 1, "原作"), SeriesSourceWork("2", "真人电影", 6, "真人")), loaded = true, origin = "来源：合成动画的直接关联"))
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { host ->
            host.get().setContent { CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                MaterialTheme { SeriesSourceSheet(value, emptyList(), emptyList(), {}, {},
                    { id -> value = value.copy(selected = setOf(id)) }, { saved++ }, {}, {}) }
            } }
            host.visible(); compose.waitForIdle()
            compose.onNodeWithTag("series-source-list").performScrollToNode(hasContentDescription("选择异名原作小说"))
            compose.onNodeWithContentDescription("选择异名原作小说").performClick()
            compose.onNodeWithTag("series-source-list").performScrollToNode(hasText("加入所选 1 部"))
            draw("series-source-large")
            compose.onNodeWithText("加入所选 1 部").performClick()
            assertEquals(0, saved)
            compose.onNodeWithText("加入当前系列？").assertIsDisplayed()
            compose.onAllNodesWithText("取消").onLast().performClick()
            assertEquals(0, saved)
            compose.onNodeWithText("加入所选 1 部").performClick()
            compose.onNodeWithText("确认加入").performClick()
            assertEquals(1, saved)
        }
    }
    @Test fun movingAnExistingMemberRequiresConfirmationAndFailureKeepsItsSelection() {
        var saves = 0
        val member = AnimeEntity(id = 7, title = "异名作品", subjectType = "book", seriesId = 2)
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { host ->
            host.get().setContent { MaterialTheme {
                AddSeriesWorksSheet(listOf(member), mapOf(2L to "原系列"), {
                    saves++; assertEquals(mapOf(7L to 2L), it); throw IllegalArgumentException("归属已变化，请重新选择")
                }, {})
            } }
            host.visible(); compose.waitForIdle()
            compose.onNodeWithText("异名作品").performClick()
            compose.onNodeWithText("添加 1 部").performClick()
            compose.onNodeWithText("确认移入当前系列？").assertIsDisplayed()
            assertEquals(0, saves)
            compose.onAllNodesWithText("取消").onLast().performClick()
            compose.onNodeWithText("添加 1 部").performClick()
            compose.onNodeWithText("确认移入").performClick()
            compose.waitForIdle()
            assertEquals(1, saves)
            compose.onNodeWithText("归属已变化，请重新选择").assertExists()
            compose.onNodeWithText("添加 1 部").assertIsEnabled()
        }
    }

    @Test @Config(qualifiers = "w800dp-h375dp-land-xhdpi")
    fun errorsAndSavingKeepDraftVisibleAndDoNotOfferDuplicateSubmit() {
        val value = SeriesSourceState(items = listOf(SeriesSourceWork("1", "异名原作小说", 1, "原作")),
            selected = setOf("1"), loaded = true, saving = true, error = "保存结果待核实，保留选择")
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { host ->
            host.get().setContent { MaterialTheme(colorScheme = darkColorScheme()) {
                SeriesSourceSheet(value, emptyList(), emptyList(), {}, {}, {}, {}, {}, {})
            } }
            host.visible(); compose.waitForIdle()
            compose.onNodeWithTag("series-source-list").performScrollToNode(hasText("正在保存…"))
            compose.onNodeWithText("正在保存…").assertIsNotEnabled()
            compose.onNodeWithText("关闭").assertIsNotEnabled()
            draw("series-source-saving-landscape")
        }
    }
    private fun draw(name: String) {
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        val roots = android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.width > 0 }
        assertTrue(roots.isNotEmpty())
        roots.forEachIndexed { index, root ->
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File("../../build/series-223-20261106/$name-$index.png")
            file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
