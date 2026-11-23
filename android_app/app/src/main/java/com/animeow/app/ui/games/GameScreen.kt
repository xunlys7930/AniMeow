package com.animeow.app.ui.games

import com.animeow.app.ui.navigation.PageAction
import com.animeow.app.ui.navigation.PageActionRequests
import com.animeow.app.ui.navigation.HandlePageAction

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.games.*
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.motionAnimateItem
import com.animeow.app.ui.components.motionAnimationSpec
import com.animeow.app.ui.components.motionBarEnter
import com.animeow.app.ui.components.motionBarExit
import com.animeow.app.ui.components.motionExpandIn
import com.animeow.app.ui.components.motionFadeThroughSpec
import com.animeow.app.ui.components.motionShrinkOut
import com.animeow.app.ui.theme.LocalMotionLevel
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.icons.AppIcon as Icon

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GameScreen(settings: AppearanceSettings, modifier: Modifier = Modifier, pageActions: PageActionRequests? = null, onSelectionModeChanged: (Boolean) -> Unit = {}, onSeriesOpen: (Long) -> Unit = {}, viewModel: GameViewModel = viewModel(), initialGameId: Long = 0L) {
    val games by viewModel.games.collectAsStateWithLifecycle()
    var pendingGameId by rememberSaveable(initialGameId) { mutableLongStateOf(initialGameId) }
    LaunchedEffect(games, pendingGameId) {
        games.firstOrNull { it.id == pendingGameId }?.let { game ->
            pendingGameId = 0L
            viewModel.open(game)
        }
    }
    val allSeries by viewModel.allSeries.collectAsStateWithLifecycle()
    var selectionMode by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var batchStatus by remember { mutableStateOf(false) }
    var batchDelete by remember { mutableStateOf(false) }
    var batchTags by remember { mutableStateOf(false) }
    var manageTags by remember { mutableStateOf(false) }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val allTags = remember(games) { games.flatMap { gameTagNames(it.tags) }.distinct().sorted() }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun finishSelection() { selectionMode = false; selection = emptySet() }
    fun toggle(game: GameEntity) { selection = if (game.id in selection) selection - game.id else selection + game.id }
    fun select(game: GameEntity) { if (!viewModel.busy.value) { selectionMode = true; toggle(game); haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) } }
    fun open(game: GameEntity) { if (!viewModel.busy.value) { if (selectionMode) toggle(game) else viewModel.open(game) } }
    LaunchedEffect(selectionMode) { onSelectionModeChanged(selectionMode) }
    DisposableEffect(Unit) { onDispose { onSelectionModeChanged(false) } }

    val search by viewModel.search.collectAsStateWithLifecycle()
    val searched by viewModel.searched.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    BackHandler(selectionMode && !busy) { finishSelection() }
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("全部") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var controls by rememberSaveable { mutableStateOf(false) }
    var series by rememberSaveable { mutableStateOf(false) }
    var appearance by rememberSaveable { mutableStateOf(false) }
    var sync by remember { mutableStateOf(false) }
    var record by remember { mutableStateOf<GameEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val bottomPadding = maxOf(100.dp, com.animeow.app.ui.navigation.LocalFloatingNavigationInset.current + 80.dp)
    HandlePageAction(pageActions, PageAction.CUSTOMIZE_GAME, !busy) { appearance = true }
    HandlePageAction(pageActions, PageAction.SEARCH_GAME, !busy) { searching = true }
    HandlePageAction(pageActions, PageAction.ADD_GAME, !busy) { viewModel.open(GameEntity()) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); viewModel.message.value = null } }
    BackHandler(searching && selected == null) { searching = false; query = ""; viewModel.clearSearch() }
    val shown = remember(games, status, query, tagFilter, settings.gameLayout) {
        games.filter { (status == "全部" || it.status == status) && (tagFilter == null || tagFilter in gameTagNames(it.tags)) && listOf(it.title, it.originalTitle, it.tags).any { value -> value.contains(query, true) } }
            .let { list -> if (settings.gameLayout == GameLayout.RECORD) list.sortedBy { it.status != "在玩" } else list }
    }
    val gameHeader: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            com.animeow.app.ui.components.BrowseSearchField(
                query = query,
                onQueryChanged = { query = it; if (searching) { if (it.isNotBlank()) viewModel.search(it) else viewModel.clearSearch() } },
                placeholder = if (searching) "搜索公共游戏资料" else "搜索我的游戏",
                enabled = !selectionMode,
                onSearch = { if (searching && query.isNotBlank()) viewModel.search(query) },
                controls = {
                    if (searching) TextButton(onClick = { searching = false; query = ""; viewModel.clearSearch() }) { Text("返回") }
                    else if (!selectionMode) com.animeow.app.ui.components.BrowseFilterButton((if (status != "全部") 1 else 0) + (if (tagFilter != null) 1 else 0), { controls = true }, "筛选与显示")
                },
            )
            if (!searching) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf("全部") + gameStatuses).forEach { value ->
                        FilterChip(selected = status == value, onClick = { status = value }, label = { Text(value) })
                    }
                }
            }
            AnimatedVisibility(
                visible = busy || (search.busy && searching),
                enter = motionExpandIn(),
                exit = motionShrinkOut(),
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (!searching && settings.gameLayout == GameLayout.RECORD && games.isNotEmpty() && games.none { it.status == "在玩" }) {
                Text("还没有在玩的游戏，打开记录可改为“在玩”。", Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (pendingCount > 0 && !searching) {
                Text("$pendingCount 项收藏变更待上报，可在筛选与显示中同步重试", Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val gameDisplayMode = when {
        searching -> "search"
        shown.isEmpty() -> "empty"
        settings.gameLayout == GameLayout.GRID -> "grid"
        else -> "list"
    }
    Column(modifier.fillMaxSize()) {
    Box(Modifier.weight(1f)) {
        val motionLevel = LocalMotionLevel.current
        AnimatedContent(
            targetState = gameDisplayMode,
            transitionSpec = { motionFadeThroughSpec(motionLevel, 180, 120) },
            label = "gameDisplayMode",
        ) { mode ->
        when (mode) {
        "search" -> {
            LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, bottomPadding), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                item(key = "game-header") { gameHeader() }
                search.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) } }
                if (search.items.isEmpty() && !search.busy && search.error == null) item { Text(if (searched) "没有找到相关游戏，可以换个名称或手动添加。" else "输入名称并点搜索，或手动添加自己的游戏记录。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(search.items, key = { it.catalogKey }) { game -> GameRow(game, compact = true, onClick = { viewModel.open(games.firstOrNull { sameCatalogGame(it, game) } ?: game) }, modifier = motionAnimateItem()) }
                if (search.items.isNotEmpty() && search.hasMore) item { TextButton(onClick = { viewModel.search(query, more = true) }, enabled = !search.busy) { Text("加载更多") } }
                item { OutlinedButton(onClick = { viewModel.open(GameEntity(title = query.take(200))) }, enabled = !busy) { Text("手动添加游戏") } }
            }
        }
        "empty" -> {
            LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, bottomPadding), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                item(key = "game-header") { gameHeader() }
                item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (query.isNotBlank()) {
                            Text("我的游戏中未找到“$query”", style = MaterialTheme.typography.titleMedium)
                            Text("可在公共游戏资料中查找并添加收藏。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { searching = true; viewModel.search(query) }) {
                                Icon(AppIcons.Search, null, Modifier.padding(end = 6.dp))
                                Text("在公共游戏资料中查找“$query”")
                            }
                            TextButton(onClick = { viewModel.open(GameEntity(title = query.take(200))) }) {
                                Text("手动添加此游戏")
                            }
                        } else {
                            Text(if (games.isEmpty()) "把想玩的故事，收进游戏库" else "没有符合条件的游戏", style = MaterialTheme.typography.titleMedium)
                            Text("记录游玩时长、平台和感受。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { searching = true }) { Text("查找并收藏游戏") }
                            TextButton(onClick = { viewModel.open(GameEntity()) }) { Text("手动添加") }
                        }
                    }
                }
            }
        }
        "grid" -> {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = effectiveGameColumns(settings.gameGridColumns, maxWidth.value - 32f, LocalDensity.current.fontScale)
                LazyVerticalGrid(columns = GridCells.Fixed(columns), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, bottomPadding),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
                    item(key = "game-header", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { gameHeader() }
                    items(shown, key = { it.id }) { game ->
                        val isSelected = game.id in selection
                        val cardColor by animateColorAsState(
                            targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            animationSpec = motionAnimationSpec(180),
                            label = "gameGridCardColor",
                        )
                        Card(modifier = motionAnimateItem().clip(RoundedCornerShape(20.dp)).animatedPressClick(onClick = { open(game) }, onLongClick = { select(game) }, onLongClickLabel = "选择游戏")
                            .semantics { this.selected = isSelected },
                            shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = cardColor)) {
                            Box { GameCover(game, Modifier.fillMaxWidth().aspectRatio(0.72f)); Surface(Modifier.align(Alignment.TopStart).padding(8.dp), color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) { Text(game.status, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium) } }
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text((if (isSelected) "已选 · " else "") + game.title, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                                Text(game.selectedPlatform.ifBlank { gamePlatforms(game.platforms).ifBlank { "平台待补充" } }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (game.vndbId != null) Text(game.catalogSource, style = MaterialTheme.typography.labelSmall)
                Text(gamePlayTime(game.playMinutes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        else -> LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, bottomPadding), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "game-header") { gameHeader() }
            items(shown, key = { it.id }) { game -> GameRow(game, settings.gameLayout == GameLayout.LIST, { open(game) }, if (selectionMode) null else ({ record = game }), { select(game) }, game.id in selection, modifier = motionAnimateItem()) }
        }
        }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (selectionMode) 12.dp else 80.dp))
    }
        AnimatedVisibility(
            visible = selectionMode,
            enter = motionBarEnter(),
            exit = motionBarExit(),
        ) {
            Surface(modifier = Modifier.fillMaxWidth()
                .padding(bottom = com.animeow.app.ui.navigation.LocalFloatingNavigationInset.current), tonalElevation = 3.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${selection.size} 款", Modifier.weight(1f))
                        TextButton(onClick = { selection = if (shown.isNotEmpty() && shown.all { it.id in selection }) emptySet() else shown.map { it.id }.toSet() }, enabled = !busy) { Text(if (shown.isNotEmpty() && shown.all { it.id in selection }) "取消全选" else "全选") }
                        TextButton(onClick = { finishSelection() }, enabled = !busy) { Text("退出") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(onClick = { batchStatus = true }, enabled = selection.isNotEmpty() && !busy) { Text("修改状态") }
                        TextButton(onClick = { batchTags = true }, enabled = selection.isNotEmpty() && !busy) { Text("标签") }
                        TextButton(onClick = { batchDelete = true }, enabled = selection.isNotEmpty() && !busy) { Text("移除收藏") }
                    }
                }
            }
        }
    }
    if (batchTags) GameTagPicker(allTags, busy = busy, batch = true,
        onSave = { tags, remove -> viewModel.updateTags(selection, tags, remove) { batchTags = false; finishSelection() } },
        onDismiss = { batchTags = false })
    if (manageTags) GameTagManager(allTags, busy, viewModel::changeTag, { manageTags = false })
    if (batchStatus) AlertDialog(onDismissRequest = { if (!busy) batchStatus = false }, title = { Text("修改 ${selection.size} 款游戏的状态") },
        text = { Column { gameStatuses.forEach { value -> TextButton(enabled = !busy, onClick = {
            viewModel.updateSelection(selection, status = value) { batchStatus = false; finishSelection() }
        }) { Text(value) } } } }, confirmButton = {}, dismissButton = { TextButton(enabled = !busy, onClick = { batchStatus = false }) { Text("取消") } })
    if (batchDelete) AlertDialog(onDismissRequest = { if (!busy) batchDelete = false }, title = { Text("移除 ${selection.size} 款游戏？") },
        text = { Text("将从本地游戏库移除所选游戏，不会删除 Bangumi 上的收藏。如需恢复，可使用移除前的原生备份。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { viewModel.updateSelection(selection, delete = true) { batchDelete = false; finishSelection() } }) { Text("移除") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { batchDelete = false }) { Text("取消") } })
    if (controls) com.animeow.app.ui.components.StableModalBottomSheet(onDismissRequest = { controls = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("筛选与显示", style = MaterialTheme.typography.headlineSmall)
            com.animeow.app.ui.components.CollectionFilterPlans("game",
                com.animeow.app.data.preferences.CollectionFilterConditions(query = query, status = status, tags = listOfNotNull(tagFilter)),
                allTags, listOf("全部") + gameStatuses, !selectionMode && !busy) { saved ->
                query = saved.query; status = saved.status; tagFilter = saved.tags.firstOrNull(); null
            }
            Text("状态", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("全部") + gameStatuses).forEach { value -> FilterChip(status == value, { status = value }, label = { Text(value) }) }
            }
            Text("标签", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tagFilter == null, { tagFilter = null }, label = { Text("全部标签") })
                allTags.forEach { name -> FilterChip(tagFilter == name, { tagFilter = if (tagFilter == name) null else name }, label = { Text(name) }) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text("管理与资料", style = MaterialTheme.typography.titleMedium)
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    ListItem(
                        headlineContent = { Text("管理收藏") },
                        leadingContent = { Icon(AppIcons.SelectAll, null) },
                        modifier = Modifier.clickable { controls = false; selectionMode = true },
                    )
                    ListItem(
                        headlineContent = { Text("管理游戏标签") },
                        leadingContent = { Icon(AppIcons.Tag, null) },
                        modifier = Modifier.clickable { controls = false; manageTags = true },
                    )
                    ListItem(
                        headlineContent = { Text("管理游戏系列") },
                        leadingContent = { Icon(AppIcons.Collection, null) },
                        modifier = Modifier.clickable { controls = false; series = true },
                    )
                    ListItem(
                        headlineContent = { Text("显示设置") },
                        leadingContent = { Icon(AppIcons.Tune, null) },
                        modifier = Modifier.clickable { controls = false; appearance = true },
                    )
                    ListItem(
                        headlineContent = { Text(if (pendingCount > 0) "同步游戏收藏 · $pendingCount 项待上报" else "同步游戏收藏") },
                        leadingContent = { Icon(AppIcons.Sync, null) },
                        modifier = Modifier.clickable { controls = false; sync = true },
                    )
                    ListItem(
                        headlineContent = { Text("查找公共游戏资料") },
                        leadingContent = { Icon(AppIcons.Search, null) },
                        modifier = Modifier.clickable { controls = false; searching = true; query = ""; viewModel.clearSearch() },
                    )
                }
            }
            FilledTonalButton(onClick = { controls = false }, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
    if (appearance) ModalBottomSheet(onDismissRequest = { appearance = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("游戏显示", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GameLayout.entries.forEach { layout -> FilterChip(selected = settings.gameLayout == layout, onClick = { viewModel.setLayout(layout) }, label = { Text(layout.label) }) }
            }
            Text("每行列数", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (2..6).forEach { value ->
                    FilterChip(
                        selected = settings.gameGridColumns == value,
                        onClick = {
                            if (settings.gameLayout != GameLayout.GRID) viewModel.setLayout(GameLayout.GRID)
                            viewModel.setColumns(value)
                        },
                        label = { Text("$value 列") },
                    )
                }
            }
            Text("列数生效于封面网格模式。如当前不是网格，选择列数会自动切换。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
    }
    if (series) com.animeow.app.ui.series.MediaSeriesDialog(com.animeow.app.data.MediaSeriesKind.GAME, viewModel.seriesRepository,
        games.map { com.animeow.app.data.MediaSeriesWork(it.syncId, it.title, it.updatedAt,
            source = if (it.bangumiId != null) "bangumi" else if (!it.vndbId.isNullOrBlank()) "vndb" else null,
            externalId = it.bangumiId?.toString() ?: it.vndbId) }, { series = false })
    selected?.let { game -> GameDetailSheet(game, busy, onDismiss = { viewModel.selected.value = null }, onSave = viewModel::save, onDelete = viewModel::delete, availableTags = allTags,
        onCompleteMetadata = { viewModel.completeMetadata(game) },
        linkedSeries = com.animeow.app.data.local.seriesLinkedToWork(allSeries, "GAME", game.syncId),
        onSeriesOpen = { seriesId -> viewModel.selected.value = null; onSeriesOpen(seriesId) }) }
    record?.let { game ->
        var minutes by rememberSaveable(game.id) { mutableStateOf("30") }
        AlertDialog(onDismissRequest = { record = null }, title = { Text("记录本次游玩") }, text = {
            OutlinedTextField(minutes, { minutes = it }, label = { Text("分钟（1–1440）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        }, confirmButton = { TextButton(onClick = { viewModel.record(game, minutes.toLong()); record = null }, enabled = !busy && minutes.toLongOrNull() in 1L..1440L) { Text("保存") } }, dismissButton = { TextButton(onClick = { record = null }) { Text("取消") } })
    }
    if (sync) AlertDialog(onDismissRequest = { sync = false }, title = { Text("同步游戏收藏") }, text = {
        Text("使用已连接的 Bangumi 账号。导入会更新状态和评分，保留本地时长、日期与心得；上报会用本地状态和评分更新 Bangumi 收藏。自动上报沿用同步设置中的开关。")
    }, confirmButton = { TextButton(onClick = { sync = false; viewModel.importCollections() }) { Text("从 Bangumi 导入") } }, dismissButton = {
        Column { TextButton(onClick = { sync = false; viewModel.uploadCollections() }) { Text("上报到 Bangumi") }; TextButton(onClick = { sync = false }) { Text("取消") } }
    })
}

@Composable
internal fun GameCover(game: GameEntity, modifier: Modifier) {
    var failed by remember(game.coverUrl) { mutableStateOf(false) }
    var loaded by remember(game.coverUrl) { mutableStateOf(false) }
    val coverAlpha by animateFloatAsState(
        targetValue = if (loaded) 1f else 0f,
        animationSpec = motionAnimationSpec(220),
        label = "gameCoverAlpha",
    )
    Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (!loaded) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(game.title.take(1).ifBlank { "游" }, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(if (game.coverUrl.isBlank()) "暂无封面" else if (failed) "封面暂未加载" else "正在加载封面", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (game.coverUrl.isNotBlank()) AsyncImage(
            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current).data(game.coverUrl).diskCacheKey("game-cover:${game.coverUrl}").build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = coverAlpha },
            onError = { failed = true; loaded = false },
            onSuccess = { failed = false; loaded = true },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GameRow(game: GameEntity, compact: Boolean, onClick: () -> Unit, onRecord: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null, selected: Boolean = false, modifier: Modifier = Modifier) {
    val rowColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = motionAnimationSpec(180),
        label = "gameRowCardColor",
    )
    Card(modifier = modifier.clip(RoundedCornerShape(20.dp)).animatedPressClick(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "选择游戏").semantics { this.selected = selected }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = rowColor)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GameCover(game, Modifier.width(if (compact) 64.dp else 88.dp).aspectRatio(0.72f).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text((if (selected) "已选 · " else "") + game.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text("${game.status} · ${game.selectedPlatform.ifBlank { game.platforms.ifBlank { "平台待补充" } }}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (compact) Text(game.catalogSource, style = MaterialTheme.typography.labelSmall)
                Text(gamePlayTime(game.playMinutes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (!compact && onRecord != null) FilledTonalButton(onClick = if (game.status == "在玩" || game.status == "想玩") onRecord else onClick) { Text(if (game.status == "在玩" || game.status == "想玩") "记录本次游玩" else "修改记录") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun GameDetailSheet(game: GameEntity, busy: Boolean, onDismiss: () -> Unit, onSave: (GameEntity) -> Unit, onDelete: (GameEntity) -> Unit,
    onCompleteMetadata: (() -> Unit)? = null, availableTags: List<String> = emptyList(),
    linkedSeries: List<com.animeow.app.data.local.SeriesEntity> = emptyList(),
    onSeriesOpen: ((Long) -> Unit)? = null) {
    var editing by rememberSaveable(game.id, game.catalogKey) { mutableStateOf(game.bangumiId == null && game.vndbId == null && game.id == 0L) }
    var title by rememberSaveable(game.id, game.catalogKey) { mutableStateOf(game.title) }
    var originalTitle by rememberSaveable(game.id, game.catalogKey, game.originalTitle) { mutableStateOf(game.originalTitle) }
    var developer by rememberSaveable(game.id, game.catalogKey, game.developer) { mutableStateOf(game.developer) }
    var tags by rememberSaveable(game.id, game.catalogKey) { mutableStateOf(game.tags) }
    var releaseDate by rememberSaveable(game.id, game.catalogKey, game.releaseDate) { mutableStateOf(game.releaseDate) }
    var status by rememberSaveable(game.id, game.catalogKey) { mutableStateOf(game.status) }
    var platform by rememberSaveable(game.id, game.catalogKey) { mutableStateOf(game.selectedPlatform) }
    var hours by rememberSaveable(game.id, game.catalogKey) { mutableStateOf((game.playMinutes / 60).toString()) }
    var minutes by rememberSaveable(game.id, game.catalogKey) { mutableStateOf((game.playMinutes % 60).toString()) }
    var start by rememberSaveable(game.id, game.catalogKey) { mutableStateOf(game.startedOn) }
    var finish by rememberSaveable(game.id, game.catalogKey) { mutableStateOf(game.finishedOn) }
    var rating by rememberSaveable(game.id, game.catalogKey) { mutableStateOf(game.rating.toString()) }
    var review by rememberSaveable(game.id, game.catalogKey) { mutableStateOf(game.review) }
    var deleting by remember { mutableStateOf(false) }
    var pickingTags by remember { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable(game.id, game.catalogKey) { mutableStateOf(false) }
    val dirty = title != game.title || originalTitle != game.originalTitle || developer != game.developer || tags != game.tags ||
        releaseDate != game.releaseDate || status != game.status || platform != game.selectedPlatform ||
        hours != (game.playMinutes / 60).toString() || minutes != (game.playMinutes % 60).toString() ||
        start != game.startedOn || finish != game.finishedOn || rating != game.rating.toString() || review != game.review
    fun dismiss() { if (!busy) { if (dirty) confirmDiscard = true else onDismiss() } }
    val validDate: (String) -> Boolean = { it.isBlank() || runCatching { java.time.LocalDate.parse(it) }.isSuccess }
    val valid = title.isNotBlank() && title.length <= 200 && originalTitle.length <= 500 && developer.length <= 500 &&
        (releaseDate == game.releaseDate || validDate(releaseDate)) && hours.toLongOrNull() in 0L..1_000_000L && minutes.toLongOrNull() in 0L..59L &&
        rating.toIntOrNull() in 0..10 && validDate(start) && validDate(finish) && (start.isBlank() || finish.isBlank() || start <= finish) &&
        (hours.toLongOrNull() ?: 0) * 60 + (minutes.toLongOrNull() ?: 0) <= 60_000_000L
    if (!confirmDiscard && !deleting) androidx.compose.ui.window.Dialog(onDismissRequest = { if (pickingTags) pickingTags = false else dismiss() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        if (pickingTags) GameTagPicker(availableTags, tags, busy, embedded = true,
            onSave = { value, _ -> tags = value; pickingTags = false }, onDismiss = { pickingTags = false })
        else Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TextButton(onClick = ::dismiss, enabled = !busy) { Text("返回") }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GameCover(game, Modifier.width(100.dp).aspectRatio(0.72f).clip(RoundedCornerShape(16.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(game.title.ifBlank { "添加游戏" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (game.originalTitle != game.title) Text(game.originalTitle.ifBlank { "原名待补充" }, style = MaterialTheme.typography.bodySmall)
                    Text(game.releaseDate.ifBlank { "发售日期待补充" }, style = MaterialTheme.typography.bodySmall)
                    if (game.score > 0) Text("大众评分 ${gameScore(game.score)}", color = MaterialTheme.colorScheme.primary)
                }
            }
            if (!editing) {
                if (linkedSeries.isNotEmpty() && onSeriesOpen != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("所属系列", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            linkedSeries.forEach { seriesItem ->
                                AssistChip(
                                    onClick = { onSeriesOpen(seriesItem.id) },
                                    leadingIcon = { Icon(AppIcons.Collection, null, Modifier.size(16.dp)) },
                                    label = { Text(seriesItem.name) },
                                )
                            }
                        }
                    }
                }
                listOf("平台" to gamePlatforms(game.platforms), "开发" to game.developer, "发行" to game.publisher, "类型" to game.genre).filter { it.second.isNotBlank() }.forEach { (label, value) -> Text("$label：$value", style = MaterialTheme.typography.bodyMedium) }
                Text("资料来源：${game.catalogSource}" + if (game.vndbId != null) " · 不参与 Bangumi 收藏同步" else "", style = MaterialTheme.typography.bodySmall)
                gameSynopsisNotice(game)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(game.synopsis.ifBlank { "暂无简介" }, style = MaterialTheme.typography.bodyMedium)
                if (game.developer.isBlank()) Text("开发商待补充", style = MaterialTheme.typography.bodyMedium)
                if (game.tags.isNotBlank()) Text("作品标签：${game.tags}", style = MaterialTheme.typography.bodyMedium)
                if ((game.bangumiId != null || game.vndbId != null) && onCompleteMetadata != null) {
                    OutlinedButton(onClick = onCompleteMetadata, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "正在读取资料" else "补全缺失资料")
                    }
                    Text("仅补空白资料，已有内容和个人记录保持不变。", style = MaterialTheme.typography.bodySmall)
                }
                if (game.id > 0) {
                    Text("${game.status} · ${gamePlayTime(game.playMinutes)}", style = MaterialTheme.typography.titleMedium)
                    if (game.selectedPlatform.isNotBlank()) Text("游玩平台：${game.selectedPlatform}")
                    if (game.startedOn.isNotBlank()) Text("开始：${game.startedOn}")
                    if (game.finishedOn.isNotBlank()) Text("完成：${game.finishedOn}")
                    if (game.rating > 0) Text("我的评分：${game.rating} / 10")
                    if (game.review.isNotBlank()) Text(game.review)
                }
                Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text(if (game.id == 0L) "加入我的游戏" else "修改记录") }
            } else {
                OutlinedTextField(title, { title = it }, label = { Text("游戏名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(originalTitle, { originalTitle = it.take(500) }, label = { Text("游戏原名") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                OutlinedTextField(developer, { developer = it.take(500) }, label = { Text("开发商") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                OutlinedButton(onClick = { pickingTags = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (tags.isBlank()) "选择或新建标签" else "标签：$tags")
                }
                OutlinedTextField(releaseDate, { releaseDate = it }, label = { Text("发售日期（YYYY-MM-DD，可留空）") },
                    isError = releaseDate != game.releaseDate && !validDate(releaseDate), modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { gameStatuses.forEach { value -> FilterChip(selected = status == value, onClick = { status = value }, label = { Text(value) }) } }
                OutlinedTextField(platform, { platform = it }, label = { Text("我的游玩平台") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(hours, { hours = it }, label = { Text("累计小时") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(minutes, { minutes = it }, label = { Text("分钟（0–59）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(start, { start = it }, label = { Text("开始日期（YYYY-MM-DD，可留空）") }, isError = !validDate(start), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(finish, { finish = it }, label = { Text("完成日期（可留空）") }, isError = !validDate(finish) || start.isNotBlank() && finish.isNotBlank() && start > finish, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("“已玩”表示玩过这款游戏，不代表已经通关。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(rating, { rating = it }, label = { Text("我的评分（0–10，0 为未评分）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(review, { review = it.take(10000) }, label = { Text("游玩心得") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Button(onClick = { onSave(game.copy(title = title, originalTitle = originalTitle.trim(), developer = developer.trim(), tags = tags, releaseDate = releaseDate.trim(), status = status, selectedPlatform = platform, playMinutes = hours.toLong() * 60 + minutes.toLong(), startedOn = start, finishedOn = finish, rating = rating.toInt(), review = review)) }, enabled = valid && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在保存…" else "保存游戏记录") }
                if (game.id > 0) TextButton(onClick = { deleting = true }, enabled = !busy) { Text("移出游戏库", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("保留未保存的修改？") },
        text = { Text("游戏记录尚未保存，可以继续编辑或放弃本次修改。") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text("放弃修改") } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("移出游戏库？") }, text = { Text("这会移除本地游戏记录，并通过 AniMeow 云同步保留删除状态。Bangumi 收藏不受影响。") }, confirmButton = { TextButton(onClick = { deleting = false; onDelete(game) }) { Text("移除") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
}
