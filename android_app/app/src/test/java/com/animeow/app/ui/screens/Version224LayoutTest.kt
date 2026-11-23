package com.animeow.app.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.preferences.CollectionPosterPreset
import com.animeow.app.data.preferences.DiscoveryDisplayConfig
import com.animeow.app.data.preferences.TrackerSettings
import com.animeow.app.data.preferences.withCollectionPosterStyle
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.RemoteCatalogSource
import com.animeow.app.ui.calendar.CalendarEvent
import com.animeow.app.ui.calendar.CalendarEventType
import com.animeow.app.ui.calendar.CalendarUiState
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.ContentDensity
import java.io.File
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Version224LayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun selectingAnotherDayReplacesTheAgendaAndAnEmptyDayHasItsOwnMessage() {
        val start = LocalDate.of(2026, 11, 10)
        val events = listOf(
            CalendarEvent("first", 1, start, "周二的日程", "", CalendarEventType.AIR_DATE),
            CalendarEvent("second", 2, start.plusDays(1), "周三的日程", "", CalendarEventType.AIR_DATE),
        )
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent {
                var date by remember { mutableStateOf(start) }
                MaterialTheme { WeekContent(CalendarUiState(selectedDate = date, events = events), { date = it }, {}, {}) }
            }
            controller.visible()
            compose.onNodeWithText("周二的日程").assertIsDisplayed()
            compose.onNodeWithText("周三的日程").assertDoesNotExist()
            compose.onNodeWithText("11").performClick()
            compose.onNodeWithText("周三的日程").assertIsDisplayed()
            compose.onNodeWithText("周二的日程").assertDoesNotExist()
            compose.onNodeWithText("12").performClick()
            compose.onNodeWithText("当天没有日历事件").assertIsDisplayed()
        }
    }

    @Test fun narrowSearchCardsAndBothCollectionPresetsRenderTheirImportantFacts() {
        for (dark in listOf(false, true)) {
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                val work = AnimeEntity(title = "用于预览的较长动画标题", status = "在看", rating = 88,
                    coverUrl = "android.resource://${activity.packageName}/drawable/preview_cover",
                    watchedEpisodes = 3, totalEpisodes = 12, broadcastDay = 4)
                val results = (1..4).map { index ->
                    RemoteAnime(RemoteCatalogSource.BANGUMI, index.toString(), "结果 $index",
                        coverUrl = work.coverUrl, episodes = index + 20, airDate = "1998-10-0$index")
                }
                activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                            Surface {
                                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    DiscoveryGridRow(results, DiscoveryDisplayConfig(gridColumns = 4, density = ContentDensity.COMPACT),
                                        6.dp, onClick = {}, onImport = {})
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CollectionPosterPreset.entries.forEach { preset ->
                                            Column(Modifier.weight(1f)) {
                                                PosterAnimeCard(work, AppearanceSettings().withCollectionPosterStyle(), preset.apply(TrackerSettings()),
                                                    selected = false, onClick = {}, onLongClick = {}, swipeEnabled = false, onSwipe = { _, _ -> })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                controller.visible()
                compose.onNodeWithText("21 集").assertIsDisplayed()
                compose.onNodeWithText("1998\n10-01").assertIsDisplayed()
                compose.onNodeWithText("周四更新").assertIsDisplayed()
                val root = activity.window.decorView
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                val file = File("../../build/android-2.2.4-layout/${if (dark) "dark" else "light"}.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }
}
