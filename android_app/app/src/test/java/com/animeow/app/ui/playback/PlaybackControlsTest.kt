package com.animeow.app.ui.playback

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36], qualifiers = "w760dp-h360dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackControlsTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun fullscreenAndLockAreOperableInsideVideoAndAllControlsRemainWithinLandscapeViewport() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            var fullscreen by mutableStateOf(false)
            var locked by mutableStateOf(false)
            activity.setContent { MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    PlaybackOverlay("第 12 集 · 合成控件测试（不含视频解码）", 754000, 1440000, true, locked, fullscreen, null,
                        {}, {}, {}, { fullscreen = !fullscreen }, { locked = !locked }, { fullscreen = false }, {}, {}, {}, {}, true, {}, {}, {})
                }
            } }
            controller.visible()
            fun settle() {
                compose.waitForIdle()
                activity.window.decorView.apply {
                    measure(View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
                    layout(0, 0, 1520, 720)
                }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            settle()
            fun root() = requireNotNull(owner(activity.window.decorView)).unmergedRootSemanticsNode
            fun find(label: String) = nodes(root()).firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
            fun click(label: String) {
                val node = requireNotNull(find(label)) { "Missing video control: $label" }
                val action = generateSequence(node) { it.parent }.firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
                assertTrue(action()); settle()
            }
            fun awaitControl(label: String) {
                repeat(100) { settle(); if (find(label) != null) return; Thread.sleep(5) }
                fail("The recomposed control did not appear: $label")
            }
            click("全屏"); assertTrue(fullscreen); awaitControl("退出全屏")
            for (label in listOf("暂停", "播放进度", "下一集", "锁定触控", "播放设置", "切换画面比例", "退出全屏")) {
                val bounds = requireNotNull(find(label)) { "Missing $label; labels=${nodes(root()).mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription) }}" }.boundsInRoot
                assertTrue("$label must fit the video viewport: $bounds", bounds.left >= 0 && bounds.top >= 0 && bounds.right <= 1520 && bounds.bottom <= 720)
            }
            click("锁定触控"); assertTrue(locked); awaitControl("解锁触控"); assertNull(find("暂停")); assertNull(find("全屏"))
            assertNotNull(find("解锁触控"))
            click("解锁触控"); assertFalse(locked); assertTrue(fullscreen); awaitControl("暂停")
            val bitmap = Bitmap.createBitmap(1520, 720, Bitmap.Config.ARGB_8888)
            activity.window.decorView.draw(Canvas(bitmap))
            val path = File("../../build/playback-remediation-2.2.0/player-landscape-controls.png")
            path.parentFile?.mkdirs(); path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            click("退出全屏"); assertFalse(fullscreen)
        }
    }
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun owner(view: View): SemanticsOwner? {
        val method = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
        (method?.invoke(view) as? SemanticsOwner)?.let { return it }
        if (view is ViewGroup) for (i in 0 until view.childCount) owner(view.getChildAt(i))?.let { return it }
        return null
    }
}
