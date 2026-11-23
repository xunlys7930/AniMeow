package com.animeow.app.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import com.animeow.app.data.BatchEpisodeProgress
import com.animeow.app.data.EpisodeProgressEdit
import com.animeow.app.data.bangumi.BangumiSyncReport
import com.animeow.app.data.cloud.CloudSyncSettings
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.preferences.DiscoveryDisplayConfig
import com.animeow.app.ui.cloud.BangumiSyncStatusContent
import com.animeow.app.ui.cloud.CloudAccountUiState
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Version224PolishTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun batchResetShowsTheExactChangesBeforeConfirmationInBothThemes() {
        for (dark in listOf(false, true)) {
            val plan = BatchEpisodeProgress(listOf(EpisodeProgressEdit(
                AnimeEntity(id = 1, title = "未看动画", watchedEpisodes = 24, totalEpisodes = 24), 0)), 1)
            var saved: BatchEpisodeProgress? = null
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
                controller.get().setContent {
                    CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                            BatchEpisodeProgressDialog(null, false, { plan }, {}, { saved = it })
                        }
                    }
                }
                controller.visible()
                compose.onNodeWithText("24/24 → 0/24").assertIsDisplayed()
                compose.onNodeWithText("确认修改").assertIsDisplayed().assertIsEnabled()
                assertNull(saved)
                draw("batch-${if (dark) "dark" else "light"}")
                compose.onNodeWithText("确认修改").performClick()
                assertEquals(plan, saved)
            }
        }
    }

    @Test fun discoveryPresetsPreviewWithoutSavingAndCanReturnToTheOpeningConfiguration() {
        var saved: DiscoveryDisplayConfig? = null
        val initial = DiscoveryDisplayConfig()
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent {
                MaterialTheme { DiscoveryDisplaySheet(initial, { saved = it }, {}) }
            }
            controller.visible()
            compose.onNodeWithText("紧凑信息列表").performClick()
            assertNull(saved)
            draw("search-presets")
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("恢复打开前的设置"))
            compose.onNodeWithText("恢复打开前的设置").performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("应用设置"))
            compose.onNodeWithText("应用设置").performClick()
            assertEquals(initial, saved)
        }
    }

    @Test fun syncFailureShowsTheReasonAndRetryActionAtLargeText() {
        var retries = 0
        val report = BangumiSyncReport("2026-11-11T01:00:00Z", true, 0, 2, 0, "网络连接不稳定", emptyList(), false)
        val state = CloudAccountUiState(bangumiPendingCount = 2,
            syncSettings = CloudSyncSettings(bangumiProgressSync = true, bangumiLastReport = report))
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        BangumiSyncStatusContent(state, {}, { retries++ })
                    }
                }
            }
            controller.visible()
            compose.onNodeWithText("同步未完成 · 2 项等待重试").assertIsDisplayed()
            compose.onNodeWithText("网络连接不稳定").assertIsDisplayed()
            compose.onNodeWithText("立即重试").assertIsDisplayed().performClick()
            assertEquals(1, retries)
            draw("sync-dark")
        }
    }

    private fun draw(name: String) {
        val root = requireNotNull(ShadowDialog.getLatestDialog()).window!!.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../../build/224-polish-previews/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
