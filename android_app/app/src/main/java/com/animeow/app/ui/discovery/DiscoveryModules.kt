package com.animeow.app.ui.discovery

import com.animeow.app.ui.navigation.PageAction
import com.animeow.app.ui.navigation.PageActionRequests
import com.animeow.app.ui.navigation.HandlePageAction

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.animeow.app.data.remote.DiscoveryFeedMode

enum class DiscoveryModule(val key: String, val label: String) {
    ANIME("anime", "动画"), BOOK("book", "书籍"), GAME("game", "游戏");
    companion object { fun fromKey(key: String?) = if (key in listOf("novel", "manga")) BOOK else entries.firstOrNull { it.key == key } ?: ANIME }
}

internal fun discoveryFeeds(type: String): List<DiscoveryFeedMode> = when (type) {
    "game" -> emptyList()
    "book" -> listOf(DiscoveryFeedMode.FEATURED, DiscoveryFeedMode.RANKING)
    else -> DiscoveryFeedMode.entries
}

@Composable
internal fun DiscoveryModuleHost(
    modifier: Modifier,
    viewModel: DiscoveryViewModel,
    pageActions: PageActionRequests? = null,
    onBackRequested: (() -> Unit)? = null,
    onGlobalSearchRequested: () -> Unit = {},
    catalog: @Composable (Modifier) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val visibleCategories by com.animeow.app.data.preferences.LibraryCategories.visible.collectAsStateWithLifecycle()
    val modules = DiscoveryModule.entries.filter { it.key in visibleCategories }
    LaunchedEffect(modules.map { it.key }) {
        if (modules.none { it.key == state.subjectType }) viewModel.selectSubjectType(modules.firstOrNull()?.key ?: "anime")
    }
    var showDisplay by rememberSaveable { mutableStateOf(false) }
    HandlePageAction(pageActions, PageAction.CUSTOMIZE_DISCOVERY) { showDisplay = true }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            onBackRequested?.let { back ->
                androidx.compose.material3.IconButton(onClick = back) { com.animeow.app.ui.icons.AppIcon(com.animeow.app.ui.icons.AppIcons.Back, "返回来源页面") }
            }
            androidx.compose.material3.PrimaryTabRow(selectedTabIndex = modules.indexOfFirst { it.key == state.subjectType }.coerceAtLeast(0), modifier = Modifier.weight(1f), containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                modules.forEach { module ->
                    androidx.compose.material3.Tab(selected = state.subjectType == module.key, onClick = { viewModel.selectSubjectType(module.key) }, text = { Text(module.label, maxLines = 1) })
                }
            }
        }
        if (state.subjectType == "book") Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(null to "全部书籍", "小说" to "小说", "轻小说" to "轻小说", "漫画" to "漫画").forEach { (tag, label) ->
                FilterChip(state.filters.tag == tag, { viewModel.updateFilters(state.filters.copy(tag = tag)) }, label = { Text(label) })
            }
        }
        if (state.subjectType == "game" && "game" in visibleCategories) {
            com.animeow.app.ui.games.GameDiscoveryScreen(state.networkSettings.display, { showDisplay = true }, Modifier.weight(1f), onGlobalSearchRequested = onGlobalSearchRequested)
        } else key(state.subjectType) { catalog(Modifier.weight(1f)) }
    }
    if (showDisplay) com.animeow.app.ui.screens.DiscoveryDisplaySheet(
        state.networkSettings.display, viewModel::saveDisplaySettings,
        onDismiss = { showDisplay = false },
    )
}
