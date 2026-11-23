package com.animeow.app.ui.navigation

import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.animeow.app.ui.anime.AnimeDetailScreen
import com.animeow.app.ui.anime.AnimeEditorScreen
import com.animeow.app.ui.legacy.LegacyAnimeDetailScreen
import com.animeow.app.ui.legacy.LegacyAnimeEditorScreen
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.DetailCardStyle
import com.animeow.app.ui.theme.DetailModule
import com.animeow.app.ui.anime.EpisodeInfoScreen
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import com.animeow.app.ui.navigation.appComposable as composable

internal fun NavGraphBuilder.animeRoutes(
    navController: NavHostController,
    settings: AppearanceSettings,
    legacyMode: Boolean,
    returnToPlaybackHistory: Boolean,
    onDetailModuleOrderChanged: (List<DetailModule>) -> Unit,
    onHiddenDetailModulesChanged: (Set<DetailModule>) -> Unit,
    onDetailCardStyleSelected: (DetailCardStyle) -> Unit,
    onCollectionReset: () -> Unit,
) {
    composable(
        route = DETAIL_ROUTE,
        arguments = listOf(
            navArgument("animeId") { type = NavType.LongType },
        ),
    ) {
        // Opened from the playback history list: leaving the detail must return to that list,
        // so finish this instance instead of falling back to the library tab.
        val detailContext = androidx.compose.ui.platform.LocalContext.current
        fun finishOrNavigateUp() {
            val activity = detailContext as? android.app.Activity
            if (returnToPlaybackHistory && activity != null) activity.finish() else navController.navigateUp()
        }
        val leaveDetail: () -> Unit = { finishOrNavigateUp() }
        androidx.activity.compose.BackHandler(enabled = returnToPlaybackHistory) { finishOrNavigateUp() }
        if (legacyMode) {
            LegacyAnimeDetailScreen(
                onBack = leaveDetail,
                onEdit = { animeId -> navController.navigate("anime/edit?animeId=$animeId") },
                onEditEpisodes = { animeId -> navController.navigate("anime/$animeId/episodes") },
                settings = settings,
                onDetailModuleOrderChanged = onDetailModuleOrderChanged,
                onHiddenDetailModulesChanged = onHiddenDetailModulesChanged,
                onDetailCardStyleSelected = onDetailCardStyleSelected,
                onManageCharacters = { navController.navigate(CHARACTERS_ROUTE) },
                onAnimeOpen = { navController.navigate("anime/$it") },
                onSeriesOpen = { navController.navigate("series/$it") },
                onTagOpen = { navController.navigate("tags/$it") },
                onCharacterOpen = { characterId -> navController.navigate("$CHARACTERS_ROUTE?characterId=$characterId") },
                onOpenLegadoSync = { navController.navigate(LEGADO_SYNC_ROUTE) },
            )
        } else {
            AnimeDetailScreen(
                onBack = leaveDetail,
                onEdit = { animeId -> navController.navigate("anime/edit?animeId=$animeId") },
                onEditEpisodes = { animeId -> navController.navigate("anime/$animeId/episodes") },
                settings = settings,
                onDetailModuleOrderChanged = onDetailModuleOrderChanged,
                onHiddenDetailModulesChanged = onHiddenDetailModulesChanged,
                onDetailCardStyleSelected = onDetailCardStyleSelected,
                onManageCharacters = { navController.navigate(CHARACTERS_ROUTE) },
                onAnimeOpen = { navController.navigate("anime/$it") },
                onSeriesOpen = { navController.navigate("series/$it") },
                onTagOpen = { navController.navigate("tags/$it") },
                  onCharacterOpen = { characterId -> navController.navigate("$CHARACTERS_ROUTE?characterId=$characterId") },
                onOpenLegadoSync = { navController.navigate(LEGADO_SYNC_ROUTE) },
            )
        }
    }
    composable(
        route = EPISODES_ROUTE_PATTERN,
        arguments = listOf(
            navArgument("animeId") { type = NavType.LongType },
            navArgument("episode") { type = NavType.IntType; defaultValue = 0 },
        ),
    ) { entry ->
        EpisodeInfoScreen(
            onBack = navController::navigateUp,
            initialEpisode = entry.arguments?.getInt("episode")?.takeIf { it > 0 },
        )
    }
    composable(
        route = EDITOR_ROUTE_PATTERN,
        arguments = listOf(
            navArgument("animeId") {
                type = NavType.LongType
                defaultValue = -1L
            },
            navArgument("subjectType") {
                type = NavType.StringType
                defaultValue = "anime"
            },
        ),
    ) {
        val onSaved: (Long) -> Unit = { animeId ->
            navController.navigate("anime/$animeId") {
                popUpTo(AppDestination.TRACKER.route)
                launchSingleTop = true
            }
        }
        val onBatchSaved: () -> Unit = {
            onCollectionReset()
            navController.navigate(AppDestination.TRACKER.route) {
                popUpTo(navController.graph.id)
                launchSingleTop = true
            }
        }
        if (legacyMode) {
            LegacyAnimeEditorScreen(
                onBack = navController::navigateUp,
                onSaved = onSaved,
                onBatchSaved = onBatchSaved,
            )
        } else {
            AnimeEditorScreen(
                onBack = navController::navigateUp,
                onSaved = onSaved,
                onBatchSaved = onBatchSaved,
            )
        }
    }
}
