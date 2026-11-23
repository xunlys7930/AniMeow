package com.animeow.app.ui.navigation

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController

enum class PageAction(val destination: AppDestination, internal val collection: String? = null) {
    QUICK_ADD(AppDestination.TRACKER, "library"),
    ADD_GAME(AppDestination.TRACKER, "game"), SEARCH_GAME(AppDestination.TRACKER, "game"), CUSTOMIZE_GAME(AppDestination.TRACKER, "game"),
    ADD_AUDIO(AppDestination.TRACKER, "audio"), SEARCH_AUDIO(AppDestination.TRACKER, "audio"), CUSTOMIZE_TRACKER(AppDestination.TRACKER, "library"),
    CUSTOMIZE_DISCOVERY(AppDestination.DISCOVERY), CUSTOMIZE_CALENDAR(AppDestination.CALENDAR),
    COMPOSE_POST(AppDestination.COMMUNITY),
}

/** One pending UI intent per navigation entry. A later tap replaces an unhandled tap, never queues writes. */
class PageActionRequests internal constructor(private val destination: AppDestination, private val saved: SavedStateHandle) {
    internal val pending = saved.getStateFlow<String?>(KEY, null)
    init { if (pending.value != null && PageAction.entries.none { it.name == pending.value && it.destination == destination }) saved[KEY] = null }
    internal fun request(action: PageAction): Boolean {
        if (action.destination != destination) return false
        saved[KEY] = action.name
        return true
    }
    internal fun isPending(action: PageAction) = action.destination == destination && pending.value == action.name
    internal fun consume(action: PageAction): Boolean {
        if (!isPending(action)) return false
        saved[KEY] = null
        return true
    }
    internal fun discardOtherCollections(category: String) {
        val target = when (category) { "game", "audio" -> category; else -> "library" }
        PageAction.entries.firstOrNull { isPending(it) && it.destination == AppDestination.TRACKER && it.collection != target }?.let(::consume)
    }
    private companion object { const val KEY = "page_pending_action" }
}

internal fun pageActionDestination(route: String?): AppDestination? =
    listOf(AppDestination.TRACKER, AppDestination.DISCOVERY, AppDestination.CALENDAR, AppDestination.COMMUNITY)
        .firstOrNull { route == it.route || route == it.pageRoute }

internal fun NavHostController.requestPageAction(action: PageAction): Boolean {
    val entry = currentBackStackEntry ?: return false
    val destination = pageActionDestination(entry.destination.route) ?: return false
    return PageActionRequests(destination, entry.savedStateHandle).request(action)
}

@Composable
internal fun rememberPageActions(entry: NavBackStackEntry): PageActionRequests = remember(entry) {
    PageActionRequests(checkNotNull(pageActionDestination(entry.destination.route)), entry.savedStateHandle)
}

@Composable
internal fun isPageActionPending(entry: NavBackStackEntry?, action: PageAction): Boolean {
    if (entry == null || pageActionDestination(entry.destination.route) != action.destination) return false
    val requests = rememberPageActions(entry)
    val pending by requests.pending.collectAsStateWithLifecycle()
    return pending == action.name
}

@Composable
internal fun HandlePageAction(requests: PageActionRequests?, action: PageAction, enabled: Boolean = true, onAction: () -> Unit) {
    if (requests == null) return
    val pending by requests.pending.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val latestAction by rememberUpdatedState(onAction)
    LaunchedEffect(requests, action, pending, enabled, lifecycle) {
        if (enabled && lifecycle == Lifecycle.State.RESUMED && requests.isPending(action)) {
            latestAction()
            requests.consume(action)
        }
    }
}
