package com.animeow.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.remote.ImageSearchMatch
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.RemoteCatalogSource
import com.animeow.app.ui.discovery.ImageSearchContent
import com.animeow.app.ui.discovery.ImageSearchUiState
import com.animeow.app.ui.icons.AppIconId
import com.animeow.app.ui.icons.IconSettings
import com.animeow.app.ui.icons.IconStyle
import com.animeow.app.ui.preferences.IconBrowserSheet
import com.animeow.app.ui.theme.AniMeowTheme
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.MotionLevel
import com.animeow.app.ui.theme.ThemeMode
import com.animeow.app.ui.tracker.PinnedAnimeShelf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class MobileVisualAuditTest {
    @get:Rule val compose = createComposeRule()
    private val appearance = AppearanceSettings(themeMode = ThemeMode.LIGHT, useDynamicColor = false, motionLevel = MotionLevel.NONE,
        accentColor = 0xFF7A5AB8, icons = IconSettings(style = IconStyle.LINE, animated = false))

    @Test fun imageSearchEmptyStateHasAClearPrimaryActionAndGuidance() {
        var chosen = false
        compose.setContent { AniMeowTheme(appearance) {
            Scaffold(topBar = { TopAppBar(title = { Text("以图搜番") }) }) { padding ->
                ImageSearchContent(ImageSearchUiState(), { chosen = true }, {}, {}, {}, Modifier.padding(padding))
            }
        } }
        compose.onNodeWithText("选择动画截图").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(chosen) }
        compose.onNodeWithText("怎样的截图更容易找到？").assertIsDisplayed()
        screenshot("image-search-empty-light")
    }

    @Test fun imageResultLongTitlesKeepFullWidthActionAndEpisodeDetailsAtLargeFontSize() {
        val state = mutableStateOf(ImageSearchUiState(imageUri = fixture(), hasSearched = true, matches = listOf(match())))
        var imported = 0L
        compose.setContent { AniMeowTheme(appearance.copy(themeMode = ThemeMode.DARK, fontScale = 1.4f, icons = IconSettings(style = IconStyle.SOFT))) {
            Scaffold(topBar = { TopAppBar(title = { Text("以图搜番") }) }) { padding ->
                ImageSearchContent(state.value, {}, {}, {}, { imported = it.anime.id.toLong() }, Modifier.padding(padding))
            }
        } }
        compose.onNodeWithText("加入资料库").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("相似度 97.8%").assertIsDisplayed()
        compose.onNodeWithText("第 7 集").assertIsDisplayed()
        compose.onNodeWithText("04:32 – 04:36").assertIsDisplayed()
        screenshot("image-search-result-dark-large")
        compose.onNodeWithText("加入资料库").performClick()
        compose.runOnIdle { assertEquals(42L, imported) }
    }

    @Test fun imageResultPreviewLight() {
        compose.setContent { AniMeowTheme(appearance) {
            Scaffold(topBar = { TopAppBar(title = { Text("以图搜番") }) }) { padding ->
                ImageSearchContent(ImageSearchUiState(imageUri = fixture(), hasSearched = true, matches = listOf(match())), {}, {}, {}, {}, Modifier.padding(padding))
            }
        } }
        compose.onNodeWithText("加入资料库").performScrollTo()
        screenshot("image-search-result-light")
    }

    @Test fun searchingCanCancelAndNoMatchOffersAnotherImage() {
        val uri = fixture()
        val state = mutableStateOf(ImageSearchUiState(imageUri = uri, isSearching = true))
        var chosen = false
        compose.setContent { AniMeowTheme(appearance) { Surface {
            ImageSearchContent(state.value, { chosen = true }, {}, { state.value = state.value.copy(isSearching = false, hasSearched = true) }, {})
        } } }
        compose.onNodeWithText("取消").performScrollTo().performClick()
        compose.onNodeWithText("换一张截图试试").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(chosen) }
        compose.onNodeWithText("还没找到相近的画面").assertIsDisplayed()
        screenshot("image-search-no-match")
    }

    @Test fun imageFailureRemainsReadableInDarkMode() {
        var retried = false
        compose.setContent { AniMeowTheme(appearance.copy(themeMode = ThemeMode.DARK)) { Surface {
            ImageSearchContent(ImageSearchUiState(imageUri = fixture(), error = "识别服务暂时繁忙，请稍等一会儿再试"), {}, { retried = true }, {}, {})
        } } }
        compose.onNodeWithText("重新识别").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(retried) }
        screenshot("image-search-error-dark")
    }

    @Test fun repeatedFlingsAtBothGridEdgesKeepTheSheetAndLastRowStable() {
        compose.setContent { AniMeowTheme(appearance) { IconBrowserSheet(IconSettings(animated = false), { _, _ -> }, {}) } }
        val grid = compose.onNodeWithTag("icon-browser-grid")
        grid.performScrollToIndex(AppIconId.entries.lastIndex)
        val titleTop = compose.onNodeWithText("逐个定制").fetchSemanticsNode().boundsInWindow.top
        repeat(8) {
            grid.performTouchInput { swipeUp(durationMillis = 150) }
            compose.waitForIdle()
            assertEquals(titleTop, compose.onNodeWithText("逐个定制").fetchSemanticsNode().boundsInWindow.top, 2f)
        }
        screenshot("icon-sheet-bottom-stable")
        grid.performScrollToIndex(0)
        repeat(6) {
            grid.performTouchInput { swipeDown(durationMillis = 150) }
            compose.waitForIdle()
            assertEquals(titleTop, compose.onNodeWithText("逐个定制").fetchSemanticsNode().boundsInWindow.top, 2f)
        }
        compose.onNodeWithText("完成").assertIsDisplayed()
    }

    @Test fun pinBadgeIsSmallWhileItsClickTargetStillWorks() {
        var unpinned = false
        var opened = false
        val anime = AnimeEntity(id = 77, title = "星海旅人", coverUrl = fixture().toString(), pinnedAt = 1)
        compose.setContent { AniMeowTheme(appearance) { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp)) { PinnedAnimeShelf(listOf(anime), { opened = true }, { unpinned = true }) }
        } } }
        compose.onNodeWithTag("pin-badge-77", useUnmergedTree = true).assertWidthIsEqualTo(22.dp).assertHeightIsEqualTo(22.dp)
        screenshot("pin-badge-small")
        compose.onNodeWithContentDescription("取消置顶《星海旅人》").performClick()
        compose.runOnIdle { assertTrue(unpinned); assertFalse(opened) }
    }

    private fun match() = ImageSearchMatch(RemoteAnime(RemoteCatalogSource.ANILIST, "42", "星海旅人：从雨后的车站出发，去寻找远方的光", originalTitle = "星の旅人 — A Journey Beyond the Sky"),
        7.0, 272.0, 276.0, 0.978, fixture().toString(), null, null)

    private fun fixture(): Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "image-search-qa-scene.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.rgb(174, 195, 217))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = android.graphics.Color.rgb(255, 224, 179); canvas.drawCircle(720f, 130f, 62f, paint)
            paint.color = android.graphics.Color.rgb(127, 150, 184); canvas.drawRect(0f, 310f, 960f, 540f, paint)
            paint.color = android.graphics.Color.rgb(75, 99, 134); canvas.drawRect(0f, 400f, 960f, 540f, paint)
            paint.color = android.graphics.Color.rgb(34, 49, 74); canvas.drawRect(118f, 148f, 134f, 470f, paint); canvas.drawRect(90f, 135f, 400f, 156f, paint)
            paint.color = android.graphics.Color.WHITE; paint.textSize = 28f
            canvas.drawText("The journey has only just begun.", 248f, 504f, paint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        return Uri.fromFile(file)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "mobile-followup-qa/$name.png")
        file.parentFile!!.mkdirs()
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
