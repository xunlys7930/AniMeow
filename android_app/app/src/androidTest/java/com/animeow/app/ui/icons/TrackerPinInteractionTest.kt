package com.animeow.app.ui.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.animeow.app.AniMeowApplication
import com.animeow.app.R
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.preferences.TrackerPreferences
import com.animeow.app.data.preferences.TrackerSettings
import com.animeow.app.ui.screens.TrackerScreen
import com.animeow.app.ui.theme.AniMeowTheme
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.HomeLayout
import com.animeow.app.ui.theme.MotionLevel
import com.animeow.app.ui.tracker.TrackerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import java.io.File

class TrackerPinInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longPressPinsAndDirectUnpinCanBeUndoneWhileTheFirstMessageIsVisible(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AniMeowApplication>()
        val preferences = TrackerPreferences(app)
        val originalSettings = preferences.snapshot()
        val dao = app.database.libraryDao()
        val store = ViewModelStore()
        val title = "置顶验证·星野放映室"
        val cover = File(app.cacheDir, "pin-test-cover.png")
        val art = requireNotNull(app.getDrawable(R.drawable.brand_mint_preview))
        val bitmap = Bitmap.createBitmap(432, 576, Bitmap.Config.ARGB_8888)
        art.setBounds(0, 72, 432, 504)
        Canvas(bitmap).apply { drawColor(0xFFE1EFE9.toInt()); art.draw(this) }
        cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val id = dao.insertAnime(AnimeEntity(title = title, coverUrl = cover.toURI().toString(), watchedEpisodes = 3, totalEpisodes = 12))
        try {
            preferences.save(TrackerSettings(groupSeriesOnHome = false, showContinueWatching = false,
                showRecentAdded = false, showStandaloneBookshelf = false))
            lateinit var tracker: TrackerViewModel
            compose.runOnUiThread {
                tracker = TrackerViewModel(app)
                store.put("tracker", tracker)
                tracker.setSearchQuery("置顶验证")
            }
            val appearance = AppearanceSettings(homeLayout = HomeLayout.POSTER_WALL, motionLevel = MotionLevel.NONE)
            compose.setContent {
                AniMeowTheme(appearance) {
                    TrackerScreen(settings = appearance, viewModel = tracker,
                        onAnimeClick = {}, onSeriesClick = {}, onHomeLayoutSelected = {}, onDetailLayoutSelected = {},
                        onContentDensitySelected = {}, onMotionLevelSelected = {}, onFontScaleChanged = {}, onCornerScaleChanged = {},
                        onGridColumnsChanged = {}, onCoverAspectRatioSelected = {}, onCoverTitlePositionSelected = {},
                        onShowTitleChanged = {}, onShowStatusChanged = {}, onShowRatingChanged = {}, onShowProgressChanged = {},
                        onRatingBadgeColorStyleSelected = {}, onRatingBadgeCustomColorSelected = {},
                        onCardSwipeActionsEnabledChanged = {}, onSwipeStartActionSelected = {}, onSwipeEndActionSelected = {},
                        onAnimeEdit = {}, onAutoCompleteStatusChanged = {}, onCompletionStatusSelected = {})
                }
            }
            compose.waitUntil(10_000) { !tracker.libraryState.value.isLoading && tracker.libraryState.value.items.isNotEmpty() }
            compose.onNodeWithText(title).performScrollTo().performTouchInput { longClick() }
            compose.onNodeWithText("置顶", substring = false).performClick()
            compose.waitUntil(10_000) { runBlocking { dao.getAnime(id)?.pinnedAt != null } }
            compose.onNodeWithText("置顶作品 · 1").assertIsDisplayed()
            val capture = compose.onRoot().captureToImage().asAndroidBitmap()
            val output = File(app.getExternalFilesDir(null), "icon-qa/home-pinned.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { capture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithContentDescription("取消置顶《$title》").performClick()
            compose.waitUntil(10_000) { runBlocking { dao.getAnime(id)?.pinnedAt == null } }
            compose.onNodeWithText("已取消置顶 1 部作品").assertIsDisplayed()
            compose.onNodeWithText("撤销").performClick()
            compose.waitUntil(10_000) { runBlocking { dao.getAnime(id)?.pinnedAt != null } }
            assertNotNull(dao.getAnime(id)?.pinnedAt)
            compose.onNodeWithText("置顶作品 · 1").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { store.clear() }
            preferences.save(originalSettings)
            dao.deleteAnime(id)
            cover.delete()
        }
    }
}
