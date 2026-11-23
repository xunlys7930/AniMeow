package com.animeow.app.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.animeow.app.data.remote.*
import com.animeow.app.ui.maintenance.SponsorThanksContent
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SponsorThanksUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val json = """{"status":"success","data":{"version":1,"items":[{"id":"00000000-0000-4000-8000-000000000001","displayName":"合成支持者","avatarUrl":"","supportMonth":"2026-10"}]}}"""

    @Test fun cacheSurvivesFailureAndExplicitEmptySuccessReplacesIt() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("sponsor_thanks_v1", 0).edit().clear().commit()
        val repository = SponsorThanksRepository(context) { json }
        assertNull(repository.cached())
        repository.refresh()
        val failing = SponsorThanksRepository(context) { error("synthetic outage") }
        assertEquals("合成支持者", failing.cached()!!.items.single().displayName)
        assertTrue(runCatching { failing.refresh() }.isFailure)
        assertEquals(1, failing.cached()!!.items.size)
        val empty = SponsorThanksRepository(context) { """{"status":"success","data":{"version":2,"items":[]}}""" }
        empty.refresh()
        assertTrue(empty.cached()!!.items.isEmpty())
    }

    @Test fun rejectsMalformedPayloadAndDropsUntrustedAvatar() {
        assertEquals("", SponsorThanksRepository.parse(json.replace("\"avatarUrl\":\"\"", "\"avatarUrl\":\"https://evil.example/avatar\"")).items.single().avatarUrl)
        for (bad in listOf("{}", json.replace("2026-10", "2026-13"), json.replace("合成支持者", ""), json.replace("\"success\"", "\"error\""))) {
            assertTrue(runCatching { SponsorThanksRepository.parse(bad) }.isFailure)
        }
    }

    @Test fun rendersLightDarkLargeTextAndCachedErrorWithRetry() {
        for (dark in listOf(false, true)) {
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
                var refreshed = 0
                controller.get().setContent {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        CompositionLocalProvider(LocalDensity provides Density(2f, 1.5f)) {
                            Surface(Modifier.fillMaxSize()) { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                                SponsorThanksContent(SponsorThanks(1, listOf(SponsorThank("one", "合成支持者 · 很长的中文昵称也应自然换行", "", "2026-10"), SponsorThank("two", "匿名支持者", "", ""))), false, "名单暂时加载失败，请检查网络后重试") { refreshed++ }
                            } }
                        }
                    }
                }
                controller.visible()
                compose.onNodeWithText("赞助鸣谢").assertIsDisplayed()
                compose.onNodeWithText("匿名支持者").assertExists()
                compose.onNodeWithText("以下为上次加载的名单", substring = true).assertExists()
                compose.onNodeWithText("刷新").performClick()
                assertEquals(1, refreshed)
                draw(controller.get(), if (dark) "thanks-dark-large" else "thanks-light-large")
            }
        }
    }

    @Test fun loadingAndEmptyAreDistinctAndPaginationDoesNotDuplicateRows() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val snapshot = SponsorThanks(1, (0..16).map { SponsorThank("$it", "合成支持者$it", "", "") })
            controller.get().setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { SponsorThanksContent(snapshot, false, null) {} } } }
            controller.visible()
            compose.onNodeWithText("合成支持者16").assertDoesNotExist()
            compose.onNodeWithText("下一页").performScrollTo().performClick()
            compose.onNodeWithText("合成支持者16").assertExists()
            compose.onNodeWithText("合成支持者0", substring = false).assertDoesNotExist()
        }
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent { MaterialTheme { SponsorThanksContent(null, true, null) {} } }
            controller.visible()
            compose.onNodeWithText("正在加载名单…").assertExists()
            compose.onNodeWithText("刷新").assertIsNotEnabled()
            compose.onNodeWithText("名单尚未公布，感谢每一份支持。").assertDoesNotExist()
        }
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent { MaterialTheme { SponsorThanksContent(SponsorThanks(0, emptyList()), false, null) {} } }
            controller.visible()
            compose.onNodeWithText("名单尚未公布，感谢每一份支持。").assertExists()
        }
    }

    private fun draw(activity: ComponentActivity, name: String) {
        compose.waitForIdle()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 720, 1520)
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val bitmap = Bitmap.createBitmap(720, 1520, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../../build/afdian-thanks/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
