package com.animeow.app.data.overlay

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.local.AnimeEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [30, 36], qualifiers = "w320dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProgressOverlayBallTest {
    @Test fun tapExpandsDragAndCancelDoNotRecordAndCollapseKeepsPosition() = runBlocking {
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildService(ProgressOverlayService::class.java).create()
        val service = controller.get()
        val configuration = android.content.res.Configuration(service.resources.configuration).apply { fontScale = 1.3f }
        @Suppress("DEPRECATION")
        service.resources.updateConfiguration(configuration, service.resources.displayMetrics)
        val app = android.app.Instrumentation.newApplication(AniMeowApplication::class.java, service.application) as AniMeowApplication
        org.robolectric.util.ReflectionHelpers.setField(service, "mApplication", app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val id = app.database.libraryDao().insertAnime(AnimeEntity(title = "约会大作战 第二季 · 悬浮记录", watchedEpisodes = 3, totalEpisodes = 12))
        try {
            service.onStartCommand(ProgressOverlayService.intent(service, id), 0, 1)
            val root = ProgressOverlayService::class.java.getDeclaredField("panel").apply { isAccessible = true }.get(service) as LinearLayout
            val ball = root.getChildAt(0)
            val controls = root.getChildAt(1)
            val title = ProgressOverlayService::class.java.getDeclaredField("title").apply { isAccessible = true }.get(service) as TextView
            eventually { title.text.startsWith("约会大作战") }
            assertEquals(View.VISIBLE, ball.visibility)
            assertEquals(View.GONE, controls.visibility)
            val params = root.layoutParams as WindowManager.LayoutParams
            assertEquals(112, params.width)
            draw(root, "ball")
            fun touch(action: Int, x: Float, y: Float) {
                MotionEvent.obtain(0, 20, action, x, y, 0).also { ball.dispatchTouchEvent(it); it.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN, 10f, 10f)
            touch(MotionEvent.ACTION_MOVE, 150f, 100f)
            touch(MotionEvent.ACTION_UP, 150f, 100f)
            assertEquals(View.GONE, controls.visibility)
            assertTrue(params.x > 16)
            touch(MotionEvent.ACTION_DOWN, 10f, 10f)
            touch(MotionEvent.ACTION_MOVE, -1000f, 10f)
            touch(MotionEvent.ACTION_UP, -1000f, 10f)
            assertEquals(-params.width / 2, params.x)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
            assertEquals("periodic bounds must retain half-hidden docking", -params.width / 2, params.x)
            touch(MotionEvent.ACTION_DOWN, 10f, 10f)
            touch(MotionEvent.ACTION_CANCEL, 10f, 10f)
            assertEquals(View.GONE, controls.visibility)
            ball.performClick()
            assertEquals(View.VISIBLE, controls.visibility)
            assertTrue(params.width > 112)
            assertTrue("expanded controls must stay entirely on screen", params.x >= 0)
            draw(root, "expanded")
            val collapse = root.findViewWithTag<View>("overlay.collapse")
            collapse.performClick()
            assertEquals(View.VISIBLE, ball.visibility)
            assertEquals(112, params.width)
            assertEquals(-params.width / 2, params.x)
            assertEquals(3, app.database.libraryDao().getAnime(id)!!.watchedEpisodes)
            ball.performClick()
            val plus = root.findViewWithTag<View>("overlay.plus")
            val undo = root.findViewWithTag<View>("overlay.undo")
            eventually { plus.isEnabled }
            plus.performClick()
            eventually { undo.isEnabled }
            assertEquals(4, app.database.libraryDao().getAnime(id)!!.watchedEpisodes)
            // Swapping appearance on a running overlay must retain work, docking and undo.
            val preferences = com.animeow.app.data.preferences.AppearancePreferences(service)
            for (style in com.animeow.app.ui.theme.OverlayStyle.entries) {
                for (theme in listOf(com.animeow.app.ui.theme.ThemeMode.LIGHT, com.animeow.app.ui.theme.ThemeMode.DARK)) {
                    preferences.setOverlayStyle(style)
                    preferences.setThemeMode(theme)
                    eventually { (ProgressOverlayService::class.java.getDeclaredField("ballArt").apply { isAccessible = true }
                        .get(service) as OverlayBallDrawable).style == style &&
                        ProgressOverlayService::class.java.getDeclaredField("themeMode").apply { isAccessible = true }.get(service) == theme }
                    assertTrue(undo.isEnabled)
                    eventually { plus.isEnabled }
                    val image = draw(root, "${style.storageKey}-${theme.storageKey}-expanded")
                    val palette = style.palette(theme == com.animeow.app.ui.theme.ThemeMode.DARK)
                    assertTrue("enabled +1 needs its contrasting primary surface", (0 until image.width).any { x ->
                        (0 until image.height).any { y -> image.getPixel(x, y) == palette.primary }
                    })
                    assertTrue(plus.height >= 96)
                    collapse.performClick()
                    assertEquals(-params.width / 2, params.x)
                    draw(root, "${style.storageKey}-${theme.storageKey}-ball")
                    ball.performClick()
                }
            }
            collapse.performClick(); ball.performClick()
            assertTrue(undo.isEnabled)
            undo.performClick()
            eventually { plus.isEnabled && !undo.isEnabled }
            assertEquals(3, app.database.libraryDao().getAnime(id)!!.watchedEpisodes)
            service.onStartCommand(ProgressOverlayService.intent(service, id), 0, 2)
            assertEquals(View.GONE, controls.visibility)
            ShadowSettings.setCanDrawOverlays(false)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
            ShadowSettings.setCanDrawOverlays(false)
        }
    }

    private fun eventually(condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("overlay state did not update", condition())
    }

    private fun draw(root: View, name: String): Bitmap {
        shadowOf(Looper.getMainLooper()).idle()
        val width = (root.layoutParams as WindowManager.LayoutParams).width
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, width, root.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../../build/feedback-next-2.2.1/overlay-api${android.os.Build.VERSION.SDK_INT}-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }
}
