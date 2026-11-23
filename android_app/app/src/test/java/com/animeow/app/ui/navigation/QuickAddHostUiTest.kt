package com.animeow.app.ui.navigation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.animeow.app.ui.tracker.QuickAddHost
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w375dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickAddHostUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun quickAddRestoresOnItsOwnEntryAndDoesNotRepeatAfterSelectingAnEditor() {
        lateinit var nav: NavHostController
        val navigations = mutableListOf<String>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                nav = rememberNavController()
                NavHost(nav, startDestination = AppDestination.TRACKER.route) {
                    composable(AppDestination.TRACKER.route) { entry ->
                        val requests = rememberPageActions(entry)
                        Button(onClick = { requests.request(PageAction.QUICK_ADD) }) { Text("添加入口") }
                        QuickAddHost(requests) { navigations += it; nav.navigate("synthetic-editor") }
                    }
                    composable("synthetic-editor") { Text("合成编辑器") }
                    composable("synthetic-detail") { Text("合成详情") }
                }
            }
        }
        compose.onNodeWithText("添加入口").performClick()
        compose.onNodeWithText("快速添加").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("快速添加").assertIsDisplayed()
        compose.runOnIdle { nav.navigate("synthetic-detail") }
        compose.onNodeWithText("合成详情").assertIsDisplayed()
        compose.onNodeWithText("快速添加").assertDoesNotExist()
        compose.runOnIdle { assertTrue(nav.popBackStack()) }
        compose.onNodeWithText("快速添加").assertIsDisplayed()
        compose.onNodeWithText("创建书籍").performScrollTo().performClick()
        compose.onNodeWithText("合成编辑器").assertIsDisplayed()
        assertEquals(listOf(BOOK_EDITOR_ROUTE), navigations)
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("添加入口").assertIsDisplayed()
        compose.onNodeWithText("快速添加").assertDoesNotExist()
    }

    @Test fun aPausedTrackerWaitsBeforeShowingTheSheetAndDismissalStaysDismissed() {
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        val requests = PageActionRequests(AppDestination.TRACKER, SavedStateHandle())
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            Text("收藏页面")
            QuickAddHost(requests) { }
        } } }
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED; requests.request(PageAction.QUICK_ADD) }
        compose.onNodeWithText("快速添加").assertDoesNotExist()
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("快速添加").assertIsDisplayed()
        compose.runOnIdle { requests.consume(PageAction.QUICK_ADD) }
        compose.onNodeWithText("快速添加").assertDoesNotExist()
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED; owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("快速添加").assertDoesNotExist()
    }

    @Test fun theLastRouteCanBeReachedWithLargeText() = largeTextScene(false)

    @Test @Config(qualifiers = "w800dp-h375dp-land-xhdpi")
    fun theLastRouteCanBeReachedInDarkLandscapeWithLargeText() = largeTextScene(true)

    private fun largeTextScene(dark: Boolean) {
        val requests = PageActionRequests(AppDestination.TRACKER, SavedStateHandle())
        var route: String? = null
        compose.setContent { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1.4f)) {
                Column { Text("收藏页面"); QuickAddHost(requests) { route = it } }
            }
        } }
        compose.runOnIdle { requests.request(PageAction.QUICK_ADD) }
        compose.onNodeWithText("快速添加").assertIsDisplayed()
        draw(if (dark) "quick-add-dark-landscape-large-top" else "quick-add-light-large-top")
        compose.onNodeWithText("导入 Excel / CSV").performScrollTo().assertIsDisplayed()
        draw(if (dark) "quick-add-dark-landscape-large-bottom" else "quick-add-light-large-bottom")
        compose.onNodeWithText("导入 Excel / CSV").performClick()
        assertEquals(SPREADSHEET_TRANSFER_ROUTE, route)
        assertFalse(requests.isPending(PageAction.QUICK_ADD))
    }

    private fun draw(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val root = WindowInspector.getGlobalWindowViews().last { it.width > 0 && it.height > 0 }
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File("../../build/am03-followup-20261106/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
