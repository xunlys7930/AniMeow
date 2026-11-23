package com.animeow.app.ui.components

import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.remote.MediaCatalogService
import com.animeow.app.data.remote.PlaybackWork
import com.animeow.app.ui.playback.PlaybackScreen
import com.animeow.app.ui.playback.SourcePicker
import com.animeow.app.ui.playback.PlaybackViewModel
import com.animeow.app.ui.playback.PlaybackSettingsContent
import com.animeow.app.ui.playback.PlaybackOverlay
import com.animeow.app.data.playback.PlaybackSettings
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackFlowRenderTest {
    @Test fun playbackControlsDrawInBothThemesWithLargeFontsAndAccessibleUnlock() {
        for (dark in listOf(false, true)) for (locked in listOf(false, true)) render(null, dark, locked)
    }
    @Test fun pickerAndRealViewModelPreserveResumeAndDeduplicateAutomaticProgressAndUndo() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        // Attach the real app without invoking production onCreate/background workers.
        val app = Instrumentation.newApplication(AniMeowApplication::class.java, context) as AniMeowApplication
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val failResolve = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) runCatching { server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream().bufferedReader()
                val path = input.readLine().split(' ')[1]
                while (!input.readLine().isNullOrBlank()) { }
                if (path.contains("/resolve")) Thread.sleep(400)
                val body = when {
                    path.contains("/sources") -> """{"status":"success","data":[{"id":"giri","name":"giriGiriLove"},{"id":"seven","name":"7sefun"}]}"""
                    path.contains("/search") -> """{"status":"success","data":[{"id":"123","title":"搜索取得的动画示例","coverUrl":""}]}"""
                    path.contains("/episodes") -> """{"status":"success","data":{"lines":[{"id":"1","name":"线路 1","episodes":[{"id":"1-1","label":"第01集","number":1},{"id":"1-2","label":"SP01","number":null}]}]}}"""
                    else -> if (failResolve.get()) """{"status":"error","message":"synthetic resolver failure"}"""
                        else """{"status":"success","data":{"url":"https://cdn.example.test/sample.mp4","mimeType":"video/mp4","headers":{}}}"""
                }.toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
            } }
        }
        val store = ViewModelStore()
        try {
            val id = app.database.libraryDao().insertAnime(AnimeEntity(title = "检查长标题与大字体的动画示例", totalEpisodes = 12, watchedEpisodes = 0))
            val model = PlaybackViewModel(app, id, MediaCatalogService(baseUrl = "http://127.0.0.1:${server.localPort}"))
            store.put("playback", model)
            waitUntil { model.state.value.sources.size == 2 }
            model.search()
            waitUntil { model.state.value.searchGroups.size == 2 && model.state.value.searchGroups.none { it.loading } }
            model.choose(PlaybackWork("123", "经用户确认的来源作品", ""))
            waitUntil { model.state.value.lines.isNotEmpty() && !model.state.value.busy }
            render(model, false); render(model, true)
            render(model, false, sourceState = "ready"); render(model, true, sourceState = "ready")
            render(model, false, sourceState = "loading"); render(model, true, sourceState = "error")

            val episode = model.state.value.lines.first().episodes.first()
            model.play(episode)
            assertEquals(episode, model.state.value.openingTarget)
            assertTrue(model.state.value.busy)
            waitUntil { model.state.value.session != null && !model.state.value.busy }
            val session = requireNotNull(model.state.value.session)
            model.source("seven")
            assertEquals("Switching a candidate source must preserve actual playback", session, model.state.value.session)
            model.choose("giri", PlaybackWork("123", "经用户确认的来源作品", ""))
            waitUntil { model.state.value.source == "giri" && !model.state.value.busy }

            model.sample(session.id, 0, 100_000, true, 1f)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); model.discontinuity(session.id)
            model.sample(session.id, 99_000, 100_000, true, 1f)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, app.database.libraryDao().getAnime(id)?.watchedEpisodes)
            model.discontinuity(session.id)
            model.sample(session.id, 0, 100_000, true, 1f)
            for (second in 1..90) {
                ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                model.sample(session.id, second * 1000L, 100_000, true, 1f)
            }
            waitUntil { model.state.value.undo != null }
            val undoBeforeSwitch = model.state.value.undo
            model.source("seven")
            model.choose("giri", PlaybackWork("123", "经用户确认的来源作品", ""))
            waitUntil { model.state.value.source == "giri" && !model.state.value.busy }
            assertEquals("Choosing another candidate must not discard undo for the playing episode", undoBeforeSwitch, model.state.value.undo)

            assertEquals(1, app.database.libraryDao().getAnime(id)?.watchedEpisodes)
            assertEquals(1, app.database.libraryDao().getWatchRecords().size)
            model.undo()
            waitUntil { model.state.value.notice?.contains("已撤销") == true }
            assertEquals(0, app.database.libraryDao().getAnime(id)?.watchedEpisodes)
            for (second in 91..100) { ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); model.sample(session.id, second * 1000L, 100_000, true, 1f) }
            model.flush(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, app.database.libraryDao().getAnime(id)?.watchedEpisodes)
            assertTrue(app.database.libraryDao().getWatchRecords().isEmpty())
            model.play(episode)
            assertEquals(episode, model.state.value.openingTarget)
            assertEquals(session.id, model.state.value.session?.id)
            waitUntil { model.state.value.session?.id != session.id && !model.state.value.busy }
            val reopened = requireNotNull(model.state.value.session)
            assertEquals(100_000L, reopened.checkpoint.positionMs)
            assertTrue(reopened.checkpoint.handled)
            failResolve.set(true)
            val special = model.state.value.lines.first().episodes.last()
            model.play(special)
            assertEquals(special, model.state.value.openingTarget)
            waitUntil { !model.state.value.busy && model.state.value.error != null }
            assertEquals(special, model.state.value.openingTarget)
            assertEquals(reopened.id, model.state.value.session?.id)
            model.cancelOpening()
            assertNull(model.state.value.openingTarget)
            assertNull(model.state.value.error)
            assertEquals(reopened.id, model.state.value.session?.id)
        } finally { store.clear(); server.close(); worker.join(2000); app.database.close() }
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(500) { shadowOf(Looper.getMainLooper()).idle(); if (condition()) return; Thread.sleep(10) }
        fail("The real playback flow did not settle")
    }
    private fun render(model: PlaybackViewModel?, dark: Boolean, locked: Boolean = false, sourceState: String? = null) {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val config = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.3f }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            val sourceSnapshot = model?.state?.value
            activity.setContent { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                if (model != null && sourceState != null) Surface(Modifier.fillMaxSize()) {
                    SourcePicker(requireNotNull(sourceSnapshot).copy(sourcesLoading = sourceState == "loading",
                        sourcesError = if (sourceState == "error") "来源列表暂时无法获取，可重试；已缓存视频仍可离线观看" else null),
                        model, { _, _ -> }, Modifier.fillMaxSize())
                }
                else if (model != null) PlaybackScreen(model, {})
                else Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("合成播放控件预览（无媒体解码）")
                        Box(Modifier.fillMaxWidth().height(220.dp).background(Color.Black)) {
                            PlaybackOverlay("第 1 集", 754000, 1440000, true, locked, false, null,
                                {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, true, {}, {}, {})
                        }
                        PlaybackSettingsContent(PlaybackSettings(1.25f, 10, false), {})
                    }
                }
            } }
            controller.visible(); shadowOf(Looper.getMainLooper()).idle()
            val root = activity.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 720, 1520); shadowOf(Looper.getMainLooper()).idle()
            val bitmap = Bitmap.createBitmap(720, 1520, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val pixels = IntArray(720 * 1520); bitmap.getPixels(pixels, 0, 720, 0, 0, 720, 1520)
            assertTrue("The source/episode picker must draw real content", pixels.toSet().size > 20)
            val scene = if (sourceState != null) "source-$sourceState" else if (model != null) "player-picker" else if (locked) "player-locked" else "player-controls"
            val directory = if (sourceState != null) "media-source-audit-2026-10-23" else "playback-remediation-2.2.0"
            val output = File("../../build/$directory/$scene-${if (dark) "dark" else "light"}.png")
            requireNotNull(output.parentFile).mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
