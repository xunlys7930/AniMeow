package com.animeow.app.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.animeow.app.data.audio.AudioWork
import com.animeow.app.ui.audio.AudioWorkEditor
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import androidx.compose.ui.semantics.SemanticsActions

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioEditorRenderTest {
    @get:Rule val compose = createComposeRule()
    @Test fun originalTitleCanBeEditedWithoutReplacingTheDisplayNameOrListeningRecords() {
        var saved: AudioWork? = null
        val work = AudioWork(title = "自定义译名", originalTitle = "原站名称", listenedSeconds = 90, rating = 8)
        compose.setContent { MaterialTheme { AudioWorkEditor(work, false, {}, { saved = it }, {}) } }
        compose.onNodeWithText("作品原名").performScrollTo().performTextReplacement("新的原站名称")
        compose.onNodeWithText("保存音声记录").performScrollTo().performClick()
        assertEquals(work.copy(originalTitle = "新的原站名称"), saved)
    }
    @Test fun blankEditorCanOpenLookupAndValidInputEnablesReading() {
            val work = AudioWork()
            compose.setContent { MaterialTheme { AudioWorkEditor(work, false, {}, {}, {}) } }
            compose.waitForIdle()
            compose.onNodeWithText("作品名称").performTextInput("未保存的音声草稿")
            compose.onNodeWithText("从 DLsite 补全资料").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("读取资料").assertIsNotEnabled()
            compose.onNodeWithText("编号、链接或关键词").performTextInput("01570739")
            compose.onNodeWithText("读取资料").assertIsEnabled()
            compose.onNodeWithText("编号、链接或关键词").performTextReplacement("小木曽雪菜")
            compose.onNodeWithText("搜索候选").assertIsEnabled()
            compose.onNodeWithText("取消").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("作品名称").assertTextContains("未保存的音声草稿")
    }
    @Test fun lightEditorWithLargeTextDrawsARealForm() = render(false)
    @Test fun darkEditorWithLargeTextDrawsARealForm() = render(true)

    private fun render(dark: Boolean) {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val configuration = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.3f }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
            var composed = false
            activity.setContent {
                composed = true
                CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        AudioWorkEditor(AudioWork(title = "用于检查长标题和大字体的音声示例", voiceActors = "示例声优", circle = "示例社团"),
                            false, {}, {}, {})
                    }
                }
            }
            controller.visible()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            val root = requireNotNull(dialog.window).decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 720, 1520)
            shadowOf(Looper.getMainLooper()).idle()
            val bitmap = Bitmap.createBitmap(720, 1520, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            assertTrue(composed)
            val pixels = IntArray(720 * 1520)
            bitmap.getPixels(pixels, 0, 720, 0, 0, 720, 1520)
            assertTrue("The actual dialog must draw content, not a blank activity", pixels.toSet().size > 20)
            val output = File("../../build/media-2.2.0/audio-editor-${if (dark) "dark" else "light"}.png")
            requireNotNull(output.parentFile).mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            dialog.dismiss()
            bitmap.recycle()
        }
    }
}
