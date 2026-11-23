package com.animeow.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.community.CommunityComposer
import com.animeow.app.ui.community.FeedSortChips
import com.animeow.app.ui.screens.ThemeModePreference
import com.animeow.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MobileInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun darkModeCanReturnToFollowingSystem() {
        val theme = mutableStateOf(ThemeMode.DARK)
        compose.setContent { MaterialTheme { ThemeModePreference(theme.value) { theme.value = it } } }
        compose.onNodeWithText("深色模式").performClick()
        compose.onNodeWithText("跟随系统").performClick()
        compose.runOnIdle { assertEquals(ThemeMode.SYSTEM, theme.value) }
        compose.onNodeWithText("跟随系统").assertIsDisplayed()
    }

    @Test
    fun allFeedSortOptionsRemainReachableWithLargeTextAndKeepFeaturedFilter() {
        val sort = mutableStateOf("newest")
        val featured = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.4f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        FeedSortChips(sort.value, featured.value) { order, onlyFeatured ->
                            sort.value = order
                            featured.value = onlyFeatured
                        }
                    }
                }
            }
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("精华"))
        compose.onNodeWithText("精华").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("点赞最多"))
        compose.onNodeWithText("点赞最多").performClick()
        compose.runOnIdle {
            assertEquals("likes", sort.value)
            assertTrue(featured.value)
        }
    }

    @Test
    fun composerValidatesEmptyPostAndAllowsEditingAFullStickerSelection() {
        val text = mutableStateOf("")
        val presets = mutableStateOf(emptyList<String>())
        var published = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    CommunityComposer(
                        text = text.value, images = emptyList(), draftPresetMedia = presets.value,
                        maxImages = 2, maxTextLength = 2000, busy = false, busyLabel = null,
                        presetImages = emptyList(), presetDisclaimer = "", onTextChange = { text.value = it },
                        onPickImages = {}, onRemoveImage = {}, onRemovePresetMedia = {},
                        onMoveImage = { _, _ -> }, onMovePresetMedia = { _, _ -> }, onCropPresetMedia = {},
                        onPickPresetMedia = {}, onConfirmPresetSelection = {}, onPublish = { published++ },
                    )
                }
            }
        }
        compose.onNodeWithText("发布").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("本地测试草稿")
        compose.onNodeWithText("发布").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, published)
            presets.value = listOf("fixture-one.png", "fixture-two.png")
        }
        compose.onNodeWithContentDescription("选择图片").assertIsNotEnabled()
        compose.onNodeWithContentDescription("选择贴纸/表情").assertIsEnabled()
        compose.onNodeWithContentDescription("左移贴纸 1").assertIsNotEnabled()
        compose.onNodeWithContentDescription("右移贴纸 2").assertIsNotEnabled()
        compose.onNodeWithContentDescription("移除贴纸 1").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }
}
