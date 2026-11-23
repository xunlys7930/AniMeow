package com.animeow.app.ui.icons

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.ui.preferences.IconCustomizationControls
import com.animeow.app.ui.theme.AniMeowTheme
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IconCustomizationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectASuiteThenKeepAnIndependentIconWhileSwitchingSuites() {
        var settings by mutableStateOf(IconSettings())
        compose.setContent {
            AniMeowTheme(AppearanceSettings(icons = settings)) {
                Surface {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        IconCustomizationControls(settings,
                            onStyle = { settings = settings.copy(style = it) },
                            onVariant = { settings = settings.copy(variant = it) },
                            onAnimated = { settings = settings.copy(animated = it) },
                            onOverride = { id, choice -> settings = settings.withOverride(id, choice) },
                            onResetOverrides = { settings = settings.copy(overrides = emptyMap()) })
                    }
                }
            }
        }
        compose.onNodeWithText("柔和圆角").performScrollTo().performClick()
        compose.onNodeWithContentDescription("整套变体 B").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(IconStyle.SOFT, settings.style); assertEquals(IconVariant.B, settings.variant) }
        compose.onNodeWithText("整套风格").performScrollTo()
        saveScreenshot("icon-styles.png")
        compose.onNodeWithText("逐个定制图标").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("搜索")
        compose.onNode(hasText("搜索") and hasText("跟随整套")).performClick()
        compose.onNodeWithContentDescription("搜索，双色层次，变体 B").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(IconOverride(IconStyle.DUO, IconVariant.B), settings.overrides[AppIconId.SEARCH]) }
        saveScreenshot("icon-individual.png")
        compose.onNodeWithText("完成选择").performScrollTo().performClick()
        compose.onNodeWithText("完成", substring = false).performClick()
        compose.onNodeWithText("清晰线条").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(IconStyle.LINE, settings.style)
            assertEquals(ResolvedIcon(IconStyle.DUO, IconVariant.B), settings.resolve(AppIconId.SEARCH))
        }
        compose.onNodeWithText("让所有图标重新跟随整套").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(emptyMap<AppIconId, IconOverride>(), settings.overrides) }
    }

    @Test
    fun individualChoicesRemainUsableWithLargeTextInDarkMode() {
        var settings by mutableStateOf(IconSettings(style = IconStyle.DUO, animated = false))
        compose.setContent {
            AniMeowTheme(AppearanceSettings(icons = settings, themeMode = ThemeMode.DARK, fontScale = 1.4f)) {
                Surface {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        IconCustomizationControls(settings,
                            onStyle = { settings = settings.copy(style = it) },
                            onVariant = { settings = settings.copy(variant = it) },
                            onAnimated = { settings = settings.copy(animated = it) },
                            onOverride = { id, choice -> settings = settings.withOverride(id, choice) },
                            onResetOverrides = { settings = settings.copy(overrides = emptyMap()) })
                    }
                }
            }
        }
        compose.onNodeWithText("逐个定制图标").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("提醒")
        compose.onNode(hasText("提醒与通知") and hasText("跟随整套")).performClick()
        compose.onNodeWithContentDescription("提醒与通知，柔和圆角，变体 B").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(IconOverride(IconStyle.SOFT, IconVariant.B), settings.overrides[AppIconId.NOTIFICATION])
        }
        saveScreenshot("icon-large-text-dark.png")
        compose.onNodeWithText("完全跟随整套").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(null, settings.overrides[AppIconId.NOTIFICATION]) }
    }

    private fun saveScreenshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.getExternalFilesDir(null), "icon-qa/" + name)
        file.parentFile?.mkdirs()
        compose.waitForIdle()
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
