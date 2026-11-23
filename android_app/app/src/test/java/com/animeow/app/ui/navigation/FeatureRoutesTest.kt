package com.animeow.app.ui.navigation

import android.app.Application
import android.net.Uri
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.compose.ui.graphics.Color
import com.animeow.app.ui.theme.AppearanceSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Robolectric
import androidx.activity.ComponentActivity

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class FeatureRoutesTest {
    @Test fun extractedGraphsKeepExistingRoutesAndTypedArguments() {
        val controller = NavHostController(RuntimeEnvironment.getApplication())
        controller.navigatorProvider.addNavigator(ComposeNavigator())
        val graph = controller.createGraph(startDestination = LIBRARY_MANAGEMENT_ROUTE) {
            libraryRoutes(controller, AppearanceSettings(), Color.Transparent)
            communityRoutes(controller)
            preferenceRoutes(controller)
            searchRoutes(controller, AppearanceSettings())
            animeRoutes(controller, AppearanceSettings(), false, false, {}, {}, {}, {})
        }
        for (route in listOf(LIBRARY_MANAGEMENT_ROUTE, TRASH_ROUTE, DUPLICATES_ROUTE, SERIES_SHELF_ROUTE,
            SERIES_DETAIL_ROUTE, IMAGE_SEARCH_ROUTE, AppDestination.STATISTICS.route, AppDestination.STATISTICS.pageRoute,
            TAG_INDEX_ROUTE, TAG_COLLECTION_ROUTE, "tag-results?ids={ids}", STATUS_COLLECTION_ROUTE,
            CUSTOMIZATION_ROUTE_PATTERN, BANGUMI_IMPORT_ROUTE, SPREADSHEET_TRANSFER_ROUTE, BRANDING_ROUTE,
            COMMUNITY_MANAGEMENT_ROUTE, COMMUNITY_POST_DETAIL_ROUTE, COMMUNITY_USER_SPACE_ROUTE,
            COMMUNITY_FOLLOWS_ROUTE, COMMUNITY_BLOCKLIST_ROUTE, DETAIL_ROUTE, EPISODES_ROUTE_PATTERN,
            EDITOR_ROUTE_PATTERN, "$GLOBAL_SEARCH_ROUTE?scope={scope}", "$GAME_COLLECTION_ROUTE?id={id}",
            "$AUDIO_COLLECTION_ROUTE?id={id}", "$GAME_DISCOVERY_ROUTE?query={query}")) {
            assertNotNull("Missing route $route", graph.findNode(route))
        }
        assertEquals(NavType.LongType, graph.findNode(SERIES_DETAIL_ROUTE)!!.arguments.getValue("seriesId").type)
        assertEquals(NavType.LongType, graph.findNode(COMMUNITY_POST_DETAIL_ROUTE)!!.arguments.getValue("postId").type)
        assertEquals("", graph.findNode(CUSTOMIZATION_ROUTE_PATTERN)!!.arguments.getValue("query").defaultValue)
    }

    @Test fun extractedDestinationsKeepTheCallerAndEditorArguments() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { activity ->
            val controller = NavHostController(activity.get())
            controller.setLifecycleOwner(activity.get())
            controller.setViewModelStore(activity.get().viewModelStore)
            controller.navigatorProvider.addNavigator(ComposeNavigator())
            controller.graph = controller.createGraph(startDestination = LIBRARY_MANAGEMENT_ROUTE) {
                libraryRoutes(controller, AppearanceSettings(), Color.Transparent)
                preferenceRoutes(controller)
                searchRoutes(controller, AppearanceSettings())
                animeRoutes(controller, AppearanceSettings(), true, false, {}, {}, {}, {})
            }
            controller.navigate(TAG_INDEX_ROUTE)
            assertEquals(LIBRARY_MANAGEMENT_ROUTE, controller.previousBackStackEntry?.destination?.route)
            assertTrue(controller.popBackStack())
            assertEquals(LIBRARY_MANAGEMENT_ROUTE, controller.currentDestination?.route)
            controller.navigate("anime/edit?animeId=42&subjectType=book")
            assertEquals(42L, controller.currentBackStackEntry!!.arguments!!.getLong("animeId"))
            assertEquals("book", controller.currentBackStackEntry!!.arguments!!.getString("subjectType"))
            assertTrue(controller.popBackStack())
            controller.navigate(customizationRoute("文字大小", "size"))
            assertEquals("文字大小", controller.currentBackStackEntry!!.arguments!!.getString("query"))
            assertEquals("size", controller.currentBackStackEntry!!.arguments!!.getString("section"))
            assertTrue(controller.popBackStack())

            controller.navigate("$GLOBAL_SEARCH_ROUTE?scope=local")
            val search = controller.currentBackStackEntry!!
            search.savedStateHandle["query"] = "旅人"
            controller.navigate("anime/42/episodes")
            assertEquals(0, controller.currentBackStackEntry!!.arguments!!.getInt("episode"))
            assertTrue(controller.popBackStack())
            controller.navigate("anime/42/episodes?episode=3")
            assertEquals(42L, controller.currentBackStackEntry!!.arguments!!.getLong("animeId"))
            assertEquals(3, controller.currentBackStackEntry!!.arguments!!.getInt("episode"))
            assertTrue(controller.popBackStack())
            assertEquals("旅人", controller.currentBackStackEntry!!.savedStateHandle.get<String>("query"))

            for (route in listOf(GAME_COLLECTION_ROUTE, AUDIO_COLLECTION_ROUTE)) {
                controller.navigate("$route?id=42")
                assertEquals(42L, controller.currentBackStackEntry!!.arguments!!.getLong("id"))
                assertTrue(controller.popBackStack())
            }
            controller.navigate("$GAME_DISCOVERY_ROUTE?query=${Uri.encode("旅人 & 伙伴")}")
            assertEquals("旅人 & 伙伴", controller.currentBackStackEntry!!.arguments!!.getString("query"))
        }
    }
}
