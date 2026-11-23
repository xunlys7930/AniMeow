package com.animeow.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.R
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.preferences.TrackerSettings
import com.animeow.app.ui.screens.LargeSeriesCard
import com.animeow.app.ui.screens.PosterAnimeCard
import com.animeow.app.ui.screens.PosterSeriesCard
import com.animeow.app.ui.theme.*
import com.animeow.app.ui.tracker.LibraryDisplayItem
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SeriesCoverLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val base = AppearanceSettings(showTitle = false, showStatus = false, showRating = false,
        showProgress = false, showSeriesEditButton = false, showPinBadges = false)
    private val tracker = TrackerSettings(showCoverSubjectType = false, showCoverUpdateWeekday = false,
        showCoverProgress = true)
    private val work = AnimeEntity(id = 1, title = "星海旅人", watchedEpisodes = 3, totalEpisodes = 12)
    private val series = LibraryDisplayItem.SeriesItem(SeriesEntity(id = 1, name = "星海系列"), listOf(work))

    @Test fun hiddenSeriesInformationHasExactlyTheCoverHeightAcrossThemesFontsAndRatios() {
        val settings = mutableStateOf(base)
        var clicks = 0
        var longClicks = 0
        compose.setContent {
            AniMeowTheme(settings.value) {
                Box(Modifier.width(120.dp).testTag("card")) {
                    PosterSeriesCard(series, settings.value, tracker, false, { clicks++ }, { longClicks++ })
                }
            }
        }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (font in listOf(1f, 1.4f)) {
            for (ratio in CoverAspectRatio.entries) {
                compose.runOnIdle { settings.value = base.copy(themeMode = theme, fontScale = font, coverAspectRatio = ratio) }
                compose.onNodeWithTag("card").assertHeightIsEqualTo((120 / ratio.ratio).dp)
            }
        }
        compose.onNodeWithTag("card").performTouchInput { click(center) }
        compose.onNodeWithTag("card").performTouchInput { longClick(center) }
        compose.runOnIdle { assertEquals(1, clicks); assertEquals(1, longClicks) }
    }

    @Test fun footerAppearsOnlyForActualTitleOrMetadataAndNeverForProgressOrAnEmptySeries() {
        val settings = mutableStateOf(base)
        val options = mutableStateOf(tracker.copy(showCoverUpdateWeekday = true))
        val item = mutableStateOf(series)
        compose.setContent {
            AniMeowTheme(settings.value) {
                Box(Modifier.width(120.dp).testTag("card")) {
                    PosterSeriesCard(item.value, settings.value, options.value, false, {}, {})
                }
            }
        }
        val expected = (120 / base.coverAspectRatio.ratio).dp
        compose.onNodeWithTag("card").assertHeightIsEqualTo(expected)
        val coverHeight = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { settings.value = base.copy(showTitle = true, coverTitlePosition = CoverTitlePosition.BELOW) }
        assertTrue(compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot.height > coverHeight)
        compose.onNodeWithText(series.title).assertIsDisplayed()
        compose.runOnIdle { settings.value = settings.value.copy(coverTitlePosition = CoverTitlePosition.OVERLAY) }
        compose.onNodeWithTag("card").assertHeightIsEqualTo(expected)
        compose.runOnIdle { settings.value = base; item.value = series.copy(members = listOf(work.copy(broadcastDay = 3))) }
        assertTrue(compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot.height > coverHeight)
        compose.runOnIdle { options.value = tracker; item.value = series.copy(members = emptyList()) }
        compose.onNodeWithTag("card").assertHeightIsEqualTo(expected)
    }

    @Test fun largeSeriesKeepsRealDescriptionsButCollapsesAnEmptyDetailsArea() {
        val item = mutableStateOf(series)
        compose.setContent {
            AniMeowTheme(base) {
                Box(Modifier.width(280.dp).testTag("large")) {
                    LargeSeriesCard(item.value, base, tracker, false, {}, {})
                }
            }
        }
        val expected = (210 * base.densityFor("tracker").itemScale).dp
        compose.onNodeWithTag("large").assertHeightIsEqualTo(expected)
        val height = compose.onNodeWithTag("large").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { item.value = series.copy(series = series.series.copy(description = "保留用户填写的系列简介")) }
        compose.onNodeWithText("保留用户填写的系列简介").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("large").fetchSemanticsNode().boundsInRoot.height > height)
        compose.runOnIdle { item.value = series.copy(series = series.series.copy(description = "   ")) }
        compose.onNodeWithTag("large").assertHeightIsEqualTo(expected)
    }

    @Test fun reviewPureCoversBesideIndividualWorks() {
        val settings = mutableStateOf(base)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sample = work.copy(coverUrl = "android.resource://${context.packageName}/${R.drawable.preview_cover}")
        val sampleSeries = series.copy(members = listOf(sample, sample.copy(id = 2)))
        compose.setContent {
            AniMeowTheme(settings.value) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("纯封面 · 系列与单部对齐", style = MaterialTheme.typography.titleLarge)
                        Text("标题、评分、状态、进度均隐藏", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f)) { PosterAnimeCard(sample, settings.value, tracker, false, {}, {}, swipeEnabled = false, onSwipe = { _, _ -> }) }
                            Box(Modifier.weight(1f)) { PosterSeriesCard(sampleSeries, settings.value, tracker, false, {}, {}) }
                            Box(Modifier.weight(1f)) { PosterAnimeCard(sample, settings.value, tracker, false, {}, {}, swipeEnabled = false, onSwipe = { _, _ -> }) }
                        }
                        Text("大卡片同样收起空白区域", style = MaterialTheme.typography.titleMedium)
                        LargeSeriesCard(sampleSeries, settings.value, tracker, false, {}, {})
                    }
                }
            }
        }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            compose.runOnIdle { settings.value = base.copy(themeMode = theme) }
            saveAppearanceFollowup(compose, "series-covers-${theme.storageKey}")
        }
    }
}

internal fun saveAppearanceFollowup(compose: androidx.compose.ui.test.junit4.ComposeTestRule, name: String) {
    compose.waitForIdle()
    // Local Coil resources finish decoding outside Compose's idling clock.
    Thread.sleep(400)
    compose.waitForIdle()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val file = File(instrumentation.targetContext.getExternalFilesDir(null), "appearance-followup-213/$name.png")
    file.parentFile!!.mkdirs()
    // Capture the rendered component, excluding system bars and transient window frames.
    val bitmap = compose.onRoot().assertIsDisplayed().captureToImage().asAndroidBitmap()
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}
