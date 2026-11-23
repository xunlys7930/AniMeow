package com.animeow.app.ui.navigation

import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.animeow.app.ui.importer.BangumiImportScreen
import com.animeow.app.ui.importer.BangumiUploadScreen
import com.animeow.app.ui.importer.SpreadsheetTransferScreen
import com.animeow.app.ui.preferences.CustomizationScreen
import com.animeow.app.ui.preferences.BrandingScreen
import com.animeow.app.ui.reader.LegadoSyncScreen
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import com.animeow.app.ui.navigation.appComposable as composable

internal fun NavGraphBuilder.preferenceRoutes(navController: NavHostController) {
    composable(BANGUMI_IMPORT_ROUTE) {
        BangumiImportScreen(onBack = navController::navigateUp, onMerge = { navController.navigate(BANGUMI_UPLOAD_ROUTE) })
    }
    composable(BANGUMI_UPLOAD_ROUTE) {
        BangumiUploadScreen(
            onBack = navController::navigateUp,
            onCloudAccountRequested = { navController.navigate(CLOUD_ACCOUNT_ROUTE) },
        )
    }
    composable(SPREADSHEET_TRANSFER_ROUTE) {
        SpreadsheetTransferScreen(onBack = navController::navigateUp)
    }
    composable(LEGADO_SYNC_ROUTE) {
        LegadoSyncScreen(onBack = navController::navigateUp)
    }
    composable(
        route = CUSTOMIZATION_ROUTE_PATTERN,
        arguments = listOf(
            navArgument("query") {
                type = NavType.StringType
                defaultValue = ""
            },
            navArgument("section") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { entry ->
        CustomizationScreen(
            onBack = navController::navigateUp,
            onCloudAccountRequested = { navController.navigate(CLOUD_ACCOUNT_ROUTE) },
            onBrandingRequested = { navController.navigate(BRANDING_ROUTE) },
            initialQuery = entry.arguments?.getString("query").orEmpty(),
            initialSection = entry.arguments?.getString("section")?.takeIf(String::isNotBlank),
        )
    }
    composable(BRANDING_ROUTE) {
        BrandingScreen(onBack = navController::navigateUp)
    }
}
