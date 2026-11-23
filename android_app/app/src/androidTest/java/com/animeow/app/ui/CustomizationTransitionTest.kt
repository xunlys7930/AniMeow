package com.animeow.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.preferences.AppearancePreferences
import com.animeow.app.data.preferences.ConfigurationBundle
import com.animeow.app.data.preferences.ConfigurationProfileRepository
import com.animeow.app.ui.icons.IconSettings
import com.animeow.app.ui.icons.IconStyle
import com.animeow.app.ui.legacy.AniMeowFrontendTheme
import com.animeow.app.ui.preferences.CustomizationScreen
import com.animeow.app.ui.preferences.CustomizationViewModel
import com.animeow.app.ui.theme.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CustomizationTransitionTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AniMeowApplication
    private var original: ConfigurationBundle? = null
    private val store = ViewModelStore()
    private lateinit var model: CustomizationViewModel
    private val starting = AppearanceSettings(frontendMode = FrontendMode.MODERN, frontendModeChoiceMade = true,
        themeMode = ThemeMode.LIGHT, useDynamicColor = false, accentColor = 0xFF7362AC, cornerScale = 0.8f,
        motionLevel = MotionLevel.NONE, icons = IconSettings(style = IconStyle.DUO, animated = false))

    private fun start() {
        runBlocking {
            original = ConfigurationProfileRepository(app).snapshotCurrent()
            AppearancePreferences(app).restore(starting)
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[CustomizationViewModel::class.java]
        }
        compose.setContent {
            val settings by model.settings.collectAsStateWithLifecycle()
            AniMeowTheme(settings) { AniMeowFrontendTheme(settings) {
                CustomizationScreen(onBack = {}, onBrandingRequested = {}, viewModel = model)
            } }
        }
        compose.waitUntil(10_000) { model.settings.value == starting }
        compose.onNodeWithText("经典书架").performScrollTo().performClick()
    }

    @After fun restore() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        original?.let { runBlocking { ConfigurationProfileRepository(app).applyConfiguration(it) } }
    }

    @Test fun classicPresetConfirmsThenUndoRestoresTheActualPreviousSettings() {
        start()
        compose.onNodeWithText("切换到经典界面？").assertIsDisplayed()
        assertEquals(FrontendMode.MODERN, model.settings.value.frontendMode)
        screenshot("classic-preset-confirm")
        compose.onNodeWithText("保留当前界面").performClick()
        assertEquals(FrontendMode.MODERN, model.settings.value.frontendMode)
        compose.onNodeWithText("经典书架").performClick()
        compose.onNodeWithText("确认切换").performClick()
        compose.waitUntil(5_000) { model.settings.value.frontendMode == FrontendMode.LEGACY }
        compose.onNodeWithText("外观定制").assertIsDisplayed()
        compose.onNodeWithText("已切换经典界面。返回新版：我的 → 设置 → 界面版本。").assertIsDisplayed()
        screenshot("classic-preset-undo")
        compose.onNodeWithText("撤销").performClick()
        compose.waitUntil(5_000) { model.settings.value.frontendMode == FrontendMode.MODERN }
        assertEquals(starting, model.settings.value)
        compose.onNodeWithText("外观定制").assertIsDisplayed()
    }

    @Test fun quickUndoExpiresAfterFiveSecondsWithoutChangingTheChosenPreset() {
        start()
        compose.onNodeWithText("确认切换").performClick()
        compose.waitUntil(5_000) { model.settings.value.frontendMode == FrontendMode.LEGACY }
        compose.onNodeWithText("撤销").assertIsDisplayed()
        compose.waitUntil(8_000) { model.uiState.value.classicUndoDeadline == null }
        compose.onNodeWithText("已切换经典界面。返回新版：我的 → 设置 → 界面版本。").assertDoesNotExist()
        assertEquals(FrontendMode.LEGACY, model.settings.value.frontendMode)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val file = File(app.getExternalFilesDir(null), "mobile-followup-qa/$name.png")
        file.parentFile!!.mkdirs()
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
