package com.animeow.app.ui.community

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommunityPeopleUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun moderationReadFailureHasRetryInsteadOfPretendingThereAreNoReports() {
        var failed by mutableStateOf(true)
        scene {
            CommunityReviewList(emptyList(), "", false, null, { _, _ -> },
                error = if (failed) "网络暂时不可用" else null, onRetry = { failed = false })
        }.use { activity ->
            compose.onNodeWithText("待复审内容暂时无法读取").assertIsDisplayed()
            compose.onNodeWithText("暂无待复审内容").assertDoesNotExist()
            draw(activity.get(), "moderation-error-large")
            compose.onNodeWithText("重新加载").performClick()
            compose.onNodeWithText("暂无待复审内容").assertIsDisplayed()
        }
    }

    @Test fun statisticsFailureCanRetryAndNonDevelopersNeverSeeAuditContents() {
        var retries = 0
        scene {
            CommunityStatsDialog(com.animeow.app.data.community.CommunityStats(postCount = 5), "synthetic-private-audit",
                {}, {}, {}, error = "网络暂时不可用", onRetry = { retries++ }, canExport = false)
        }.use { activity ->
            compose.onNodeWithText("统计暂时无法读取").assertIsDisplayed()
            compose.onNodeWithText("导出审计").assertDoesNotExist()
            compose.onNodeWithText("synthetic-private-audit").assertDoesNotExist()
            draw(activity.get(), "moderation-stats-error-large")
            compose.onNodeWithText("重新加载").performClick(); assertEquals(1, retries)
        }
    }

    @Test fun profilePostFailureHasARetryAndNeverPretendsTheUserHasNoPosts() {
        var failed by mutableStateOf(true)
        scene {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                CommunityProfilePostsState(false, failed, "网络暂时不可用") { failed = false }
            }
        }.use { activity ->
            compose.onNodeWithText("帖子暂时无法读取").assertIsDisplayed()
            compose.onNodeWithText("还没有发布过帖子").assertDoesNotExist()
            draw(activity.get(), "people-profile-error-large")
            compose.onNodeWithText("重新加载").performClick()
            compose.onNodeWithText("还没有发布过帖子").assertIsDisplayed()
        }
    }

    @Test fun searchFiltersWrapAndFailureKeepsTheQueryAvailableForRetry() {
        val queries = mutableListOf<String>()
        scene {
            CommunitySearchDialog(emptyList(), false, null, { query, _, _ -> queries += query }, {}, {}, {}, {}, "网络暂时不可用")
        }.use { activity ->
            compose.onNodeWithText("帖子内容或用户名").performTextInput("合成搜索")
            compose.onNodeWithText("精华").assertIsDisplayed()
            compose.onNodeWithText("评论最多").assertIsDisplayed()
            compose.onNodeWithText("搜索暂时不可用").assertIsDisplayed()
            compose.onNodeWithText("没有找到相关帖子").assertDoesNotExist()
            draw(activity.get(), "people-search-error-large")
            compose.onNodeWithText("重新加载").performClick()
            assertEquals("合成搜索", queries.last())
        }
    }

    private fun scene(content: @Composable () -> Unit): org.robolectric.android.controller.ActivityController<ComponentActivity> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            MaterialTheme { CompositionLocalProvider(LocalDensity provides Density(2f, 1.4f)) { Surface(Modifier.fillMaxSize()) { content() } } }
        }
        controller.visible()
        val root = controller.get().window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 720, 1520); compose.waitForIdle()
        return controller
    }

    private fun draw(activity: ComponentActivity, name: String) {
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        val root = activity.window.decorView
        val target = WindowInspector.getGlobalWindowViews().lastOrNull { it !== root && it.isShown && it.width > 0 } ?: root
        val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888); target.draw(Canvas(bitmap))
        val colors = hashSetOf<Int>(); for (y in 0 until bitmap.height step 12) for (x in 0 until bitmap.width step 12) colors += bitmap.getPixel(x, y)
        assertTrue(colors.size > 12)
        File("../../build/am02-community-20261106/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
