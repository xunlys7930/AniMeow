package com.animeow.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.data.preferences.TrackerSettings
import com.animeow.app.ui.preferences.*
import com.animeow.app.ui.theme.*
import java.io.File
import org.junit.Rule
import org.junit.Test

class AppearancePresetReviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun suiteAndPaletteChoicesRemainReadableInLightDarkAndLargeText() {
        val settings = mutableStateOf(AppearanceSuite.QUIET.apply(AppearanceSettings()))
        val page = mutableStateOf("choices")
        compose.setContent { AniMeowTheme(settings.value) {
            Surface(Modifier.fillMaxSize()) {
                key(page.value, settings.value.fontScale, settings.value.themeMode) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("主题与配色", style = MaterialTheme.typography.titleLarge)
                        if (page.value == "choices") {
                            AppearanceSuitePicker(settings.value, { settings.value = it.apply(settings.value) })
                            AccentPalettePresets(settings.value) { settings.value = it.apply(settings.value) }
                        } else {
                            Text(page.value, style = MaterialTheme.typography.titleMedium)
                            HomePagePreview(settings.value, TrackerSettings())
                            AccentGradientControls(settings.value.accentGradient,
                                { settings.value = settings.value.copy(accentGradient = it) },
                                { settings.value = settings.value.withAccentPalette(it) })
                        }
                    }
                }
            }
        } }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (scale in listOf(1f, 1.4f)) {
            compose.runOnIdle { settings.value = settings.value.copy(themeMode = theme, fontScale = scale) }
            compose.onNodeWithText("灵动聚合").assertIsDisplayed()
            compose.onNodeWithText("流光网格").assertIsDisplayed()
            screenshot("appearance-choices-${theme.storageKey}-${(scale * 100).toInt()}")
            compose.onNodeWithText("晴空蓝 · 纯色").performScrollTo().assertIsDisplayed()
        }
        for (suite in listOf(AppearanceSuite.QUIET, AppearanceSuite.GLASS)) {
            compose.runOnIdle {
                settings.value = suite.apply(settings.value)
                page.value = suite.label
            }
            screenshot("appearance-suite-${suite.name.lowercase()}")
        }
    }

    @Test fun cloudConfigurationExplanationAndConfirmationFitLargeText() {
        compose.setContent { AniMeowTheme(AppearanceSettings(themeMode = ThemeMode.DARK, useDynamicColor = false, fontScale = 1.4f)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("配置档案", style = MaterialTheme.typography.titleLarge)
                    CloudConfigurationCard(true, "合成测试账号", false, false, {}, {}, {})
                }
            }
        } }
        compose.onNodeWithText("保存到云端").assertIsDisplayed()
        screenshot("cloud-configuration-dark-140")
        compose.onNodeWithText("保存到云端").performClick()
        compose.onNodeWithText("确认保存").assertIsDisplayed()
        screenshot("cloud-configuration-confirm-dark-140")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(220)
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "mobile-213-review/$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
