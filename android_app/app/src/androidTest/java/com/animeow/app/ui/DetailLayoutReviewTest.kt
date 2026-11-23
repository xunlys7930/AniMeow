package com.animeow.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.R
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.EpisodeDetailEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.ui.anime.*
import com.animeow.app.ui.theme.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DetailLayoutReviewTest {
    @get:Rule val compose = createComposeRule()
    private val base = AppearanceSettings(useDynamicColor = false, motionLevel = MotionLevel.NONE, themeMode = ThemeMode.LIGHT)
    private val work = AnimeEntity(id = 1, title = "星海旅人", originalTitle = "A Journey Beyond the Sky", status = "在看",
        watchedEpisodes = 8, totalEpisodes = 12, watchStartDate = "2026-08-23", createdAt = "2026-08-20",
        airDate = "2026-07-10", rating = 85, studio = "星海工作室", mediaFormat = "TV",
        synopsis = "从雨后的车站出发，去寻找远方的光。\n\n沿着银河继续前进，把沿途遇到的朋友、风景与感动，慢慢写进自己的记录。")

    @Composable private fun Detail(settings: AppearanceSettings, sections: List<DetailModule> = DetailModule.entries.filterNot { it == DetailModule.SERIES },
        onCheckIn: () -> Unit = {}) {
        val context = LocalContext.current
        val anime = work.copy(coverUrl = "android.resource://${context.packageName}/${R.drawable.preview_cover}")
        AniMeowTheme(settings) {
            CompositionLocalProvider(LocalDetailLayout provides settings.detailLayout, LocalDetailCardStyle provides settings.detailCardStyle) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Text(settings.detailLayout.displayName, Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            style = MaterialTheme.typography.titleLarge)
                        DetailGlassScene(settings.detailCardStyle == DetailCardStyle.GLASS && settings.detailLayout != DetailLayout.MINIMAL,
                            anime.coverUrl, Modifier.weight(1f)) {
                            DetailModulesLayout(Modifier.fillMaxSize(), settings.detailLayout, sections,
                                anime, listOf(TagEntity(id = 1, name = "冒险"), TagEntity(id = 2, name = "治愈")), emptyList(), null, emptyList(),
                                settings.characterImageAlignment, {}, onCheckIn, {}, {}, {}, {}, {}, {}, {}, {},
                                episodeDetails = listOf(EpisodeDetailEntity(animeId = 1, episode = 2, title = "合成分集标题")),
                                onOpenEpisodes = {})
                        }
                    }
                }
            }
        }
    }

    @Test fun fiveLayoutsRenderRealContentInLightDarkAndLargeText() {
        val appearance = mutableStateOf(base)
        compose.setContent { key(appearance.value) { Detail(appearance.value) } }
        for (scale in listOf(1f, 1.4f)) for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            for (layout in DetailLayout.entries) {
                compose.runOnIdle { appearance.value = base.copy(detailLayout = layout, fontScale = scale, themeMode = mode) }
                compose.onNodeWithTag("detail-module-header").assertIsDisplayed()
                screenshot("detail-${layout.storageKey}-${mode.storageKey}-${(scale * 100).toInt()}")
                compose.onNodeWithTag("detail-modules").performScrollToNode(hasTestTag("detail-module-progress"))
                compose.onNodeWithText("修改进度").assertIsDisplayed()
                compose.onNodeWithTag("detail-modules").performScrollToNode(hasTestTag("detail-module-tags"))
                compose.onNodeWithText("治愈").assertIsDisplayed()
            }
        }
    }

    @Test fun dashboardUsesTwoColumnsAndLargeTextFallsBackWithoutLosingActions() {
        val appearance = mutableStateOf(base.copy(detailLayout = DetailLayout.DASHBOARD))
        compose.setContent { Detail(appearance.value) }
        val progress = compose.onNodeWithTag("detail-module-progress").fetchSemanticsNode().boundsInRoot
        val dates = compose.onNodeWithTag("detail-module-watch_dates").fetchSemanticsNode().boundsInRoot
        assertEquals(progress.top, dates.top, 1f)
        assertTrue(dates.left > progress.left)
        compose.runOnIdle { appearance.value = appearance.value.copy(fontScale = 1.4f) }
        compose.onNodeWithTag("detail-modules").performScrollToNode(hasTestTag("detail-module-watch_dates"))
        val wideDates = compose.onNodeWithTag("detail-module-watch_dates").fetchSemanticsNode().boundsInRoot
        assertTrue(wideDates.width > dates.width * 1.5f)
        compose.onNodeWithText("编辑日期").assertIsDisplayed()
    }

    @Test fun hiddenAndReorderedModulesApplyToAllLayouts() {
        val appearance = mutableStateOf(base)
        val sections = listOf(DetailModule.PROGRESS, DetailModule.TAGS, DetailModule.SYNOPSIS)
        var increments = 0
        compose.setContent { key(appearance.value.detailLayout) { Detail(appearance.value, sections) { increments++ } } }
        for (layout in DetailLayout.entries) {
            compose.runOnIdle { appearance.value = base.copy(detailLayout = layout) }
            compose.onNodeWithTag("detail-module-header").assertDoesNotExist()
            compose.onNodeWithTag("detail-module-progress").assertIsDisplayed()
            val button = when (layout) {
                DetailLayout.MINIMAL -> compose.onNodeWithContentDescription("记录下一集")
                DetailLayout.THEATER -> compose.onNodeWithText("记录下一集")
                else -> compose.onNodeWithText("+1 集")
            }
            button.performClick()
        }
        compose.runOnIdle { assertEquals(5, increments) }
    }

    @Test fun draggingAcrossRowsCommitsOnceOnReleaseAndCancelRestoresOrder() {
        val settings = mutableStateOf(base)
        var saves = 0
        compose.setContent { AniMeowTheme(settings.value) {
            DetailModuleCustomizationSheet(settings.value,
                { saves++; settings.value = settings.value.copy(detailModuleOrder = it) },
                { settings.value = settings.value.copy(hiddenDetailModules = it) }, {}, {})
        } }
        compose.onNodeWithTag("module-drag-header").assertIsDisplayed()
        screenshot("detail-module-sort")
        compose.mainClock.autoAdvance = false
        val list = compose.onNodeWithTag("detail-module-sort-list")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithTag("module-drag-header").fetchSemanticsNode().boundsInRoot
        list.performTouchInput { down(Offset(handle.center.x - bounds.left, handle.center.y - bounds.top)); moveBy(Offset(0f, handle.height * 3.7f)) }
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle { assertEquals(0, saves) }
        list.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle {
            assertEquals(1, saves)
            assertTrue(settings.value.detailModuleOrder.indexOf(DetailModule.HEADER) >= 2)
        }
        val before = settings.value.detailModuleOrder
        val first = before.first()
        list.performScrollToNode(hasTestTag("module-drag-${first.storageKey}"))
        val start = compose.onNodeWithTag("module-drag-${first.storageKey}").fetchSemanticsNode().boundsInRoot
        list.performTouchInput { down(Offset(start.center.x - bounds.left, start.center.y - bounds.top)); moveBy(Offset(0f, 180f)) }
        compose.mainClock.advanceTimeBy(160)
        list.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { assertEquals(1, saves); assertEquals(before, settings.value.detailModuleOrder) }
    }

    @Test fun holdingDraggedModuleAtBottomScrollsToInitiallyHiddenRows() {
        val settings = mutableStateOf(base.copy(fontScale = 1.4f))
        compose.setContent { AniMeowTheme(settings.value) {
            DetailModuleCustomizationSheet(settings.value, { settings.value = settings.value.copy(detailModuleOrder = it) }, {}, {}, {})
        } }
        compose.mainClock.autoAdvance = false
        val list = compose.onNodeWithTag("detail-module-sort-list")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithTag("module-drag-header").fetchSemanticsNode().boundsInRoot
        list.performTouchInput {
            down(Offset(handle.center.x - bounds.left, handle.center.y - bounds.top))
            moveTo(Offset(handle.center.x - bounds.left, bounds.height - 6f), 300)
        }
        compose.mainClock.advanceTimeBy(2600)
        list.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { assertTrue("Drag should reach rows beyond the initial viewport", settings.value.detailModuleOrder.indexOf(DetailModule.HEADER) >= 6) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Semantics can be committed before SurfaceFlinger presents the corresponding frame.
        android.os.SystemClock.sleep(220)
        val root = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-213-review").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
