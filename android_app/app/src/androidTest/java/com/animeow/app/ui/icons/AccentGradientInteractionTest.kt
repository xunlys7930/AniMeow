package com.animeow.app.ui.icons

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.animeow.app.ui.preferences.AccentGradientControls
import com.animeow.app.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AccentGradientInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun advancedControlsEditStopsDirectionAndSpeed() {
        var settings by mutableStateOf(AppearanceSettings(accentColor = 0xFF547EB0L, useDynamicColor = false))
        compose.setContent {
            AniMeowTheme(settings) {
                Surface { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    AccentGradientControls(settings.accentGradient,
                        onChange = { settings = settings.copy(accentGradient = it) },
                        onPalette = { settings = settings.withAccentPalette(it) })
                } }
            }
        }
        compose.onNodeWithText("流动").performClick()
        compose.onNodeWithText("添加色点").performScrollTo().performClick()
        compose.onNodeWithText("添加色点").performClick()
        compose.runOnIdle { assertEquals(3, settings.accentGradient.colors.size) }
        compose.onNodeWithContentDescription("编辑渐变色点 2").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("#8E74B8")
        compose.onNodeWithText("使用此颜色").performClick()
        compose.runOnIdle { assertEquals(0xFF8E74B8L, settings.accentGradient.colors.first()) }
        compose.onNodeWithContentDescription("渐变方向").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(140f) }
        compose.onNodeWithContentDescription("强调色流动周期").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(16_000f) }
        compose.runOnIdle {
            assertEquals(140f, settings.accentGradient.angleDegrees, 1f)
            assertEquals(16_000, settings.accentGradient.durationMillis)
        }
        compose.onNodeWithText("高级渐变设置").performScrollTo()
        save(compose.onRoot().captureToImage().asAndroidBitmap(), "accent-controls.png")
        compose.onNodeWithContentDescription("删除渐变色点 2").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, settings.accentGradient.colors.size) }
        val previousEnd = settings.accentGradient.colors.last()
        compose.onNodeWithText("反转配色").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(previousEnd, settings.accentColor)
            assertEquals(2, settings.accentGradient.colors.size)
        }
    }

    @Test fun firstStopAndReversalPreserveExactRgbInLightAndDarkThemes() {
        val palette = listOf(0xFF06072AL, 0xFFFFFEE8L, 0xFFEB1FCEL)
        val settings = mutableStateOf(AppearanceSettings(useDynamicColor = false, themeMode = ThemeMode.LIGHT,
            accentColor = palette.first(), accentGradient = AccentGradientSettings(AccentMode.GRADIENT, palette.drop(1))))
        var rendered = emptyList<Long>()
        mountPalette(settings) { rendered = it }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            compose.runOnIdle { settings.value = settings.value.copy(themeMode = theme) }
            compose.onNodeWithContentDescription("编辑渐变色点 1").performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).assertTextContains("#06072A")
            compose.onNodeWithText("使用此颜色").performClick()
            compose.runOnIdle { assertEquals(palette, rendered) }
            compose.onNodeWithText("反转配色").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(palette.reversed(), rendered) }
            compose.onNodeWithText("反转配色").performClick()
            compose.runOnIdle { assertEquals(palette, rendered) }
        }
    }

    @Test fun primaryHueAndBrightnessAreNotClampedAndCancelKeepsThePreviousPalette() {
        val settings = mutableStateOf(AppearanceSettings(useDynamicColor = false, themeMode = ThemeMode.LIGHT,
            accentColor = 0xFF3366EEL, accentGradient = AccentGradientSettings(AccentMode.GRADIENT, listOf(0xFFEAF2FFL))))
        mountPalette(settings)
        compose.onNodeWithContentDescription("编辑渐变色点 1").performScrollTo().performClick()
        compose.onNodeWithContentDescription("色相").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(285f) }
        compose.onNodeWithContentDescription("明度").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.12f) }
        compose.onNodeWithText("使用此颜色").performClick()
        var saved = 0L
        compose.runOnIdle {
            saved = settings.value.accentColor!!
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(saved.toInt(), hsv)
            assertEquals(285f, hsv[0], 3f)
            assertEquals(0.12f, hsv[2], 1f / 255f)
        }
        compose.onNodeWithContentDescription("编辑渐变色点 1").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("#%06X".format(saved and 0xFFFFFFL))
        compose.onNode(hasSetTextAction()).performTextReplacement("#FFFFFF")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(saved, settings.value.accentColor) }
        compose.onNodeWithContentDescription("编辑渐变色点 1").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("#0102FE")
        compose.onNodeWithText("使用此颜色").performClick()
        compose.runOnIdle { assertEquals(0xFF0102FEL, settings.value.accentColor) }
    }

    private fun mountPalette(settings: MutableState<AppearanceSettings>, onRendered: (List<Long>) -> Unit = {}) {
        compose.setContent {
            AniMeowTheme(settings.value) {
                val palette = LocalAccentPaint.current.colors.map { it.toArgb().toLong() and 0xFFFFFFFFL }
                SideEffect { onRendered(palette) }
                Surface { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    AccentGradientControls(settings.value.accentGradient,
                        onChange = { settings.value = settings.value.copy(accentGradient = it) },
                        onPalette = { settings.value = settings.value.withAccentPalette(it) })
                } }
            }
        }
    }

    @Test fun flowChangesPixelsAndReducedMotionKeepsTheGradientStill() {
        var motion by mutableStateOf(MotionLevel.FULL)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AniMeowTheme(AppearanceSettings(accentColor = 0xFF4E79A8L, motionLevel = motion,
                icons = IconSettings(style = IconStyle.DUO, color = IconColorSettings(IconColorMode.ACCENT)),
                accentGradient = AccentGradientSettings(AccentMode.FLOW, listOf(0xFF956FADL, 0xFFCD879CL), 30f, 8_000))) {
                Surface { Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("自己的配色，轻轻流动", style = MaterialTheme.typography.titleMedium)
                    Box(Modifier.fillMaxWidth().height(64.dp).clip(MaterialTheme.shapes.large).accentBackground(LocalAccentPaint.current))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf(AppIcons.Discover, AppIcons.HeartFilled, AppIcons.Calendar, AppIcons.Profile).forEach {
                            AppIcon(it, null, Modifier.size(36.dp))
                        }
                    }
                    Text("自定义渐变 · 图标与强调色同步", style = MaterialTheme.typography.bodySmall)
                } }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val before = compose.onRoot().captureToImage().asAndroidBitmap()
        save(before, "accent-flow-00.png")
        for (i in 1..32) {
            compose.mainClock.advanceTimeBy(250)
            val frame = compose.onRoot().captureToImage().asAndroidBitmap()
            if (i == 8) assertFalse(before.sameAs(frame))
            save(frame, "accent-flow-${i.toString().padStart(2, '0')}.png")
        }
        compose.runOnUiThread { motion = MotionLevel.REDUCED }
        compose.mainClock.advanceTimeByFrame()
        val stopped = compose.onRoot().captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(2_000)
        assertTrue(stopped.sameAs(compose.onRoot().captureToImage().asAndroidBitmap()))
        compose.mainClock.autoAdvance = true
    }

    private fun save(bitmap: Bitmap, name: String) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.getExternalFilesDir(null), "icon-qa/$name")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
