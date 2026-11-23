package com.animeow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.data.preferences.AppearancePreferences
import com.animeow.app.data.preferences.TrackerSettings
import com.animeow.app.ui.components.CoverRatingBadge
import com.animeow.app.ui.icons.*
import com.animeow.app.ui.preferences.ThemeColorControls
import com.animeow.app.ui.theme.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ThemeColorInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val base = AppearanceSettings(themeMode = ThemeMode.LIGHT, useDynamicColor = false,
        accentColor = 0xFF325DABL, accentGradient = AccentGradientSettings(AccentMode.FLOW, listOf(0xFFCE598AL)),
        ratingIconStyle = RatingIconStyle.STAR)

    @Test fun themeControlsKeepExactRgbAndNeverChangeAccentSettings() {
        val settings = mutableStateOf(base)
        compose.setContent {
            AniMeowTheme(settings.value) {
                Surface { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ThemeColorControls(settings.value) { settings.value = settings.value.copy(themeColor = it) }
                } }
            }
        }
        compose.onNodeWithText("独立设置主题色").performClick()
        compose.runOnIdle { assertEquals(base.accentColor, settings.value.themeColor) }
        compose.onNodeWithText("自定义").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("#010A2F")
        compose.onNodeWithText("使用此颜色").performClick()
        compose.runOnIdle {
            assertEquals(0xFF010A2FL, settings.value.themeColor)
            assertEquals(base.accentColor, settings.value.accentColor)
            assertEquals(base.accentGradient, settings.value.accentGradient)
        }
        compose.onNodeWithText("自定义").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("#010A2F")
        compose.onNode(hasSetTextAction()).performTextReplacement("#FDFEEE")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0xFF010A2FL, settings.value.themeColor) }
        compose.onNodeWithText("独立设置主题色").performClick()
        compose.runOnIdle { assertNull(settings.value.themeColor); assertEquals(base.accentGradient, settings.value.accentGradient) }
    }

    @Test fun renderedThemeAndAccentChangeIndependentlyIncludingSystemColorFallback() {
        val settings = mutableStateOf(base.copy(themeColor = 0xFF738550L))
        var scheme = lightColorScheme()
        var palette = emptyList<Color>()
        compose.setContent {
            AniMeowTheme(settings.value) {
                val currentScheme = MaterialTheme.colorScheme
                val currentAccent = LocalAccentPaint.current.colors
                SideEffect { scheme = currentScheme; palette = currentAccent }
                Surface { Text("独立主题色预览") }
            }
        }
        compose.waitForIdle()
        val beforeTheme = scheme
        val beforeAccent = palette.toList()
        compose.runOnIdle { settings.value = settings.value.copy(themeColor = 0xFF90537AL) }
        compose.runOnIdle { assertNotEquals(beforeTheme.primary, scheme.primary); assertNotEquals(beforeTheme.surface, scheme.surface); assertEquals(beforeAccent, palette) }
        fun themeColors() = listOf(scheme.primary, scheme.onPrimary, scheme.primaryContainer, scheme.secondary,
            scheme.background, scheme.surface, scheme.surfaceContainer, scheme.surfaceVariant)
        val fixedTheme = themeColors()
        compose.runOnIdle { settings.value = settings.value.withAccentPalette(listOf(0xFF092143L, 0xFFFB6A7EL)) }
        compose.runOnIdle { assertEquals(fixedTheme, themeColors()); assertNotEquals(beforeAccent, palette) }
        compose.runOnIdle { settings.value = settings.value.copy(useDynamicColor = true) }
        compose.runOnIdle { assertEquals(fixedTheme, themeColors()) }
        compose.runOnIdle { settings.value = settings.value.copy(themeColor = null, useDynamicColor = false) }
        compose.runOnIdle { assertNotEquals(fixedTheme.first(), scheme.primary) }
    }

    @Test fun ratingFollowsTheChosenSourceAndFlowStopsWithReducedMotion() {
        val settings = mutableStateOf(base.copy(themeColor = 0xFF568173L))
        val firstSource = mutableStateOf(RatingBadgeColorStyle.THEME_PRIMARY)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AniMeowTheme(settings.value) {
                Surface { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CoverRatingBadge("8.6", settings.value.copy(ratingBadgeColorStyle = firstSource.value),
                        TrackerSettings(), MaterialTheme.typography.titleMedium, Modifier.testTag("theme-rating"))
                    CoverRatingBadge("8.6", settings.value.copy(ratingBadgeColorStyle = RatingBadgeColorStyle.ACCENT),
                        TrackerSettings(), MaterialTheme.typography.titleMedium, Modifier.testTag("accent-rating"))
                } }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val themeBefore = compose.onNodeWithTag("theme-rating").captureToImage().asAndroidBitmap()
        val accentBefore = compose.onNodeWithTag("accent-rating").captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(2_000)
        assertTrue(themeBefore.sameAs(compose.onNodeWithTag("theme-rating").captureToImage().asAndroidBitmap()))
        assertFalse(accentBefore.sameAs(compose.onNodeWithTag("accent-rating").captureToImage().asAndroidBitmap()))
        compose.runOnUiThread { settings.value = settings.value.copy(motionLevel = MotionLevel.REDUCED) }
        compose.mainClock.advanceTimeByFrame()
        val paused = compose.onNodeWithTag("accent-rating").captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(2_000)
        assertTrue(paused.sameAs(compose.onNodeWithTag("accent-rating").captureToImage().asAndroidBitmap()))
        compose.runOnUiThread { settings.value = settings.value.copy(themeColor = null) }
        compose.mainClock.advanceTimeByFrame()
        val linkedTheme = compose.onNodeWithTag("theme-rating").captureToImage().asAndroidBitmap()
        assertFalse(themeBefore.sameAs(linkedTheme))
        // Compare sources at the same pixel position, avoiding rounded-edge subpixel differences.
        compose.runOnUiThread { firstSource.value = RatingBadgeColorStyle.ACCENT }
        compose.mainClock.advanceTimeByFrame()
        assertTrue(linkedTheme.sameAs(compose.onNodeWithTag("theme-rating").captureToImage().asAndroidBitmap()))
        compose.mainClock.autoAdvance = true
    }

    @Test fun separateColorsSurviveDataStoreReloadAndRemovingTheOverride() = runBlocking {
        val preferences = AppearancePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = preferences.snapshot()
        try {
            preferences.restore(base)
            preferences.setThemeColor(0xFF142C49L)
            preferences.setAccentColor(0xFFDB1B9AL)
            val restored = AppearancePreferences(InstrumentationRegistry.getInstrumentation().targetContext).snapshot()
            assertEquals(0xFF142C49L, restored.themeColor)
            assertEquals(0xFFDB1B9AL, restored.accentColor)
            assertEquals(base.accentGradient, restored.accentGradient)
            preferences.setThemeColor(null)
            assertNull(preferences.snapshot().themeColor)
            assertEquals(restored.accentColor, preferences.snapshot().accentColor)
        } finally { preferences.restore(original) }
    }

    @Test fun reviewSeparateThemeAndAccentWithReadableScores() {
        val settings = mutableStateOf(base.copy(themeColor = 0xFF74875CL, accentGradient = base.accentGradient.copy(mode = AccentMode.GRADIENT),
            icons = IconSettings(color = IconColorSettings(IconColorMode.ACCENT))))
        compose.setContent {
            AniMeowTheme(settings.value) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("主题色与强调色", style = MaterialTheme.typography.headlineMedium)
                        ThemeColorControls(settings.value) { settings.value = settings.value.copy(themeColor = it) }
                        ElevatedCard { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("页面与卡片使用主题色", style = MaterialTheme.typography.titleMedium)
                            Button(onClick = {}) { Text("通用操作") }
                        } }
                        Text("强调色 · 图标与渐变评分", style = MaterialTheme.typography.titleMedium)
                        Box(Modifier.fillMaxWidth().height(44.dp).clip(MaterialTheme.shapes.medium).accentBackground(LocalAccentPaint.current))
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            AppIcon(AppIcons.Discover, "发现", Modifier.size(28.dp))
                            AppIcon(AppIcons.Calendar, "日历", Modifier.size(28.dp))
                            AppIcon(AppIcons.Profile, "我的", Modifier.size(28.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("跟随主题色", style = MaterialTheme.typography.labelLarge)
                                CoverRatingBadge("8.6", settings.value.copy(ratingBadgeColorStyle = RatingBadgeColorStyle.THEME_PRIMARY), TrackerSettings(), MaterialTheme.typography.titleMedium)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("跟随强调色", style = MaterialTheme.typography.labelLarge)
                                CoverRatingBadge("8.6", settings.value.copy(ratingBadgeColorStyle = RatingBadgeColorStyle.ACCENT), TrackerSettings(), MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            compose.runOnIdle { settings.value = settings.value.copy(themeMode = theme) }
            saveAppearanceFollowup(compose, "separate-colors-${theme.storageKey}")
        }
    }
}
