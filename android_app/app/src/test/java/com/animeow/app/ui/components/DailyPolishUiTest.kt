@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.animeow.app.ui.components

import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
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
import androidx.lifecycle.ViewModelStore
import androidx.media3.exoplayer.offline.Download
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.audio.AudioWork
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.playback.*
import com.animeow.app.data.remote.*
import com.animeow.app.ui.audio.AudioQuickProgressActions
import com.animeow.app.ui.playback.*
import com.animeow.app.ui.tracker.RecentPlaybackCard
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
@Config(manifest = Config.NONE, application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DailyPolishUiTest {
    @get:Rule val compose = createEmptyComposeRule()


    @Test fun settingsHomeShowsSixTaskEntriesWithoutExpandingLowFrequencySwitches() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            var opened = ""
            val appearance = androidx.lifecycle.ViewModelProvider(activity)[com.animeow.app.ui.preferences.AppearanceViewModel::class.java]
            activity.setContent { MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1.4f)) {
                    com.animeow.app.ui.screens.SettingsScreen(
                        settings = com.animeow.app.ui.theme.AppearanceSettings(),
                        onThemeModeSelected = {}, onFrontendModeSelected = {},
                        onPredictiveBackEnabledChanged = {}, onExitBehaviorChanged = {},
                        onShowDiscoveryChanged = {}, onShowCalendarChanged = {},
                        onShowCommunityChanged = {}, onShowStatisticsChanged = {},
                        onShowVersionInProfileChanged = {}, onNavigationOrderChanged = {},
                        onStartDestinationSelected = {}, onCustomizationRequested = { opened = "appearance" },
                        onCustomizationSectionRequested = { opened = it }, onSectionRequested = { opened = it },
                        appearanceViewModel = appearance,
                    )
                }
            } }
            controller.visible(); draw(activity, "settings-home-six-entries-light-large")
            compose.onNodeWithText("外观与阅读").performScrollTo().performClick()
            assertEquals("appearance", opened)
            compose.onNodeWithText("导航与启动").performScrollTo().performClick()
            assertEquals("navigation", opened)
            compose.onNodeWithText("操作与反馈").performScrollTo().performClick()
            assertEquals("behavior", opened)
            compose.onNodeWithText("账号与数据").performScrollTo().performClick()
            assertEquals("data", opened)
            compose.onNodeWithText("工具与内容").performScrollTo().performClick()
            assertEquals("tools", opened)
            compose.onNodeWithText("帮助与维护").performScrollTo().performClick()
            assertEquals("help", opened)
            compose.onNodeWithText("预测性返回手势").assertDoesNotExist()
            compose.onNodeWithText("显示统计").assertDoesNotExist()
        }
    }

    @Test fun categoryResetRequiresExplicitScopeConfirmationAndCanBeCancelled() {
        var resets = 0
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
                com.animeow.app.ui.preferences.ChangedSettingsPanel(
                    listOf(com.animeow.app.data.preferences.AppearanceChange("behavior", "返回退出方式", "双击返回", "直接退出")),
                    "behavior", { resets++ },
                )
            } }
            controller.visible(); draw(activity, "settings-reset-scope-dark")
            compose.onNodeWithText("仅恢复本分类 · 可撤销").performClick()
            compose.onNodeWithText("恢复「操作与反馈」默认？").assertIsDisplayed()
            assertEquals(0, resets)
            compose.onNodeWithText("取消").performClick()
            assertEquals(0, resets)
            compose.onNodeWithText("仅恢复本分类 · 可撤销").performClick()
            compose.onNodeWithText("恢复本分类").performClick()
            assertEquals(1, resets)
        }
    }
    @Test
    @Config(qualifiers = "w760dp-h360dp-xhdpi")
    fun settingsRemainScrollableAndIndependentInLandscapeWithLargeText() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            var settings by mutableStateOf(PlaybackSettings())
            activity.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1.4f)) {
                    Surface(Modifier.fillMaxSize()) { Box(Modifier.padding(16.dp)) { PlaybackSettingsContent(settings, { settings = it }) } }
                }
            } }
            controller.visible(); draw(activity, "settings-landscape-dark", 1520, 720)
            compose.onNodeWithText("自动记录已看进度").assertIsDisplayed().assertIsOn()
            compose.onNodeWithText("全屏自动锁定").performScrollTo().assertIsDisplayed().assertIsOff().performClick().assertIsOn()
            assertTrue(settings.autoMarkProgress)
            compose.onNodeWithText("自动记录已看进度").performScrollTo().assertIsDisplayed().assertIsOn()
        }
    }

    @Test fun automaticProgressSwitchHasALabelAndChangesIndependentlyInBothThemes() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            var settings by mutableStateOf(PlaybackSettings())
            var updates = 0
            var dark by mutableStateOf(false)
            activity.setContent { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1.4f)) {
                    Surface(Modifier.fillMaxSize()) { Box(Modifier.padding(16.dp)) { PlaybackSettingsContent(settings, { updates++; settings = it }) } }
                }
            } }
            controller.visible(); draw(activity, "progress-switch-on-light")
            compose.onNodeWithText("自动记录已看进度").assertIsDisplayed().assertIsOn().performClick()
            compose.onNodeWithText("自动记录已看进度").assertIsOff()
            assertEquals(PlaybackSettings(autoMarkProgress = false), settings)
            compose.runOnIdle { dark = true }; draw(activity, "progress-switch-off-dark")
            compose.onNodeWithText("自动记录已看进度").performClick().assertIsOn()
            assertFalse(settings.autoNext)
            val before = updates
            compose.onNodeWithText("紧凑选集").performScrollTo().assertIsNotSelected().performClick().assertIsSelected()
            assertEquals(before + 1, updates)
            assertEquals(WatchingLayout.COMPACT, settings.layout)
            compose.onNodeWithText("清晰锐化放大").performScrollTo().performClick().assertIsSelected()
            assertEquals(PlaybackUpscale.SPATIAL, settings.upscale)
            compose.onNodeWithText("自动下一集").performScrollTo().assertIsOff().performClick().assertIsOn()
            assertTrue(settings.autoMarkProgress)
            compose.onNodeWithText("全屏自动锁定").performScrollTo().assertIsOff().performClick().assertIsOn()
        }
    }

    @Test fun completedPlaybackAndAudioActionsFitLargeTextInBothThemes() {
        var next = 0; var replay = 0; var added = 0; var finished = 0
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            var dark by mutableStateOf(false)
            activity.setContent {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    CompositionLocalProvider(LocalDensity provides Density(2f, 1.4f)) {
                        Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("最近观看", style = MaterialTheme.typography.titleLarge)
                            RecentPlaybackCard(PlaybackHistoryEntry("key", 1, "合成动画：长标题与完播后的观看操作", "", "第 1 集", 0,
                                PlaybackCheckpoint("seven", "work", "1", 1440000, 1440000)), {}, { replay++ }, Modifier.fillMaxWidth(), { next++ })
                            Text("音声快捷记录", style = MaterialTheme.typography.titleLarge)
                            Text("合成音声 · 已听 8:20 / 10:00")
                            AudioQuickProgressActions(AudioWork(title = "合成音声", listenedSeconds = 500, durationSeconds = 600), false, { added++ }, { finished++ })
                        } }
                    }
                }
            }
            controller.visible(); draw(activity, "daily-actions-light")
            compose.onNodeWithText("重播本集").assertIsDisplayed().performClick()
            compose.onNodeWithText("播放下一集").assertIsDisplayed().performClick()
            compose.onNodeWithText("已听 +5 分钟").assertIsDisplayed().performClick()
            compose.onNodeWithText("标记听完").assertIsDisplayed().performClick()
            assertEquals(listOf(1, 1, 1, 1), listOf(replay, next, added, finished))
            compose.runOnIdle { dark = true }; draw(activity, "daily-actions-dark")
            compose.onNodeWithText("本集已播完").assertIsDisplayed()
        }
    }

    @Test fun downloadFailureFilterAndSizeCleanupKeepExplicitConfirmation() {
        val singleton = AniMeowDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        val app = Instrumentation.newApplication(AniMeowApplication::class.java, RuntimeEnvironment.getApplication()) as AniMeowApplication
        val store = ViewModelStore()
        val model = PlaybackViewModel(app, 0).also { store.put("downloads", it) }
        fun row(id: String, title: String, bytes: Long, status: Int) = PlaybackDownloadRow(id,
            PlaybackDownloadInfo(1, id, title, "seven", PlaybackWork("work", title, ""), PlaybackEpisode(id, "第 1 集", 1), "line", "合成线路", emptyMap()),
            status, bytes, bytes, 100f, if (status == Download.STATE_FAILED) "解析失败" else "已下载")
        val rows = listOf(row("large", "合成大文件作品", 104857600, Download.STATE_COMPLETED),
            row("small", "合成小文件作品", 52428800, Download.STATE_COMPLETED), row("failed", "合成失败作品", 0, Download.STATE_FAILED))
        var downloads by mutableStateOf(rows)
        try {
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                activity.resources.updateConfiguration(android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.4f }, activity.resources.displayMetrics)
                var dark by mutableStateOf(true)
                activity.setContent { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.fillMaxSize()) { PlaybackDownloadPanel(PlaybackUiState(downloads = downloads, availableBytes = 2147483648,
                        downloadResults = listOf(DownloadRetryResult("failed", "合成失败作品", "第 1 集", "失败", "解析未完成"),
                            DownloadRetryResult("submitted", "合成已提交作品", "第 2 集", "已提交", "等待实际下载"))), model, Modifier.fillMaxSize(), library = true) }
                } }
                controller.visible(); draw(activity, "downloads-in-progress-dark")
                compose.onNodeWithText("失败").performClick()
                compose.onNodeWithText("合成失败作品").assertIsDisplayed()
                compose.onNodeWithText("1 项失败").assertIsDisplayed()
                compose.onNodeWithText("合成大文件作品").assertDoesNotExist()
                compose.onNodeWithText("合成失败作品").performClick()
                compose.onNodeWithText("作品任务").assertIsDisplayed()
                compose.onNodeWithText("重新解析并重试").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("返回全部作品").performScrollTo().performClick()
                draw(activity, "downloads-failed-dark")
                compose.onNodeWithText("全部暂停").assertDoesNotExist()
                compose.onNodeWithText("查看逐项重试结果").performClick()
                compose.onNodeWithText("已提交 · 等待实际下载").assertIsDisplayed()
                compose.onNodeWithText("失败 · 解析未完成").assertIsDisplayed()
                compose.onNodeWithText("关闭").performClick()
                compose.onNodeWithText("已下载").performClick()
                compose.onNodeWithText("按大小清理下载").performClick()
                compose.onNodeWithText("合成大文件作品").performClick()
                compose.onNodeWithText("清理 1 集 · 100.0 MB").assertIsDisplayed()
                draw(activity, "downloads-cleanup-dark")
                compose.onNodeWithText("清理 1 集 · 100.0 MB").performClick()
                compose.onNodeWithText("删除 1 集下载？").assertIsDisplayed()
                compose.onNodeWithText("保留").performClick()
                compose.onNodeWithText("清理 1 集 · 100.0 MB").assertIsDisplayed()
                compose.runOnIdle { dark = false }; draw(activity, "downloads-cleanup-light")
                compose.onNodeWithText("失败").performClick()
                compose.runOnIdle { downloads = emptyList() }
                compose.onNodeWithText("没有失败的下载任务").assertIsDisplayed()
                compose.onNodeWithText("当前范围内没有需要重试的任务。").assertIsDisplayed()
                draw(activity, "downloads-empty-light")
                compose.runOnIdle { dark = true }; draw(activity, "downloads-empty-dark")
            }
        } finally { store.clear(); app.database.close(); singleton.set(null, null) }
    }

    private fun draw(activity: ComponentActivity, name: String, width: Int = 720, height: Int = 1520) {
        compose.waitForIdle()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val pixels = IntArray(width * height); bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        assertTrue(pixels.toSet().size > 20)
        File("../../build/2.2.2-polish/$name.png").apply { parentFile!!.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        bitmap.recycle()
    }
}
