package com.animeow.app.ui.preferences

import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inspector.WindowInspector
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.preferences.*
import com.animeow.app.ui.theme.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomizationWorkflowUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun changedFontRequiresReviewAndApplyReturnsToTheSource() = scene("size", "文字大小") { activity, model, returns ->
        compose.onNodeWithContentDescription("文字大小").performSemanticsAction(SemanticsActions.SetProgress) { it(1.2f) }
        await { model.uiState.value.hasChanges }
        compose.onNodeWithText("查看变化").performClick()
        compose.onNodeWithText("确认本次调整").assertIsDisplayed()
        compose.onNodeWithText("原来：100%\n现在：120%").assertExists()
        assertEquals(0, returns())
        draw(activity, "font-review-light-large")
        compose.onNodeWithText("应用", substring = false).performClick()
        await { returns() == 1 }
        assertFalse(model.uiState.value.hasChanges)
        assertEquals(1.2f, runBlocking { AppearancePreferences(activity).snapshot() }.fontScale, .01f)
    }

    @Test fun undoAndDiscardKeepTheOriginalSettingsAndNestedReturnStack() = scene(null, "", dark = true) { activity, model, returns ->
        compose.onNodeWithContentDescription("文字大小").performSemanticsAction(SemanticsActions.SetProgress) { it(1.2f) }
        await { model.uiState.value.hasChanges }
        compose.onNodeWithText("撤销上一步").performClick()
        await { !model.uiState.value.hasChanges }
        compose.runOnIdle { model.setFontScale(1.3f) }
        await { model.uiState.value.hasChanges }
        compose.onNodeWithTag("customization-list").performScrollToNode(hasText("页面与卡片"))
        compose.onNodeWithText("页面与卡片").performClick()
        compose.onNodeWithText("底栏外形").performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回上一级设置").performClick()
        compose.onNodeWithText("首页与详情").assertExists()
        compose.onNodeWithContentDescription("返回上一级设置").performClick()
        assertEquals(0, returns())
        compose.onNodeWithContentDescription("取消并返回").performClick()
        compose.onNodeWithText("放弃未应用的调整？").assertIsDisplayed()
        draw(activity, "discard-dark-large")
        compose.onNodeWithText("放弃调整").performClick()
        await { returns() == 1 }
        assertEquals(1f, runBlocking { AppearancePreferences(activity).snapshot() }.fontScale, .01f)
    }

    @Test fun exitSearchScrollsToItsControlAndAdvancedGroupsStayCollapsed() = scene("behavior", "返回退出方式", dark = true) { activity, _, _ ->
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("setting-control-exit-behavior").assertIsDisplayed()
        compose.onNodeWithText("右滑动作").assertDoesNotExist()
        draw(activity, "exit-search-dark-large")
        compose.onNodeWithText("手势与触感").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("右滑动作").assertExists()
    }

    @Test fun savedConfigurationIsConfirmedBeforePreviewAndDoesNotCreateADuplicate() = scene("profile", "") { activity, model, _ ->
        compose.onNodeWithText("配置名称").performTextInput("合成完整配置")
        compose.onNodeWithText("保存", substring = false).performScrollTo().performClick()
        await { model.uiState.value.profiles.any { it.name == "合成完整配置" } }
        compose.runOnIdle { model.setExitBehavior(ExitBehavior.DIRECT) }
        await { model.uiState.value.hasChanges }
        val count = model.uiState.value.profiles.size
        val profile = model.uiState.value.profiles.first { it.name == "合成完整配置" }
        await { !model.uiState.value.busy }
        compose.mainClock.advanceTimeBy(6000)
        await { model.uiState.value.message == null }
        compose.onNodeWithText("预览", substring = false).performScrollTo().assertIsEnabled()
        draw(activity, "configuration-before-preview")
        compose.onNodeWithText("预览", substring = false).performClick()
        await { model.uiState.value.pendingProfile != null }
        assertTrue(model.uiState.value.pendingProfileIsSaved)
        compose.onNodeWithText("应用个性化配置").assertIsDisplayed()
        compose.onNodeWithText("外观、操作、导航与页面偏好按实际差异列出；未包含的配置保留本机设置。").assertExists()
        compose.onNodeWithText("预览此配置").performClick()
        await { model.uiState.value.pendingProfile == null && !model.uiState.value.busy }
        assertEquals(count, model.uiState.value.profiles.size)
        assertEquals(profile.settings.exitBehavior, runBlocking { AppearancePreferences(activity).snapshot() }.exitBehavior)
    }

    @Test
    @Config(qualifiers = "w760dp-h360dp-xhdpi")
    fun opacitySearchIsVisibleInLandscapeWithLargeText() = scene("nav", "底栏透明度", dark = true, landscape = true) { activity, _, _ ->
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("setting-control-nav-opacity").assertIsDisplayed()
        draw(activity, "opacity-search-landscape-dark-large", 1520, 720)
    }

    @Test fun brandingSavedOutsideTheDraftSurvivesUndoAndDiscard() = scene("size", "文字大小") { activity, model, _ ->
        val branding = BrandingPreferences(activity)
        val original = runBlocking { branding.snapshot() }
        try {
            compose.runOnIdle { model.setFontScale(1.2f) }
            await { model.uiState.value.hasChanges }
            var opened = false
            compose.runOnIdle { model.openBranding { opened = true } }
            await { opened }
            runBlocking { branding.setTapToSkip(!original.tapToSkip) }
            var resumed = false
            compose.runOnIdle { model.beginDraft().invokeOnCompletion { resumed = true } }
            await { resumed }
            compose.onNodeWithText("撤销上一步").performClick()
            await { !model.uiState.value.hasChanges }
            assertEquals(1f, runBlocking { AppearancePreferences(activity).snapshot() }.fontScale, .001f)
            assertEquals(!original.tapToSkip, runBlocking { branding.snapshot() }.tapToSkip)
            var discarded = false
            compose.runOnIdle { model.discard { discarded = true } }
            await { discarded }
            assertEquals(!original.tapToSkip, runBlocking { branding.snapshot() }.tapToSkip)
        } finally { runBlocking { branding.save(original) } }
    }

    @Test fun profileComparisonNamesPageChangesAndRedactsPrivateSourceSettings() = scene("profile", "", dark = true) { activity, model, _ ->
        val profiles = ConfigurationProfileRepository(activity)
        val saved = runBlocking {
            val current = profiles.snapshotCurrent()
            profiles.save("合成配置对比", current.copy(
                appearance = current.appearance.copy(statisticsLayoutStyle = StatisticsLayoutStyle.DASHBOARD),
                tracker = current.tracker!!.copy(coverProgressAlpha = .82f),
                discovery = current.discovery!!.copy(aniListProxyUrl = "https://private.test/secret"),
            ))
        }
        try {
            compose.runOnIdle { model.applyProfile(saved) }
            await { model.uiState.value.pendingProfile != null }
            compose.onNodeWithText("应用个性化配置").assertIsDisplayed()
            compose.onNodeWithText("统计布局：经典卡片 → 档案仪表盘").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("封面进度不透明度：55% → 82%").performScrollTo().assertIsDisplayed()
            assertFalse(model.uiState.value.pendingChanges.any { it.after.contains("private.test") || it.after.contains("secret") })
            draw(activity, "am01-profile-comparison-dark-large")
            compose.onNodeWithText("取消", substring = false).performClick()
            await { model.uiState.value.pendingProfile == null }
        } finally { runBlocking { profiles.delete(saved.id) } }
    }

    @Test fun deviceChoiceMarkerDoesNotCreateAUserDraftOrUndoEntry() = scene("profile", "") { activity, model, _ ->
        val profiles = ConfigurationProfileRepository(activity)
        val saved = runBlocking {
            val current = profiles.snapshotCurrent()
            profiles.save("只有设备标记", current.copy(appearance = current.appearance.copy(frontendModeChoiceMade = true)))
        }
        try {
            compose.runOnIdle { model.applyProfile(saved) }
            await { model.uiState.value.pendingProfile != null }
            compose.onNodeWithText("预览此配置").performClick()
            await { model.uiState.value.pendingProfile == null }
            assertFalse(model.uiState.value.hasChanges)
            assertFalse(model.uiState.value.canUndo)
            assertTrue(runBlocking { AppearancePreferences(activity).snapshot() }.frontendModeChoiceMade)
        } finally { runBlocking { profiles.delete(saved.id) } }
    }

    private fun scene(section: String?, query: String, dark: Boolean = false, landscape: Boolean = false, run: (ComponentActivity, CustomizationViewModel, () -> Int) -> Unit) {
        val app = Instrumentation.newApplication(AniMeowApplication::class.java, RuntimeEnvironment.getApplication()) as AniMeowApplication
        val preferences = AppearancePreferences(app)
        val original = runBlocking { preferences.snapshot() }
        runBlocking { preferences.restore(AppearanceSettings(motionLevel = MotionLevel.NONE, navigationBarStyle = NavigationBarStyle.FLOATING)) }
        val store = ViewModelStore()
        val model = CustomizationViewModel(app, "").also { store.put("customization", it) }
        var returned = 0
        try {
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                activity.setContent { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    CompositionLocalProvider(LocalDensity provides Density(2f, 1.4f)) {
                        CustomizationScreen(onBack = { returned++ }, onBrandingRequested = {}, initialSection = section, initialQuery = query, viewModel = model)
                    }
                } }
                controller.visible()
                draw(activity, "initial-${section ?: "overview"}-${if (dark) "dark" else "light"}", if (landscape) 1520 else 720, if (landscape) 720 else 1520)
                var ready = false
                compose.runOnIdle { model.beginDraft().invokeOnCompletion { ready = true } }
                await { ready }
                run(activity, model) { returned }
            }
        } finally {
            store.clear()
            runBlocking { preferences.restore(original) }
        }
    }

    private fun await(condition: () -> Boolean) {
        compose.waitUntil(15000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
    }

    private fun draw(activity: ComponentActivity, name: String, width: Int = 720, height: Int = 1520) {
        compose.waitForIdle()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val dialogRoot = WindowInspector.getGlobalWindowViews().lastOrNull { it !== root && it.isShown }
        val capture = if (dialogRoot != null && dialogRoot.width > 0 && dialogRoot.height > 0) {
            bitmap.recycle()
            Bitmap.createBitmap(dialogRoot.width, dialogRoot.height, Bitmap.Config.ARGB_8888).also { dialogRoot.draw(Canvas(it)) }
        } else bitmap.also { root.draw(Canvas(it)) }
        File("../../build/settings-polish-20261029/$name.png").apply { parentFile!!.mkdirs(); outputStream().use { capture.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        capture.recycle()
    }
}
