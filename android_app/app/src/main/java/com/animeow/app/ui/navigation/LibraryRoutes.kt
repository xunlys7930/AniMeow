package com.animeow.app.ui.navigation

import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.animeow.app.ui.management.LibraryManagementScreen
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.trash.TrashScreen
import com.animeow.app.ui.duplicates.DuplicateCleanupScreen
import com.animeow.app.ui.series.SeriesDetailScreen
import com.animeow.app.ui.series.SeriesShelfScreen
import com.animeow.app.ui.discovery.ImageSearchScreen
import com.animeow.app.ui.statistics.StatisticsScreen
import com.animeow.app.ui.statistics.StatusAnimeScreen
import com.animeow.app.ui.statistics.UNCLASSIFIED_STATUS_ROUTE_TOKEN
import com.animeow.app.ui.tier.TierListScreen
import com.animeow.app.ui.analysis.AnimeAnalysisScreen
import com.animeow.app.ui.tags.TagCollectionScreen
import com.animeow.app.ui.tags.TagIndexScreen
import com.animeow.app.ui.reminder.ReminderManagementScreen
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import com.animeow.app.ui.navigation.appComposable as composable

internal fun NavGraphBuilder.libraryRoutes(
    navController: NavHostController,
    settings: AppearanceSettings,
    chromeContainerColor: Color,
) {
    composable(LIBRARY_MANAGEMENT_ROUTE) {
        LibraryManagementScreen(
            onBack = navController::navigateUp,
            onTagIndexRequested = { navController.navigate(TAG_INDEX_ROUTE) },
            onTagClick = { navController.navigate("tags/$it") },
        )
    }
    composable(REMINDER_MANAGEMENT_ROUTE) {
        ReminderManagementScreen(
            onBack = navController::navigateUp,
            onEditReminder = { navController.navigate("anime/edit?animeId=$it") },
        )
    }
    composable(TRASH_ROUTE) {
        TrashScreen(onBack = navController::navigateUp)
    }
    composable(DUPLICATES_ROUTE) {
        DuplicateCleanupScreen(onBack = navController::navigateUp)
    }
    composable(IMAGE_SEARCH_ROUTE) {
        ImageSearchScreen(
            onBack = navController::navigateUp,
            onAnimeImported = { animeId -> navController.navigate("anime/$animeId") },
        )
    }
    listOf(AppDestination.STATISTICS.route, AppDestination.STATISTICS.pageRoute).forEach { route ->
    composable(route) {
        StatisticsScreen(
            onBack = navController::navigateUp,
            showBack = route == AppDestination.STATISTICS.pageRoute,
            topBarContainerColor = chromeContainerColor,
            onSearchRequested = { navController.navigate(GLOBAL_SEARCH_ROUTE) },
            onAnalysisRequested = { navController.navigate(ANIME_ANALYSIS_ROUTE) },
            onTagIndexRequested = { navController.navigate(TAG_INDEX_ROUTE) },
            onStatusSelected = { status ->
                val routeValue = if (status.isBlank()) UNCLASSIFIED_STATUS_ROUTE_TOKEN else Uri.encode(status)
                navController.navigate("statistics/status/$routeValue")
            },
            onTagSelected = { tagId -> navController.navigate("tags/$tagId") },
        )
    }
    }
    composable(
        route = STATUS_COLLECTION_ROUTE,
        arguments = listOf(navArgument("statusName") { type = NavType.StringType }),
    ) {
        StatusAnimeScreen(
            settings = settings,
            onBack = navController::navigateUp,
            onAnimeClick = { navController.navigate("anime/$it") },
        )
    }
    composable(TAG_INDEX_ROUTE) {
        TagIndexScreen(
            onBack = navController::navigateUp,
            onTagClick = { navController.navigate("tags/$it") },
            onViewSelected = { ids -> navController.navigate("tag-results?ids=${ids.sorted().joinToString(",")}") },
        )
    }
    composable(
        route = TAG_COLLECTION_ROUTE,
        arguments = listOf(navArgument("tagId") { type = NavType.LongType }),
    ) {
        TagCollectionScreen(
            settings = settings,
            onBack = navController::navigateUp,
            onAnimeClick = { navController.navigate("anime/$it") },
        )
    }
    composable("tag-results?ids={ids}", arguments = listOf(navArgument("ids") { type = NavType.StringType; defaultValue = "" })) {
        TagCollectionScreen(settings = settings, onBack = navController::navigateUp,
            onAnimeClick = { navController.navigate("anime/$it") })
    }
    composable(ANIME_ANALYSIS_ROUTE) {
        AnimeAnalysisScreen(onBack = navController::navigateUp)
    }
    composable(TIER_LIST_ROUTE) {
        TierListScreen(onBack = navController::navigateUp)
    }
    composable(SERIES_SHELF_ROUTE) {
        SeriesShelfScreen(
            onBack = navController::navigateUp,
            onSeriesClick = { navController.navigate("series/$it") },
            onAnimeClick = { navController.navigate("anime/$it") },
        )
    }
    composable(
        route = SERIES_DETAIL_ROUTE,
        arguments = listOf(navArgument("seriesId") { type = NavType.LongType }),
    ) {
        SeriesDetailScreen(
            onBack = navController::navigateUp,
            onAnimeClick = { navController.navigate("anime/$it") },
        )
    }
}
