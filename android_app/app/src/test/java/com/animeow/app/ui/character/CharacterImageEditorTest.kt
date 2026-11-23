package com.animeow.app.ui.character

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.animeow.app.data.local.CharacterEntity
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CharacterImageEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localImageCanBeRestoredAndSaveFailureKeepsTheDraft() {
        var saves = 0
        var restores = 0
        val original = CharacterEntity(id = 1, name = "原角色", imageUrl = "file:///private/local-character.png", rating = 9)
        compose.setContent { MaterialTheme { CharacterEditorDialog(original, false, {
            saves++
            assertEquals(9, it.rating)
            error("simulated database failure")
        }, {}, onRestoreSourceImage = {
            restores++
            if (restores == 1) error("source unavailable")
            "https://example.invalid/source.png"
        }) } }
        compose.onNodeWithText("使用本地图片").assertExists()
        compose.onNodeWithText("file:///private/local-character.png").assertDoesNotExist()
        compose.onNodeWithText("改用图片地址").performClick()
        compose.onNodeWithText("图片地址").performTextInput("https://example.invalid/new.png")
        compose.onNodeWithText("恢复编辑前图片").performScrollTo().performClick()
        compose.onNodeWithText("使用本地图片").assertExists()
        compose.onNodeWithText("恢复来源图片").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("来源图片暂时无法读取，当前图片已保留，可稍后重试").assertExists()
        compose.onNodeWithText("使用本地图片").assertExists()
        compose.onNodeWithText("恢复来源图片").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("图片地址").assertTextContains("https://example.invalid/source.png")
        compose.onNodeWithText("恢复编辑前图片").performScrollTo().performClick()
        compose.onNodeWithText("原名 *").performTextReplacement("保留的草稿")
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        assertEquals(1, saves)
        compose.onNodeWithText("保存未完成，请检查所选图片和角色是否仍可用后重试").assertExists()
        compose.onNodeWithText("原名 *").assertTextContains("保留的草稿")
        compose.onNodeWithText("保存").assertIsEnabled()
    }

}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CharacterImageRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun editorsDrawWithLargeChineseTextInBothThemes() {
        for (dark in listOf(false, true)) for (scene in listOf("character", "game")) Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val config = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.3f }
            @Suppress("DEPRECATION") activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        if (scene == "character") CharacterEditorDialog(CharacterEntity(id = 1, name = "用于检查长名字与大字体的角色示例", nameCn = "角色中文名"), false, {}, {})
                        else com.animeow.app.ui.games.GameDetailSheet(
                            com.animeow.app.data.games.GameEntity(title = "用于检查长名字与大字体的游戏示例", originalTitle = "Original name"),
                            false, {}, {}, {},
                        )
                    }
                }
            }
            controller.visible(); compose.waitForIdle()
            val activityRoot = activity.window.decorView
            activityRoot.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY))
            activityRoot.layout(0, 0, 720, 1520)
            compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            assertTrue("$scene dialog must be showing", dialog.isShowing)
            val root = requireNotNull(dialog.window).decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 720, 1520)
            compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            val bitmap = Bitmap.createBitmap(720, 1520, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val pixels = IntArray(720 * 1520)
            bitmap.getPixels(pixels, 0, 720, 0, 0, 720, 1520)
            val file = File("../../build/feedback-next-2.2.1/$scene-${if (dark) "dark" else "light"}.png")
            file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            assertTrue("$scene / dark=$dark must contain visible controls", pixels.toSet().size > 20)
            dialog.dismiss()
        }
    }
}
