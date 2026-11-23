package com.animeow.app.ui.games

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.games.GameEntity
import com.animeow.app.data.games.gameScore
import com.animeow.app.data.games.gamePlatforms
import com.animeow.app.data.games.sameCatalogGame
import com.animeow.app.data.games.catalogKey
import com.animeow.app.data.games.catalogSource
import com.animeow.app.data.preferences.DiscoveryDisplayConfig
import com.animeow.app.data.preferences.DiscoveryResultLayout
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.motionAnimateItem
import com.animeow.app.ui.components.motionExpandIn
import com.animeow.app.ui.components.motionShrinkOut
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.icons.AppIcon as Icon
import kotlinx.coroutines.launch

@Composable
internal fun GameDiscoveryScreen(
    display: DiscoveryDisplayConfig,
    onDisplaySettings: () -> Unit,
    modifier: Modifier = Modifier,
    onGlobalSearchRequested: () -> Unit = {},
    viewModel: GameViewModel = viewModel(key = "discovery-games"),
    initialQuery: String = "",
) {
    var showControls by remember { mutableStateOf(false) }
    val state by viewModel.discovery.collectAsStateWithLifecycle()
    val library by viewModel.games.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val localById = remember(library) { library.associateBy { it.catalogKey } }
    LaunchedEffect(viewModel) { viewModel.startDiscovery(initialQuery) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); viewModel.message.value = null } }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = if (display.layout == DiscoveryResultLayout.GRID)
            com.animeow.app.data.games.effectiveGameColumns(display.gridColumns.coerceIn(2, 5), maxWidth.value - 24f, fontScale) else 1
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, maxOf(104.dp, com.animeow.app.ui.navigation.LocalFloatingNavigationInset.current + 16.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                com.animeow.app.ui.components.BrowseSearchField(
                    state.query, { if (it.length <= 80) viewModel.loadDiscovery(query = it, page = 1, debounce = true) },
                    "搜索公共游戏资料", controls = {
                        com.animeow.app.ui.components.BrowseFilterButton(if (state.sort == "heat") 0 else 1, { showControls = true })
                    },
                )
            }
            item(key = "loading") {
                AnimatedVisibility(visible = state.busy, enter = motionExpandIn(), exit = motionShrinkOut()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            state.error?.let { error -> item {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { viewModel.loadDiscovery() }, enabled = !state.busy) { Text("重新加载") }
            } }
            if (state.loaded && !state.busy && state.error == null) item {
                if (state.query.isNotBlank()) Text("当前检索范围内找到 ${state.total} 款匹配游戏", style = MaterialTheme.typography.labelLarge)
                com.animeow.app.data.games.gameDiscoveryMessages(state.stale, state.updatedAt,
                    state.query.isBlank() && state.sort == "heat", state.freshness).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.scope?.takeIf { state.query.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (state.items.isEmpty() && state.loaded && !state.busy && state.error == null) item {
                Text("没有找到匹配的游戏，试试其他关键词。", Modifier.padding(vertical = 24.dp))
            }
            items(state.items.chunked(columns), key = { row -> row.joinToString(":") { it.catalogKey } }) { row ->
                Row(Modifier.fillMaxWidth().then(motionAnimateItem()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { game ->
                        val local = localById[game.catalogKey] ?: library.firstOrNull { sameCatalogGame(it, game) }
                        GameDiscoveryCard(game, local != null, columns, busy, Modifier.weight(1f), display,
                            onOpen = { viewModel.open(local ?: game) },
                            onCollect = { if (local != null) viewModel.open(local) else viewModel.save(game) })
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (state.items.isNotEmpty() || state.page > 1) item {
                com.animeow.app.ui.components.PageNavigationBar(
                    currentPage = state.page, totalPages = state.totalPages,
                    hasNext = state.hasMore, loading = state.busy,
                    status = "服务器资料 · 共 ${state.total} 款",
                    onPageSelected = { viewModel.loadDiscovery(page = it); scope.launch { listState.scrollToItem(0) } },
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }
    if (showControls) GameDiscoveryControls(state.sort,
        onApply = { sort -> showControls = false; if (sort != state.sort) viewModel.loadDiscovery(sort = sort, page = 1) },
        onDismiss = { showControls = false },
        onDisplay = { showControls = false; onDisplaySettings() },
        onRefresh = { showControls = false; viewModel.loadDiscovery() },
        onGlobalSearch = { showControls = false; onGlobalSearchRequested() })
    selected?.let { game -> GameDetailSheet(game, busy, { if (!busy) viewModel.selected.value = null }, viewModel::save, viewModel::delete,
        availableTags = library.flatMap { com.animeow.app.data.games.gameTagNames(it.tags) }.distinct().sorted()) }
}

@Composable
private fun GameDiscoveryCard(game: GameEntity, added: Boolean, columns: Int, busy: Boolean,
    modifier: Modifier, display: DiscoveryDisplayConfig, onOpen: () -> Unit, onCollect: () -> Unit) {
    Card(
        modifier = modifier.clip(CardDefaults.shape).animatedPressClick(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        if (columns > 1) {
            GameCover(game, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            GameDiscoveryInfo(game, added, columns, busy, display, onCollect)
        } else Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GameCover(game, Modifier.width(72.dp).aspectRatio(2f / 3f))
            Box(Modifier.weight(1f)) { GameDiscoveryInfo(game, added, columns, busy, display, onCollect) }
        }
    }
}

@Composable
private fun GameDiscoveryInfo(game: GameEntity, added: Boolean, columns: Int, busy: Boolean,
    display: DiscoveryDisplayConfig, onCollect: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding((10 * display.density.scale).dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(game.title, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        Text(gamePlatforms(game.platforms).ifBlank { "平台待补充" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        if (display.showSource) Text(game.catalogSource, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (display.showScore) Text(if (game.score > 0) "评分 ${gameScore(game.score)}" else "暂无评分", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (display.showAirDate) Text(game.releaseDate.ifBlank { "日期待补充" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (display.showTags) Text(game.genre.ifBlank { "类型待补充" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (columns >= 4) IconButton(onClick = onCollect, enabled = !busy) { Icon(if (added) AppIcons.CheckCircle else AppIcons.Add, if (added) "打开已收藏的《${game.title}》" else "收藏《${game.title}》") }
        else FilledTonalButton(onClick = onCollect, enabled = !busy) { Text(if (added) "已收藏" else "收藏", maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameDiscoveryControls(sort: String, onApply: (String) -> Unit, onDismiss: () -> Unit,
    onDisplay: () -> Unit, onRefresh: () -> Unit, onGlobalSearch: () -> Unit) {
    var draft by remember(sort) { mutableStateOf(sort) }
    com.animeow.app.ui.components.StableModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("游戏发现筛选", style = MaterialTheme.typography.headlineSmall)
            Text("资料来源：服务器已整理的游戏资料", style = MaterialTheme.typography.bodyMedium)
            Text("排序", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("heat" to "收藏热度", "score" to "评分", "date" to "发售日期").forEach { (key, label) ->
                    FilterChip(draft == key, { draft = key }, label = { Text(label) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDisplay) { Text("显示设置") }
                TextButton(onClick = onRefresh) { Text("刷新内容") }
                TextButton(onClick = onGlobalSearch) { Text("全局搜索") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { draft = "heat" }) { Text("重置") }
                Button(onClick = { onApply(draft) }) { Text("应用") }
            }
        }
    }
}
