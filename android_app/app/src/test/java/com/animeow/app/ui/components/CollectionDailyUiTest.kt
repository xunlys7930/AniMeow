package com.animeow.app.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.animeow.app.data.playback.*
import com.animeow.app.data.preferences.*
import com.animeow.app.ui.tracker.RecentPlaybackCard
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectionDailyUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun shortPlaybackCardUsesCoverSideForResumeAtNormalAndLargeTextSizes() {
        for (scale in listOf(1f, 1.4f)) {
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                var opened = 0; var resumed = 0
                activity.setContent { MaterialTheme(colorScheme = if (scale == 1f) lightColorScheme() else darkColorScheme()) {
                    CompositionLocalProvider(LocalDensity provides Density(2f, scale)) {
                        Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(16.dp)) {
                            RecentPlaybackCard(PlaybackHistoryEntry("compact", 1, "约会大作战", "", "02", 0,
                                PlaybackCheckpoint("source", "work", "02", 304000, 1439000)),
                                { opened++ }, { resumed++ }, Modifier.width(300.dp).testTag("compact-playback"))
                        } }
                    }
                } }
                controller.visible(); compose.waitForIdle()
                val root = activity.window.decorView
                root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 720, 1520); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val card = compose.onNodeWithTag("compact-playback").getUnclippedBoundsInRoot()
                val action = compose.onNodeWithText("继续观看").assertIsDisplayed().assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
                val header = compose.onNodeWithContentDescription("打开作品详情：约会大作战").getUnclippedBoundsInRoot()
                assertTrue(action.left >= card.left + 64.dp)
                assertTrue(action.top >= header.bottom)
                if (scale == 1f) assertTrue("普通字号卡片不再为独立按钮占一整行", card.bottom - card.top <= 180.dp)
                compose.onNodeWithText("继续观看").performClick()
                assertEquals(1, resumed); assertEquals(0, opened)
                compose.onNodeWithContentDescription("打开作品详情：约会大作战").performClick()
                assertEquals(1, opened); assertEquals(1, resumed)
                val bitmap = Bitmap.createBitmap(720, 1520, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File("../../build/settings-polish-20261029/playback-${if (scale == 1f) "light-normal" else "dark-large"}.png").apply {
                    parentFile!!.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun recentPlaybackFitsLargeChineseTextAndKeepsActionsSeparateInBothThemes() {
        var resumed = 0; var opened = 0
        val title = "一部标题很长很长的合成动画，用来检查中文大字体下的换行与操作区域"
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            var dark by androidx.compose.runtime.mutableStateOf(false)
            activity.setContent {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    CompositionLocalProvider(LocalDensity provides Density(2f, 1.4f)) {
                        Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(16.dp)) {
                            Text("今天 · 2026-10-25", style = MaterialTheme.typography.titleLarge)
                            Text("最近观看", Modifier.padding(vertical = 12.dp))
                            RecentPlaybackCard(PlaybackHistoryEntry("one", 1, title, "", "第 12 集 · 合成记录", 0,
                                PlaybackCheckpoint("source", "work", "12", 754000, 1440000)), { opened++ }, { resumed++ }, Modifier.fillMaxWidth())
                            Text("本机排期 · 20:00", Modifier.padding(vertical = 12.dp))
                        } }
                    }
                }
            }
            controller.visible()
            fun draw(name: String) {
                compose.waitForIdle()
                val root = activity.window.decorView
                root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 720, 1520); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val bitmap = Bitmap.createBitmap(720, 1520, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                val pixels = IntArray(720 * 1520); bitmap.getPixels(pixels, 0, 720, 0, 0, 720, 1520)
                assertTrue(pixels.toSet().size > 20)
                val file = File("../../build/release-plan-2.2.2/today-$name.png"); file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            draw("light")
            compose.onNodeWithText("接着上次看").assertDoesNotExist()
            compose.onNodeWithText("作品详情").assertDoesNotExist()
            compose.onNodeWithText("继续观看").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithContentDescription("打开作品详情：$title").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            val headerBounds = compose.onNodeWithContentDescription("打开作品详情：$title").getUnclippedBoundsInRoot()
            val actionBounds = compose.onNodeWithText("继续观看").getUnclippedBoundsInRoot()
            assertTrue("续播按钮与右侧作品信息对齐", actionBounds.left >= headerBounds.left)
            assertTrue("续播按钮与详情点击区域分开", actionBounds.top >= headerBounds.bottom)
            assertEquals(1, resumed); assertEquals(1, opened)
            compose.runOnIdle { dark = true }
            draw("dark")
            compose.onNodeWithText("12:34 / 24:00").assertIsDisplayed()
            compose.onNodeWithContentDescription("打开作品详情：$title").performClick()
            assertEquals(1, resumed); assertEquals(2, opened)
        }
    }

    @Test fun cachedOnlinePlaybackRetainsNetworkControlsButOfflineSelectionDoesNot() {
        val work = com.animeow.app.data.remote.PlaybackWork("work", "合成作品", "")
        val episode = com.animeow.app.data.remote.PlaybackEpisode("1", "01", 1)
        val session = com.animeow.app.ui.playback.PlaybackSession(1, "source", work, episode,
            com.animeow.app.data.remote.PlaybackStream("https://example.test/local.mp4", "video/mp4", emptyMap()),
            PlaybackCheckpoint("source", "work", "1"), offline = true)
        var state by androidx.compose.runtime.mutableStateOf(com.animeow.app.ui.playback.PlaybackUiState(work = work, session = session))
        var changes = 0
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent { MaterialTheme { Surface {
                com.animeow.app.ui.playback.PlaybackWorkHeader(state, {}, { changes++ })
            } } }
            controller.visible()
            compose.onNodeWithText("01 · 本集使用缓存").assertIsDisplayed()
            compose.onNodeWithText("更换来源").performClick()
            compose.onNodeWithText("切换线路").assertIsDisplayed()
            assertEquals(1, changes)
            compose.runOnIdle { state = state.copy(session = session.copy(offline = false), source = "candidate",
                work = work.copy(id = "other", title = "尚未播放的候选作品"), sources = listOf(
                    com.animeow.app.data.remote.PlaybackSource("source", "当前来源"),
                    com.animeow.app.data.remote.PlaybackSource("candidate", "候选来源"))) }
            compose.onNodeWithText("01 · 当前来源").assertIsDisplayed()
            compose.onNodeWithText("候选来源").assertDoesNotExist()

            compose.runOnIdle { state = state.copy(offlineOnly = true) }
            compose.onNodeWithText("本地选集与下载").assertIsDisplayed()
            compose.onNodeWithText("更换来源").assertDoesNotExist()
            compose.runOnIdle { state = state.copy(session = null) }
            compose.onNodeWithText("本地选集与下载").assertIsDisplayed()
            compose.onNodeWithText("更换来源").assertDoesNotExist()
        }
    }

    @Test fun bookHomeModulesUseReadingLabelsAndKeepTheCorrectProgressTarget() {
        val book = com.animeow.app.data.local.AnimeEntity(id = 42, title = "合成书籍", subjectType = "book", status = "在看", watchedEpisodes = 2, totalEpisodes = 10)
        var incremented: Long? = null
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent { MaterialTheme { Surface {
                com.animeow.app.ui.tracker.HomeModulesPanel(com.animeow.app.ui.tracker.TrackerHomeSections(reading = listOf(book)),
                    TrackerSettings(showRecentPlayback = false), {}, {}, { incremented = it.id })
            } } }
            controller.visible()
            compose.onNodeWithText("阅读进度 2 / 10").assertIsDisplayed()
            compose.onNodeWithText("阅读进度", substring = false).assertIsDisplayed()
            compose.onNodeWithText("继续记录").assertDoesNotExist()
            compose.onNodeWithText("阅读进度 +1").performClick()
            assertEquals(42L, incremented)
            compose.onNodeWithText("看过一集 +1").assertDoesNotExist()
        }
    }

    @Test
    @Config(qualifiers = "w760dp-h360dp-xhdpi")
    fun filterDialogFitsLandscapeAndFailedApplicationKeepsItsContext() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val config = android.content.res.Configuration(context.resources.configuration).apply { fontScale = 1.4f }
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        val preferences = CollectionFilterPreferences(context)
        preferences.restore(JSONArray())
        preferences.save(CollectionFilterPlan("landscape", "横屏合成方案", "library", CollectionFilterConditions(status = "在看", tags = listOf("日常"), years = listOf("2026"), sort = "rating")))
        var attempts = 0
        var fail = true
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface {
                CollectionFilterPlans("library", CollectionFilterConditions(), listOf("日常"), listOf("全部", "在看"), onApply = {
                    attempts++; if (fail) "标签已变化，请调整方案后重试" else null
                })
            } } }
            controller.visible()
            compose.onNodeWithText("收藏筛选方案").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("横屏合成方案").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("保存当前条件").assertIsDisplayed()
            compose.onNodeWithText("关闭").assertIsDisplayed()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("应用方案横屏合成方案"))
            compose.onNodeWithContentDescription("应用方案横屏合成方案").performClick()
            compose.onNodeWithText("保存当前条件").assertIsDisplayed()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("标签已变化，请调整方案后重试"))
            compose.onNodeWithText("标签已变化，请调整方案后重试").assertIsDisplayed()
            assertEquals(1, attempts)
            val root = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()).window!!.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.AT_MOST))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            assertTrue(root.height <= 720)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val output = File("../../build/2.2.2-unified/filter-landscape.png"); output.parentFile!!.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            fail = false
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("应用方案横屏合成方案"))
            compose.onNodeWithContentDescription("应用方案横屏合成方案").performClick()
            assertEquals(2, attempts)
            compose.onNodeWithText("保存当前条件").assertDoesNotExist()
        }
        Unit
    }

    @Test fun filterDialogBlocksMissingConditionsAndAppliesSavedValuesOnce() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val configuration = android.content.res.Configuration(context.resources.configuration)
        configuration.fontScale = 1.4f
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
        val preferences = CollectionFilterPreferences(context)
        preferences.restore(JSONArray())
        val current = CollectionFilterConditions(query = "当前草稿")
        val saved = CollectionFilterConditions(query = "已保存关键词", status = "在看", tags = listOf("日常"))
        preferences.save(CollectionFilterPlan("valid", "有效方案", "library", saved))
        preferences.save(CollectionFilterPlan("missing", "包含已删除标签的方案", "library", saved.copy(tags = listOf("已删除"))))
        var applied: CollectionFilterConditions? = null
        var dark by androidx.compose.runtime.mutableStateOf(false)
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalDensity provides Density(2f, if (dark) 1.4f else 1f)) { Surface {
                CollectionFilterPlans("library", current, listOf("日常"), listOf("全部", "在看"), onApply = { applied = it; null })
            } } } }
            controller.visible()
            compose.onNodeWithText("收藏筛选方案").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("有效方案").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("缺少标签「已删除」，请调整当前条件后更新此方案"))
            compose.onNodeWithText("缺少标签「已删除」，请调整当前条件后更新此方案").assertIsDisplayed()
            fun drawDialog(name: String) {
                compose.waitForIdle()
                val root = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()).window!!.decorView
                root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.AT_MOST))
                root.layout(0, 0, 720, root.measuredHeight); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                val file = File("../../build/release-plan-2.2.2/filters-$name.png"); file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            drawDialog("light")

            compose.onNodeWithContentDescription("应用方案包含已删除标签的方案").assertIsNotEnabled()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("应用方案有效方案"))
            compose.onNodeWithContentDescription("应用方案有效方案").performClick()
            assertEquals(saved, applied)
            compose.onNodeWithText("筛选方案：有效方案（已修改）").assertIsDisplayed()
            compose.runOnIdle { dark = true }
            compose.onNodeWithText("筛选方案：有效方案（已修改）").performClick()
            compose.onNodeWithText("保存当前条件").assertIsDisplayed()
            drawDialog("dark-large-font")
        }
        Unit
    }

    @Test fun filterPlanManagementStaysInMoreAndDeletionStillNeedsConfirmation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val preferences = CollectionFilterPreferences(context)
        preferences.restore(JSONArray())
        val current = CollectionFilterConditions(query = "当前草稿", status = "在看")
        val saved = CollectionFilterConditions(query = "已保存关键词", tags = listOf("日常"))
        preferences.save(CollectionFilterPlan("compact", "轻量方案", "library", saved))
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent { MaterialTheme { Surface {
                CollectionFilterPlans("library", current, listOf("日常"), listOf("全部", "在看"), onApply = { null })
            } } }
            controller.visible()
            val root = controller.get().window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 720, 1520); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            fun captureManagementDialog(name: String) {
                val dialogRoot = android.view.inspector.WindowInspector.getGlobalWindowViews().last { it !== root && it.isShown }
                dialogRoot.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.AT_MOST))
                dialogRoot.layout(0, 0, 720, dialogRoot.measuredHeight)
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val bitmap = Bitmap.createBitmap(dialogRoot.width, dialogRoot.height, Bitmap.Config.ARGB_8888)
                dialogRoot.draw(Canvas(bitmap))
                File("../../build/settings-polish-20261029/filter-$name.png").apply {
                    parentFile!!.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                bitmap.recycle()
            }
            compose.onNodeWithText("收藏筛选方案").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("轻量方案").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("应用方案轻量方案").assertIsEnabled().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithContentDescription("修改方案轻量方案的名称").assertDoesNotExist()
            compose.onNodeWithContentDescription("用当前条件更新方案轻量方案").assertDoesNotExist()
            compose.onNodeWithContentDescription("删除方案轻量方案").assertDoesNotExist()

            compose.onNodeWithContentDescription("管理方案轻量方案").assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText("查看完整条件").performClick()
            compose.onNodeWithText(collectionFilterSummary(saved, "library")).assertExists()
            compose.onNodeWithContentDescription("管理方案轻量方案").performScrollTo().performClick()
            compose.onNodeWithText("收起完整条件").performClick()

            compose.onNodeWithContentDescription("管理方案轻量方案").performClick()
            compose.onNodeWithContentDescription("修改方案轻量方案的名称").performClick()
            compose.onNodeWithText("修改方案名称").assertIsDisplayed()
            captureManagementDialog("rename")
            compose.onNodeWithText("取消").performClick()
            assertEquals(saved, preferences.plans.first().single().conditions)

            compose.onNodeWithContentDescription("管理方案轻量方案").performClick()
            compose.onNodeWithContentDescription("用当前条件更新方案轻量方案").performClick()
            compose.waitUntil(10000) { runBlocking { preferences.plans.first().single().conditions == current.normalized() } }
            compose.onNodeWithContentDescription("管理方案轻量方案").performClick()
            compose.onNodeWithContentDescription("删除方案轻量方案").performClick()
            compose.onNodeWithText("删除筛选方案？").assertIsDisplayed()
            compose.onNodeWithText("作品和个人记录会保留。").assertIsDisplayed()
            captureManagementDialog("delete")
            compose.onNodeWithText("保留").performClick()
            assertEquals("compact", preferences.plans.first().single().id)
            compose.onNodeWithText("轻量方案").assertIsDisplayed()
        }
        Unit
    }

}
