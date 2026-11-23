package com.animeow.app.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.animeow.app.data.local.TagEntity
import com.animeow.app.ui.anime.TagsCard
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
@Config(manifest = Config.NONE, application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpandableTagsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val names = listOf("日常", "治愈", "冒险", "轻小说改编", "合成的很长中文标签也应完整显示并自动换行", "科幻", "已补完", "周末再看", "朋友推荐", "合成末尾标签")
    private val tags = names.mapIndexed { index, name -> TagEntity(id = index.toLong() + 1, name = name) }

    @Test fun detailExpandsWrapsAndKeepsTagNavigationAndEditEntry() {
        for (dark in listOf(false, true)) {
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
                var opened = 0L
                var edited = 0
                controller.get().setContent {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        CompositionLocalProvider(LocalDensity provides Density(2f, 1.5f)) {
                            Surface(Modifier.fillMaxSize()) { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                                TagsCard(42, tags, { opened = it }, { edited++ })
                            } }
                        }
                    }
                }
                controller.visible()
                compose.onNodeWithText("合成末尾标签").assertDoesNotExist()
                compose.onNodeWithText("日常").assertHasClickAction().assertHeightIsAtLeast(48.dp)
                compose.onNodeWithText("展开全部标签（10）").performClick()
                compose.onNodeWithText("合成末尾标签").assertExists()
                val first = compose.onNodeWithText("日常").fetchSemanticsNode().boundsInRoot
                val last = compose.onNodeWithText("合成末尾标签").fetchSemanticsNode().boundsInRoot
                assertTrue(last.top > first.top)
                val long = compose.onNodeWithText(names[4]).fetchSemanticsNode().boundsInRoot
                assertTrue(long.left >= 0 && long.right <= 720)
                draw(controller.get(), if (dark) "tags-expanded-dark" else "tags-expanded-light")
                compose.onNodeWithText("合成末尾标签").performScrollTo().performClick()
                assertEquals(10L, opened)
                compose.onNodeWithText("收起标签").performScrollTo().performClick()
                compose.onNodeWithText("展开全部标签（10）").assertExists()
                compose.onNodeWithText("编辑", substring = false).performScrollTo().performClick()
                assertEquals(1, edited)
            }
        }
    }

    @Test fun editorSelectionSurvivesExpansionAndCollapse() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            var selected by mutableStateOf(setOf(1L))
            controller.get().setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ExpandableTags(tags, { id -> selected = if (id in selected) selected - id else selected + id }, selected)
                }
            } } }
            controller.visible()
            compose.onNodeWithText("日常").assertIsSelected()
            compose.onNodeWithText("展开全部标签（10）").performClick()
            compose.onNodeWithText("合成末尾标签").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText("收起标签").performScrollTo().performClick()
            assertEquals(setOf(1L, 10L), selected)
            compose.onNodeWithText("展开全部标签（10）").performClick()
            compose.onNodeWithText("合成末尾标签").assertIsSelected()
            compose.onNodeWithText("日常").performScrollTo().performClick().assertIsNotSelected()
            assertEquals(setOf(10L), selected)
        }
    }

    @Test fun emptyCardKeepsEditingAndChangingWorksResetsExpansion() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            var id by mutableLongStateOf(1L)
            controller.get().setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                TagsCard(id, if (id == 3L) emptyList() else tags, {}, { id++ })
            } } }
            controller.visible()
            compose.onNodeWithText("展开全部标签（10）").performClick()
            compose.onNodeWithText("编辑").performScrollTo().performClick()
            compose.onNodeWithText("展开全部标签（10）").assertExists()
            compose.onNodeWithText("编辑").performClick()
            compose.onNodeWithText("还没有标签").assertExists()
            compose.onNodeWithText("展开全部标签（10）").assertDoesNotExist()
        }
    }

    private fun draw(activity: ComponentActivity, name: String) {
        compose.waitForIdle()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 720, 1520); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val bitmap = Bitmap.createBitmap(720, 1520, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../../build/afdian-thanks/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
