package com.animeow.app.ui.navigation

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageActionRequestsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun navigateFirstPinsTheComposerToItsEntryAndWaitsForLoginAcrossRestoration() {
        var loggedIn by mutableStateOf(false); var openings = 0
        lateinit var nav: NavHostController
        lateinit var requests: PageActionRequests
        run {
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                MaterialTheme {
                    nav = rememberNavController()
                    NavHost(nav, startDestination = AppDestination.PROFILE.route) {
                        composable(AppDestination.PROFILE.route) {
                            Button(onClick = {
                                nav.navigate(AppDestination.COMMUNITY.pageRoute)
                                nav.requestPageAction(PageAction.COMPOSE_POST)
                            }) { Text("发帖入口") }
                        }
                        composable(AppDestination.COMMUNITY.pageRoute) { entry ->
                            requests = rememberPageActions(entry)
                            var showing by rememberSaveable { mutableStateOf(false) }
                            HandlePageAction(requests, PageAction.COMPOSE_POST, loggedIn) { openings++; showing = true }
                            Column { Text("社区页面"); if (showing) Text("发帖表单已打开") }
                        }
                    }
                }
            }
            compose.onNodeWithText("发帖入口").performClick(); compose.waitForIdle()
            compose.onNodeWithText("社区页面").assertIsDisplayed()
            assertEquals(0, openings); assertTrue(requests.isPending(PageAction.COMPOSE_POST))
            restoration.emulateSavedInstanceStateRestore(); compose.waitForIdle()
            assertTrue(requests.isPending(PageAction.COMPOSE_POST))
            compose.runOnIdle { loggedIn = true }
            compose.onNodeWithText("发帖表单已打开").assertIsDisplayed()
            assertEquals(1, openings); assertNull(requests.pending.value)
            restoration.emulateSavedInstanceStateRestore(); compose.waitForIdle()
            compose.onNodeWithText("发帖表单已打开").assertIsDisplayed(); assertEquals(1, openings)
            compose.runOnIdle { assertTrue(nav.popBackStack()) }
            compose.onNodeWithText("发帖入口").assertIsDisplayed()
        }
    }

    @Test fun pausedPagesKeepThePendingActionUntilTheyAreResumed() {
        var openings = 0
        val requests = PageActionRequests(AppDestination.TRACKER, SavedStateHandle())
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            var visible by rememberSaveable { mutableStateOf(false) }
            HandlePageAction(requests, PageAction.SEARCH_GAME) { openings++; visible = true }
            Text(if (visible) "游戏搜索已打开" else "等待操作")
        } } }
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED; requests.request(PageAction.SEARCH_GAME) }
        compose.waitForIdle(); assertEquals(0, openings); assertTrue(requests.isPending(PageAction.SEARCH_GAME))
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("游戏搜索已打开").assertIsDisplayed()
        assertEquals(1, openings); assertNull(requests.pending.value)
    }

    @Test fun onePendingActionIsConsumedOnlyOnceEvenIfTwoObserversAreMounted() {
        var calls = 0
        val requests = PageActionRequests(AppDestination.CALENDAR, SavedStateHandle())
        requests.request(PageAction.CUSTOMIZE_CALENDAR)
        run {
            compose.setContent { MaterialTheme {
                HandlePageAction(requests, PageAction.CUSTOMIZE_CALENDAR) { calls++ }
                HandlePageAction(requests, PageAction.CUSTOMIZE_CALENDAR) { calls++ }
                Text("日历请求测试")
            } }
            compose.waitForIdle(); assertEquals(1, calls); assertNull(requests.pending.value)
        }
    }

}
