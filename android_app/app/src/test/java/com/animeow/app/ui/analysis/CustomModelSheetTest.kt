package com.animeow.app.ui.analysis

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import com.animeow.app.data.analysis.AnimeAnalysisSettings
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomModelSheetTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun providerPresetFetchSearchConnectionAndSavedKeyFlow() {
        var saved: AnimeAnalysisSettings? = null
        var savedKey = ""
        var remember = false
        var forgotten = ""
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent {
                MaterialTheme {
                    CustomModelSheet(AnimeAnalysisSettings(), false,
                        onSave = { settings, key, persist -> saved = settings; savedKey = key; remember = persist },
                        onRun = { _, _, _ -> }, onReset = {}, onDismiss = {},
                        onLoadKey = { if (it.contains("siliconflow")) "synthetic-key" else "" },
                        onForgetKey = { forgotten = it },
                        onFetchModels = { endpoint, key ->
                            assertEquals("https://api.siliconflow.cn/v1", endpoint)
                            assertEquals("synthetic-key", key)
                            listOf("test-alpha", "test-beta")
                        }, onTestConnection = { _, _ -> 42 })
                }
            }
            compose.onNodeWithText("模型厂商 · DeepSeek ▾").performClick()
            compose.onNodeWithText("硅基流动", substring = false).performClick()
            compose.onNodeWithText("https://api.siliconflow.cn/v1").assertIsDisplayed()
            compose.onNode(isToggleable()).assertIsOn()
            draw("provider-light")
            compose.onNodeWithText("获取模型").performScrollTo().performClick()
            compose.onNodeWithText("搜索模型").performTextInput("beta")
            compose.onNodeWithText("test-beta").performClick()
            compose.onNodeWithText("测试连接").performScrollTo().performClick()
            compose.onNodeWithText("连接成功 · 42 ms").performScrollTo().assertIsDisplayed()
            draw("connection-light")
            compose.onNodeWithText("保存配置").performScrollTo().performClick()
            assertEquals("test-beta", saved?.model)
            assertEquals("synthetic-key", savedKey)
            assertTrue(remember)
            compose.onNode(isToggleable()).performScrollTo().performClick()
            assertEquals("https://api.siliconflow.cn/v1", forgotten)
            compose.onNodeWithText("保存配置").performScrollTo().performClick()
            assertFalse(remember)
        }
    }

    @Test fun connectionErrorAndLargeTextDarkLayout() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    CompositionLocalProvider(LocalDensity provides Density(2f, 1.4f)) {
                        CustomModelSheet(AnimeAnalysisSettings(), false,
                            onSave = { _, _, _ -> }, onRun = { _, _, _ -> }, onReset = {}, onDismiss = {},
                            onLoadKey = { "" }, onForgetKey = {}, onFetchModels = { _, _ -> emptyList() },
                            onTestConnection = { _, _ -> error("密钥无效，请检查 API Key") })
                    }
                }
            }
            draw("provider-dark-large")
            compose.onNodeWithText("测试连接").performScrollTo().performClick()
            compose.onNodeWithText("密钥无效，请检查 API Key").performScrollTo().assertIsDisplayed()
            draw("connection-dark-large-error")
        }
    }

    private fun draw(name: String) {
        compose.waitForIdle()
        val view = WindowInspector.getGlobalWindowViews().last { it.isShown && it.width > 0 }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("../../build/custom-model-20261114/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
