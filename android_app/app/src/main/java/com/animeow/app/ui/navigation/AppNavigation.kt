package com.animeow.app.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A shortcut opens a page above its caller; selecting the navigation bar switches tabs. */
internal val AppDestination.pageRoute: String get() = "page/$route"

internal fun NavHostController.selectMainDestination(destination: AppDestination) {
    if (currentDestination?.route == destination.route) return
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

internal class AppBackNavigation(
    val currentRoute: String?,
    val predictive: Boolean,
    val mainRoutes: Set<String>,
    val interceptMainBack: Boolean,
    val onMainBack: () -> Unit,
    val onPageBack: () -> Unit,
    val onMainPredictiveProgress: (BackEventCompat) -> Unit,
    val onMainPredictiveEnd: suspend () -> Unit,
)

internal val LocalAppBackNavigation = staticCompositionLocalOf<AppBackNavigation?> { null }

/**
 * Install the fallback after NavHost's handler and before page content. Editors, sheets and
 * selection modes can still handle Back first. Disabling the interactive preview must not
 * disable the ordinary pop animation triggered by either the toolbar or the system Back key.
 */
internal fun NavGraphBuilder.appComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route, arguments) { entry ->
        val behavior = LocalAppBackNavigation.current
        val active = behavior != null && behavior.currentRoute == entry.destination.route
        val main = entry.destination.route in behavior?.mainRoutes.orEmpty()
        PredictiveBackHandler(enabled = active && main && behavior!!.predictive && behavior.interceptMainBack) { events ->
            try {
                events.collect { behavior!!.onMainPredictiveProgress(it) }
                behavior!!.onMainBack()
            } catch (_: CancellationException) {
                // A cancelled gesture must not navigate or show an exit confirmation.
            } finally {
                withContext(NonCancellable) { behavior!!.onMainPredictiveEnd() }
            }
        }
        BackHandler(enabled = active && !behavior!!.predictive) {
            if (main) behavior!!.onMainBack() else behavior!!.onPageBack()
        }
        // Keep an outgoing tab's padding steady while its floating bar animates away.
        CompositionLocalProvider(LocalFloatingNavigationInset provides if (main) LocalFloatingNavigationInset.current else 0.dp) {
            Box(Modifier.fillMaxSize().testTag("app-route:$route")) { content(entry) }
        }
    }
}
