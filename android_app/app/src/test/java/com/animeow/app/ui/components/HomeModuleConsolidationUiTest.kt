package com.animeow.app.ui.components

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.playback.*
import com.animeow.app.data.preferences.*
import com.animeow.app.ui.preferences.HomeModuleControls
import com.animeow.app.ui.tracker.HomeModulesPanel
import com.animeow.app.ui.tracker.buildTrackerHomeSections
import com.animeow.app.ui.theme.AppearanceSettings
import java.io.File
import kotlinx.coroutines.runBlocking
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
class HomeModuleConsolidationUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun actualHistoryAppearsOnceAndManualProgressDoesNotBecomePlaybackHistory() {
        val singleton = AniMeowDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        val app = Instrumentation.newApplication(AniMeowApplication::class.java, RuntimeEnvironment.getApplication()) as AniMeowApplication
        val first = AnimeEntity(title = "合成动画 · 星海旅途", status = "在看", watchedEpisodes = 3, totalEpisodes = 12, createdAt = "2026-10-26")
        val anime = first.copy(id = runBlocking { app.database.libraryDao().insertAnime(first) })
        val manual = anime.copy(id = 99, title = "只手动记集的合成动画")
        val key = PlaybackIdentities(app).key(anime)
        val history = PlaybackHistory(app)
        history.describe(key, anime, "第 4 集")
        history.save(key, PlaybackCheckpoint("synthetic", "work", "4", 754000, 1440000))
        try {
            for (dark in listOf(false, true)) {
                Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
                    val activity = controller.get()
                    val context = object : ContextWrapper(activity) { override fun getApplicationContext(): Context = app }
                    var opened: Long? = null
                    activity.setContent {
                        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                            CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(2f, 1.4f)) {
                                Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(16.dp)) {
                                    HomeModulesPanel(buildTrackerHomeSections(listOf(anime, manual), emptyList()), TrackerSettings(), { opened = it }, {}, {})
                                } }
                            }
                        }
                    }
                    controller.visible()
                    compose.waitUntil(10_000) { compose.onAllNodesWithText(anime.title).fetchSemanticsNodes().size == 1 }
                    compose.onNodeWithText("最近观看").assertIsDisplayed()
                    compose.onNodeWithText("继续记录").assertDoesNotExist()
                    compose.onNodeWithText("阅读进度", substring = false).assertDoesNotExist()
                    compose.onNodeWithText(manual.title).assertDoesNotExist()
                    compose.onNodeWithContentDescription("打开作品详情：${anime.title}").assertHeightIsAtLeast(48.dp).performClick()
                    compose.waitUntil(10_000) { opened == anime.id }
                    assertEquals(anime.id, opened)
                    draw(activity, if (dark) "home-single-dark" else "home-single-light")
                    compose.onNodeWithText("12:34 / 24:00").assertIsDisplayed()
                    assertEquals(3, runBlocking { app.database.libraryDao().getAnime(anime.id) }!!.watchedEpisodes)

                    if (dark) {
                        history.remove(key)
                        compose.runOnIdle { controller.pause(); controller.resume() }
                        compose.waitUntil(10_000) { compose.onAllNodesWithText("还没有可接续的观看记录，可从作品详情开始观看。").fetchSemanticsNodes().isNotEmpty() }
                        compose.onNodeWithText(anime.title).assertDoesNotExist()
                        compose.onNodeWithText(manual.title).assertDoesNotExist()
                        draw(activity, "home-single-empty-dark")
                    }
                }
            }
        } finally { app.database.close(); singleton.set(null, null) }
    }

    @Test fun oldModuleKeysKeepIndependentVisibilityAndShowReadingLabels() {
        val settings = TrackerSettings(showContinueWatching = false, showRecentPlayback = true,
            homeModuleOrder = HomeModule.parse("continue,calendar,playback"))
        val restored = ConfigurationBundleCodec.fromJson(ConfigurationBundleCodec.toJson(ConfigurationBundle(AppearanceSettings(), tracker = settings))).tracker!!
        assertEquals(settings, restored)
        assertEquals("continue", HomeModule.CONTINUE.storageKey)
        assertEquals("阅读进度", HomeModule.CONTINUE.label)
        var current by mutableStateOf(restored)
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent { MaterialTheme { Surface {
                Column(Modifier.padding(16.dp)) { HomeModuleControls(current, { module, visible -> current = current.withModuleVisible(module, visible) }, { _, _ -> }) }
            } } }
            controller.visible()
            compose.onNodeWithContentDescription("显示阅读进度").assertIsOff().performClick().assertIsOn()
            compose.onNodeWithContentDescription("显示最近观看").assertIsOn()
            compose.onNodeWithText("继续记录", substring = false).assertDoesNotExist()
            assertEquals(settings.homeModuleOrder, current.homeModuleOrder)
        }
    }

    private fun draw(activity: ComponentActivity, name: String) {
        compose.waitForIdle()
        val root = activity.window.decorView
        root.requestLayout(); root.invalidate()
        root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 720, 1520); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val bitmap = Bitmap.createBitmap(720, 1520, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../../build/2.2.2-home-consolidation/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
