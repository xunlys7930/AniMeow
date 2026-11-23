package com.animeow.app.ui.navigation

import com.animeow.app.ui.diagnostics.DiagnosticsScreen
import com.animeow.app.ui.maintenance.AboutCenterScreen
import com.animeow.app.ui.maintenance.CacheManagementScreen
import com.animeow.app.ui.maintenance.ChangelogScreen
import com.animeow.app.ui.maintenance.CreditsSupportScreen
import com.animeow.app.ui.maintenance.SupportDevelopmentScreen
import com.animeow.app.ui.maintenance.DisclaimerScreen
import com.animeow.app.ui.maintenance.HelpCenterScreen
import com.animeow.app.ui.maintenance.UpdateViewModel
import com.animeow.app.ui.manual.UserManualScreen
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.animeow.app.ui.maintenance.UpdateUiState
import com.animeow.app.ui.navigation.appComposable as composable

internal fun NavGraphBuilder.maintenanceRoutes(
    navController: NavHostController,
    updateState: UpdateUiState,
    updateViewModel: UpdateViewModel,
) {
    composable(DIAGNOSTICS_ROUTE) {
        DiagnosticsScreen(onBack = navController::navigateUp)
    }
    composable(ABOUT_ROUTE) {
        AboutCenterScreen(
            updateState = updateState,
            onBack = navController::navigateUp,
            onCheckUpdate = { updateViewModel.check() },
            onAutoCheckChanged = updateViewModel::setAutoCheckUpdates,
            onClearIgnoredVersion = updateViewModel::clearIgnoredVersion,
            onCacheRequested = { navController.navigate(CACHE_ROUTE) },
            onHelpRequested = { navController.navigate(HELP_ROUTE) },
            onChangelogRequested = { navController.navigate(CHANGELOG_ROUTE) },
            onDisclaimerRequested = { navController.navigate(DISCLAIMER_ROUTE) },
            onCreditsRequested = { navController.navigate(CREDITS_ROUTE) },
            onManualRequested = { navController.navigate(USER_MANUAL_ROUTE) },
        )
    }
    composable(CACHE_ROUTE) { CacheManagementScreen(onBack = navController::navigateUp) }
    composable(HELP_ROUTE) {
        HelpCenterScreen(
            onBack = navController::navigateUp,
            onDiagnosticsRequested = { navController.navigate(DIAGNOSTICS_ROUTE) },
            onCacheRequested = { navController.navigate(CACHE_ROUTE) },
            onManualRequested = { navController.navigate(USER_MANUAL_ROUTE) },
        )
    }
    composable(CHANGELOG_ROUTE) { ChangelogScreen(onBack = navController::navigateUp) }
    composable(DISCLAIMER_ROUTE) { DisclaimerScreen(onBack = navController::navigateUp) }
    composable(CREDITS_ROUTE) {
        CreditsSupportScreen(onBack = navController::navigateUp, onSupportRequested = { navController.navigate(SUPPORT_ROUTE) })
    }
    composable(SUPPORT_ROUTE) { SupportDevelopmentScreen(onBack = navController::navigateUp) }
    composable(
        "$USER_MANUAL_ROUTE?query={query}",
        arguments = listOf(navArgument("query") { type = NavType.StringType; defaultValue = "" }),
    ) { entry ->
        UserManualScreen(onBack = navController::navigateUp, initialQuery = entry.arguments?.getString("query").orEmpty())
    }

}
