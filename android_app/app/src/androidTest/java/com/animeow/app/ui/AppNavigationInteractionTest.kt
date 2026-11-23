package com.animeow.app.ui

import android.content.Intent
import androidx.activity.BackEventCompat
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.AniMeowApplication
import com.animeow.app.MainActivity
import com.animeow.app.data.preferences.*
import com.animeow.app.ui.theme.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses the actual activity, navigation graph, toolbar and bottom bar. No production data is needed. */
class AppNavigationInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as AniMeowApplication
    private var activity: ActivityScenario<MainActivity>? = null
    private var original: ConfigurationBundle? = null
    private var originalManual: UserManualSettings? = null
    private var originalMaintenance: MaintenanceSettings? = null

    private fun start(predictive: Boolean = false, motion: MotionLevel = MotionLevel.FULL) {
        runBlocking {
            val profiles = ConfigurationProfileRepository(app)
            original = profiles.snapshotCurrent()
            originalManual = UserManualPreferences(app).snapshot()
            originalMaintenance = MaintenancePreferences(app).snapshot()
            UserManualPreferences(app).setDisplayMode(ManualDisplayMode.NEVER)
            MaintenancePreferences(app).save(MaintenanceSettings(autoCheckUpdates = false))
            profiles.applyConfiguration(original!!.copy(
                appearance = AppearanceSettings(useDynamicColor = false, frontendModeChoiceMade = true,
                    predictiveBackEnabled = predictive, motionLevel = motion, showStatistics = true, showCalendar = false),
                branding = original!!.branding?.copy(customSplashEnabled = false),
            ))
        }
        // A direct activity launch does not count as a launcher opening for promotional prompts.
        activity = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        awaitRoute("tracker")
    }

    @After fun restore() {
        compose.mainClock.autoAdvance = true
        activity?.close()
        runBlocking {
            original?.let { ConfigurationProfileRepository(app).applyConfiguration(it) }
            originalManual?.let { UserManualPreferences(app).setDisplayMode(it.displayMode) }
            originalMaintenance?.let { MaintenancePreferences(app).save(it) }
        }
    }

    @Test fun statisticsOpenedFromProfileOrToolsReturnsToItsActualCaller() {
        start()
        tab("我的")
        awaitRoute("profile")
        text("收录作品").performClick()
        awaitRoute("page/statistics")
        toolbarBack()
        awaitRoute("profile")
        text("全部工具").performScrollTo().performClick()
        awaitRoute("legacy/profile-tools")
        text("数据统计").performScrollTo().performClick()
        awaitRoute("page/statistics")
        systemBack()
        awaitRoute("legacy/profile-tools")
        toolbarBack()
        awaitRoute("profile")
    }

    @Test fun switchingBackToHomeAndPoppingToolsBothKeepTheirSlideAnimation() {
        start()
        tab("我的")
        awaitRoute("profile")
        val width = bounds("profile").width
        compose.mainClock.autoAdvance = false
        tab("追番")
        compose.mainClock.advanceTimeBy(96)
        assertMoving("tracker", width)
        finishMotion()
        awaitRoute("tracker")
        tab("我的")
        awaitRoute("profile")
        compose.mainClock.autoAdvance = false
        text("全部工具").performClick()
        compose.mainClock.advanceTimeBy(96)
        assertMoving("legacy/profile-tools", width)
        finishMotion()
        awaitRoute("legacy/profile-tools")
        compose.mainClock.autoAdvance = false
        toolbarBack()
        compose.mainClock.advanceTimeBy(96)
        assertMoving("legacy/profile-tools", width)
        route("profile").assertExists()
        finishMotion()
        awaitRoute("profile")
    }

    @Test fun everyTransitionStyleKeepsBothDirectionsWhenPredictivePreviewIsEnabled() {
        start(predictive = true)
        tab("我的")
        awaitRoute("profile")
        for (style in PageTransitionStyle.entries) {
            runBlocking {
                val preferences = AppearancePreferences(app)
                preferences.restore(preferences.snapshot().copy(pageTransitionStyle = style))
            }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            text("全部工具").performClick()
            compose.mainClock.advanceTimeBy(96)
            route("profile").assertExists()
            route("legacy/profile-tools").assertExists()
            finishMotion()
            awaitRoute("legacy/profile-tools")
            compose.mainClock.autoAdvance = false
            systemBack()
            compose.mainClock.advanceTimeBy(96)
            route("legacy/profile-tools").assertExists()
            route("profile").assertExists()
            finishMotion()
            awaitRoute("profile")
        }
    }

    @Test fun changingNextLaunchDestinationKeepsTheCurrentPageAndBackStack() {
        start(motion = MotionLevel.NONE)
        tab("我的")
        awaitRoute("profile")
        text("全部工具").performClick()
        awaitRoute("legacy/profile-tools")
        runBlocking {
            val preferences = AppearancePreferences(app)
            preferences.restore(preferences.snapshot().copy(startDestination = "statistics"))
        }
        compose.waitForIdle()
        route("legacy/profile-tools").assertIsDisplayed()
        systemBack()
        awaitRoute("profile")
    }

    @Test fun cancellingPredictiveMainBackKeepsTheCurrentTab() {
        start(predictive = true)
        tab("我的")
        awaitRoute("profile")
        activity!!.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 350f, 0f, BackEventCompat.EDGE_LEFT)) }
        compose.waitForIdle()
        activity!!.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(160f, 350f, 0.6f, BackEventCompat.EDGE_LEFT)) }
        compose.waitForIdle()
        activity!!.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        route("profile").assertIsDisplayed()
        systemBack()
        awaitRoute("tracker")
    }

    private fun route(value: String) = compose.onNodeWithTag("app-route:$value")
    private fun text(value: String) = compose.onNode(hasText(value) and hasClickAction())
    private fun tab(value: String) = compose.onNode(hasContentDescription(value) and hasClickAction()).performClick()
    private fun toolbarBack() = compose.onNode(hasContentDescription("返回") and hasClickAction()).performClick()
    private fun systemBack() { activity!!.onActivity { it.onBackPressedDispatcher.onBackPressed() } }
    private fun bounds(value: String): Rect = route(value).fetchSemanticsNode().boundsInRoot
    private fun assertMoving(value: String, fullWidth: Float) {
        val current = bounds(value)
        assertTrue("$value should be partway through a horizontal transition: $current",
            current.left > 1f || current.left < -1f || current.width < fullWidth - 1f)
    }
    private fun finishMotion() { compose.mainClock.advanceTimeBy(600); compose.mainClock.autoAdvance = true; compose.waitForIdle() }
    private fun awaitRoute(value: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("app-route:$value").fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
        route(value).assertIsDisplayed()
    }
}
