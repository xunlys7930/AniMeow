package com.animeow.app.ui.series

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.animeow.app.data.MediaSeriesWork
import com.animeow.app.data.remote.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class SourceSeriesPickerTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun confirmedSourceChoicesOnlyCreateADraft() {
        val works = listOf(MediaSeriesWork("a", "合成作品甲", "now", "dlsite", "RJ123456"), MediaSeriesWork("b", "合成作品乙", "now", "dlsite", "RJ123457"))
        var draft: Pair<String, List<String>>? = null
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent { MaterialTheme { SourceSeriesPicker(works, {}, lookup = { _, _ ->
                SourceSeriesPage(listOf(SourceSeriesGroup("s", "合成系列", "https://www.dlsite.com/", listOf(SourceSeriesWork("dlsite", "RJ123457", "合成作品乙", "成员", "https://www.dlsite.com/")))), true, false)
            }, onUse = { name, ids -> draft = name to ids }) } }
            compose.onNodeWithText("合成作品甲").performClick(); compose.waitForIdle()
            assertNull(draft)
            for (title in listOf("合成作品甲", "合成作品乙")) {
                compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("选择$title"))
                compose.onNodeWithContentDescription("选择$title").performClick()
            }
            compose.onNodeWithText("使用所选收藏创建系列草稿（2）").performClick()
            assertEquals("合成系列", draft?.first); assertEquals(listOf("a", "b"), draft?.second)
        }
    }
}
