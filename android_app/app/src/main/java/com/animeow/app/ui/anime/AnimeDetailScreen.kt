package com.animeow.app.ui.anime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.formatAnimeRating
import com.animeow.app.data.local.normalizeAnimeRatingGrade
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.local.subjectTypeDisplayName
import com.animeow.app.data.local.CharacterWithRole
import com.animeow.app.data.local.CharacterListItem
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.remote.RemoteCharacter
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.components.ZoomableImageViewer
import com.animeow.app.util.runCatchingCancellable
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionAnimationSpec
import com.animeow.app.ui.theme.LocalMotionLevel
import com.animeow.app.ui.theme.LocalDetailLayout
import com.animeow.app.ui.theme.LocalContentDensity
import com.animeow.app.ui.theme.DetailLayout
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.CharacterImageAlignment
import com.animeow.app.ui.theme.DetailCardStyle
import com.animeow.app.ui.theme.DetailModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val LocalDetailCardStyle = staticCompositionLocalOf { DetailCardStyle.TONAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    settings: AppearanceSettings,
    onDetailModuleOrderChanged: (List<DetailModule>) -> Unit,
    onHiddenDetailModulesChanged: (Set<DetailModule>) -> Unit,
    onDetailCardStyleSelected: (DetailCardStyle) -> Unit,
    onManageCharacters: () -> Unit,
    onAnimeOpen: (Long) -> Unit,
    onSeriesOpen: (Long) -> Unit,
    onTagOpen: (Long) -> Unit,
    onCharacterOpen: (Long) -> Unit,
    viewModel: AnimeDetailViewModel = viewModel(),
) {
    val anime by viewModel.anime.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val tagIds by viewModel.tagIds.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val allCharacters by viewModel.allCharacters.collectAsStateWithLifecycle()
    val series by viewModel.series.collectAsStateWithLifecycle()
    val siblings by viewModel.siblings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var showProgressDialog by remember { mutableStateOf(false) }
    var showModuleSheet by remember { mutableStateOf(false) }
    var showStatusSheet by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showCharacterLinker by remember { mutableStateOf(false) }
    var showCoverViewer by remember { mutableStateOf(false) }
    val motionLevel = LocalMotionLevel.current
    val animationDuration = (260 * motionLevel.durationScale).toInt()

    if (anime == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("番剧详情") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val currentAnime = anime ?: return
    val sections = remember(
        settings.detailModuleOrder,
        settings.hiddenDetailModules,
        tagIds,
        currentAnime.review,
        currentAnime.synopsis,
        currentAnime.reminderDay,
        currentAnime.reminderTime,
        series,
    ) {
        settings.detailModuleOrder.filterNot { section ->
            section in settings.hiddenDetailModules ||
                (section == DetailModule.TAGS && tagIds.isEmpty()) ||
                (section == DetailModule.SYNOPSIS && currentAnime.synopsis.isNullOrBlank()) ||
                (section == DetailModule.REMINDER &&
                    (currentAnime.reminderDay == null || currentAnime.reminderTime.isNullOrBlank())) ||
                (section == DetailModule.SERIES && series == null) ||
                (section == DetailModule.REVIEW && false)
        }
    }
    fun checkIn() {
        coroutineScope.launch {
            val change = viewModel.incrementProgress(
                autoCompleteStatus = settings.autoCompleteStatus,
                completionStatus = settings.completionStatus,
            )
            if (change == null) {
                snackbarHostState.showSnackbar("已经达到总集数")
            } else {
                val result = snackbarHostState.showSnackbar(
                    message = "已记录第 ${change.current} 集",
                    actionLabel = "撤销",
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.restoreProgress(change)
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentAnime.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showMoreSheet = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            AnimeDetailQuickBar(
                onCheckIn = ::checkIn,
                onEditProgress = { showProgressDialog = true },
                onChangeStatus = { showStatusSheet = true },
                onMore = { showMoreSheet = true },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalDetailCardStyle provides settings.detailCardStyle) {
                DetailModulesLayout(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    layout = settings.detailLayout,
                    sections = sections,
                    anime = currentAnime,
                    tags = allTags.filter { it.id in tagIds },
                    characters = characters,
                    series = series,
                    siblings = siblings,
                    animationDuration = animationDuration,
                    imageAlignment = settings.characterImageAlignment,
                    onEditProgress = { showProgressDialog = true },
                    onCheckIn = ::checkIn,
                    onEditReminder = { onEdit(currentAnime.id) },
                      onEditAnime = { onEdit(currentAnime.id) },
                    onManageCharacters = { showCharacterLinker = true },
                    onCoverClick = { showCoverViewer = true },
                    onAnimeOpen = onAnimeOpen,
                    onSeriesOpen = onSeriesOpen,
                    onTagOpen = onTagOpen,
                    onCharacterOpen = onCharacterOpen,
                )
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteDialog = false },
            title = { Text("删除这部番剧？") },
            text = { Text("作品会移入回收站，观看记录和标签关联会保留，可随时恢复。") },
            confirmButton = {
                Button(
                    onClick = {
                        if (isDeleting) return@Button
                        isDeleting = true
                        coroutineScope.launch {
                            try {
                                viewModel.deleteAnime()
                                showDeleteDialog = false
                                onBack()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                isDeleting = false
                                snackbarHostState.showSnackbar(error.message ?: "移入回收站失败")
                            }
                        }
                    },
                    enabled = !isDeleting,
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("移入回收站")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    enabled = !isDeleting,
                ) { Text("取消") }
            },
        )
    }
    if (showProgressDialog) {
        ProgressEditDialog(
            anime = currentAnime,
            onDismiss = { showProgressDialog = false },
            onConfirm = { progress ->
                showProgressDialog = false
                coroutineScope.launch {
                    viewModel.setProgress(progress)
                    snackbarHostState.showSnackbar("观看进度已更新")
                }
            },
        )
    }
    if (showModuleSheet) {
        DetailModuleCustomizationSheet(
            settings = settings,
            onOrderChanged = onDetailModuleOrderChanged,
            onHiddenChanged = onHiddenDetailModulesChanged,
            onCardStyleChanged = onDetailCardStyleSelected,
            onDismissRequest = { showModuleSheet = false },
        )
    }
    if (showStatusSheet) {
        StableModalBottomSheet(onDismissRequest = { showStatusSheet = false }, sheetGesturesEnabled = false) {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("修改观看状态", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "当前：${currentAnime.status}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(statuses, key = { it.id }) { status ->
                        ListItem(
                            headlineContent = { Text(status.name) },
                            supportingContent = if (status.name == currentAnime.status) {
                                { Text("当前状态") }
                            } else {
                                null
                            },
                            leadingContent = {
                                Box(
                                    modifier = Modifier.size(18.dp).clip(CircleShape).background(Color(status.color)),
                                )
                            },
                            trailingContent = if (status.name == currentAnime.status) {
                                { Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxWidth().clickable {
                                showStatusSheet = false
                                coroutineScope.launch {
                                    viewModel.setStatus(status.name)
                                    snackbarHostState.showSnackbar("状态已更新：${status.name}")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    if (showMoreSheet) {
        ModalBottomSheet(onDismissRequest = { showMoreSheet = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    "更多操作",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                ListItem(
                    headlineContent = { Text("编辑完整资料") },
                    supportingContent = { Text("封面、进度、日期、评分、标签、系列与提醒") },
                    leadingContent = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable {
                        showMoreSheet = false
                        onEdit(currentAnime.id)
                    },
                )
                ListItem(
                    headlineContent = { Text("自定义此页") },
                    supportingContent = { Text("调整详情模块顺序和显隐，修改会实时预览") },
                    leadingContent = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable {
                        showMoreSheet = false
                        showModuleSheet = true
                    },
                )
                ListItem(
                    headlineContent = { Text("管理关联角色") },
                    supportingContent = { Text("关联本地角色或从 Bangumi 导入") },
                    leadingContent = { Icon(Icons.Outlined.Groups, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable {
                        showMoreSheet = false
                        showCharacterLinker = true
                    },
                )
                ListItem(
                    headlineContent = { Text("互换简介与评价") },
                    supportingContent = { Text("将「番剧详情」与「我的评价」内容对调") },
                    leadingContent = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable {
                        showMoreSheet = false
                        coroutineScope.launch {
                            viewModel.swapSynopsisAndReview()
                            snackbarHostState.showSnackbar("已互换简介与评价")
                        }
                    },
                )
                ListItem(
                    headlineContent = { Text("移入回收站", color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text("之后仍可恢复作品和关联数据") },
                    leadingContent = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.fillMaxWidth().clickable {
                        showMoreSheet = false
                        showDeleteDialog = true
                    },
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }
    if (showCharacterLinker) {
        AnimeCharacterLinkDialog(
            linked = characters,
            allCharacters = allCharacters,
            onLinkLocal = { characterId, roleName ->
                coroutineScope.launch {
                    viewModel.linkCharacter(characterId, roleName)
                    snackbarHostState.showSnackbar("角色已关联到作品")
                }
            },
            onUnlink = { characterId ->
                coroutineScope.launch {
                    viewModel.unlinkCharacter(characterId)
                    snackbarHostState.showSnackbar("已移除角色关联")
                }
            },
            onDeletePermanently = { characterId ->
                coroutineScope.launch {
                    viewModel.deleteCharacterPermanently(characterId)
                    snackbarHostState.showSnackbar("角色已永久删除")
                }
            },
            onSearchRemote = viewModel::searchRemoteCharacters,
            onImportRemote = { character, roleName ->
                coroutineScope.launch {
                    runCatchingCancellable { viewModel.importAndLinkCharacter(character, roleName) }
                        .onSuccess { snackbarHostState.showSnackbar("已从 Bangumi 导入并关联角色") }
                        .onFailure { snackbarHostState.showSnackbar(it.message ?: "角色导入失败") }
                }
            },
            onOpenCharacterCenter = onManageCharacters,
            onDismiss = { showCharacterLinker = false },
        )
    }
    if (showCoverViewer && !currentAnime.coverUrl.isNullOrBlank()) {
        ZoomableImageViewer(
            imageUrl = currentAnime.coverUrl,
            title = currentAnime.title,
            onDismiss = { showCoverViewer = false },
            onModify = { onEdit(currentAnime.id) },
        )
    }
}

@Composable
private fun AnimeDetailQuickBar(
    onCheckIn: () -> Unit,
    onEditProgress: () -> Unit,
    onChangeStatus: () -> Unit,
    onMore: () -> Unit,
) {
    BottomAppBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        DetailQuickAction(
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.Add,
            label = "+1 集",
            emphasized = true,
            onClick = onCheckIn,
        )
        DetailQuickAction(
            modifier = Modifier.weight(1f),
            icon = Icons.Outlined.Edit,
            label = "精确进度",
            onClick = onEditProgress,
        )
        DetailQuickAction(
            modifier = Modifier.weight(1f),
            icon = Icons.Outlined.CheckCircle,
            label = "状态",
            onClick = onChangeStatus,
        )
        DetailQuickAction(
            modifier = Modifier.weight(1f),
            icon = Icons.Outlined.MoreVert,
            label = "更多",
            onClick = onMore,
        )
    }
}

@Composable
private fun DetailQuickAction(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier.clickable(
            role = Role.Button,
            onClickLabel = label,
            onClick = onClick,
        ).padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Composable
private fun DetailSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    tonalContainerColor: Color? = null,
    tonalElevation: androidx.compose.ui.unit.Dp = 1.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LocalDetailCardStyle.current
    val tonalColor = tonalContainerColor ?: MaterialTheme.colorScheme.surface
    when (style) {
        DetailCardStyle.TONAL -> ElevatedCard(
            modifier = modifier,
            shape = shape,
            colors = CardDefaults.elevatedCardColors(containerColor = tonalColor),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = tonalElevation),
            content = content,
        )
        DetailCardStyle.OUTLINED -> Card(
            modifier = modifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            content = content,
        )
        DetailCardStyle.GLASS -> Card(
            modifier = modifier,
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            content = content,
        )
    }
}

@Composable
private fun DetailModulesLayout(
    modifier: Modifier,
    layout: DetailLayout,
    sections: List<DetailModule>,
    anime: AnimeEntity,
    tags: List<com.animeow.app.data.local.TagEntity>,
    characters: List<CharacterWithRole>,
    series: SeriesEntity?,
    siblings: List<AnimeEntity>,
    animationDuration: Int,
    imageAlignment: CharacterImageAlignment,
    onEditProgress: () -> Unit,
    onCheckIn: () -> Unit,
    onEditReminder: () -> Unit,
    onEditAnime: () -> Unit,
    onManageCharacters: () -> Unit,
    onCoverClick: () -> Unit,
    onAnimeOpen: (Long) -> Unit,
    onSeriesOpen: (Long) -> Unit,
    onTagOpen: (Long) -> Unit,
    onCharacterOpen: (Long) -> Unit,
) {
    val density = LocalContentDensity.current
    val densityScale = density.scale
    if (layout == DetailLayout.DASHBOARD) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive((250 * density.itemScale).dp),
            modifier = modifier,
            contentPadding = PaddingValues(
                start = (16 * densityScale).dp,
                end = (16 * densityScale).dp,
                bottom = 32.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy((12 * densityScale).dp),
            verticalArrangement = Arrangement.spacedBy((12 * densityScale).dp),
        ) {
            gridItems(
                items = sections,
                key = DetailModule::storageKey,
                span = { module ->
                    if (module in DASHBOARD_FULL_WIDTH_MODULES) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                },
            ) { module ->
                DetailModuleContent(
                    module,
                    layout,
                    anime,
                    tags,
                    characters,
                    series,
                    siblings,
                    animationDuration,
                    imageAlignment,
                    onEditProgress,
                    onCheckIn,
                    onEditReminder,
                      onEditAnime,
                    onManageCharacters,
                    onCoverClick,
                    onAnimeOpen,
                    onSeriesOpen,
                    onTagOpen,
                    onCharacterOpen,
                )
            }
        }
        return
    }
    val baseSpacing = when (layout) {
        DetailLayout.MAGAZINE -> 20.dp
        DetailLayout.MINIMAL -> 7.dp
        else -> 14.dp
    }
    val spacing = baseSpacing * densityScale
    val horizontalPadding = (if (layout == DetailLayout.MAGAZINE) 10 else 16) * densityScale
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = horizontalPadding.dp,
            end = horizontalPadding.dp,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        items(sections, key = DetailModule::storageKey) { module ->
            DetailModuleContent(
                module,
                layout,
                anime,
                tags,
                characters,
                series,
                siblings,
                animationDuration,
                imageAlignment,
                onEditProgress,
                onCheckIn,
                onEditReminder,
                  onEditAnime,
                onManageCharacters,
                onCoverClick,
                onAnimeOpen,
                onSeriesOpen,
                onTagOpen,
                  onCharacterOpen,
            )
        }
    }
}

@Composable
private fun DetailModuleContent(
    module: DetailModule,
    layout: DetailLayout,
    anime: AnimeEntity,
    tags: List<com.animeow.app.data.local.TagEntity>,
    characters: List<CharacterWithRole>,
    series: SeriesEntity?,
    siblings: List<AnimeEntity>,
    animationDuration: Int,
    imageAlignment: CharacterImageAlignment,
    onEditProgress: () -> Unit,
    onCheckIn: () -> Unit,
    onEditReminder: () -> Unit,
    onEditAnime: () -> Unit,
    onManageCharacters: () -> Unit,
    onCoverClick: () -> Unit,
    onAnimeOpen: (Long) -> Unit,
    onSeriesOpen: (Long) -> Unit,
    onTagOpen: (Long) -> Unit,
    onCharacterOpen: (Long) -> Unit,
) {
    when (module) {
        DetailModule.HEADER -> AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(animationDuration)) + scaleIn(tween(animationDuration)),
        ) { HeaderCard(anime, layout, onCoverClick) }
        DetailModule.PROGRESS -> ProgressCard(anime, onEditProgress, onCheckIn)
        DetailModule.WATCH_DATES -> DetailSurface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            WatchTimelineContent(anime, onEditAnime, Modifier.padding(18.dp))
        }
        DetailModule.METADATA -> MetadataCard(anime)
        DetailModule.SYNOPSIS -> SynopsisCard(anime.synopsis.orEmpty())
        DetailModule.TAGS -> TagsCard(tags, onTagOpen)
        DetailModule.REMINDER -> ReminderCard(anime, onEditReminder)
        DetailModule.SERIES -> series?.let {
            SeriesSiblingsCard(it, siblings, onSeriesOpen, onAnimeOpen)
        }
        DetailModule.CHARACTERS -> CharactersCard(characters, imageAlignment, onManageCharacters, onCharacterOpen)
        DetailModule.REVIEW -> AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(animationDuration)),
            exit = fadeOut(tween(animationDuration)),
        ) { ReviewCard(anime.review.orEmpty(), onEditAnime) }
    }
}

private val DASHBOARD_FULL_WIDTH_MODULES = setOf(
    DetailModule.HEADER,
    DetailModule.WATCH_DATES,
    DetailModule.SYNOPSIS,
    DetailModule.SERIES,
    DetailModule.CHARACTERS,
    DetailModule.REVIEW,
)

@Composable
private fun AnimeCharacterLinkDialog(
    linked: List<CharacterWithRole>,
    allCharacters: List<CharacterListItem>,
    onLinkLocal: (Long, String?) -> Unit,
    onUnlink: (Long) -> Unit,
    onDeletePermanently: (Long) -> Unit,
    onSearchRemote: suspend (String) -> List<RemoteCharacter>,
    onImportRemote: (RemoteCharacter, String?) -> Unit,
    onOpenCharacterCenter: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var roleName by remember { mutableStateOf("") }
    var useRemote by remember { mutableStateOf(false) }
    var remoteResults by remember { mutableStateOf(emptyList<RemoteCharacter>()) }
    var remoteLoading by remember { mutableStateOf(false) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var pendingRemoval by remember { mutableStateOf<CharacterWithRole?>(null) }
    var confirmPermanentRemoval by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val linkedIds = linked.mapTo(mutableSetOf()) { it.character.id }
    val localResults = allCharacters.filter { item ->
        item.character.id !in linkedIds && (
            query.isBlank() || item.character.name.contains(query, true) ||
                item.character.nameCn.orEmpty().contains(query, true)
            )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理作品角色") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (linked.isNotEmpty()) {
                    item { Text("已关联", style = MaterialTheme.typography.titleSmall) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(linked, key = { "linked_${it.character.id}" }) { item ->
                                FilterChip(
                                    selected = true,
                                    onClick = {
                                        pendingRemoval = item
                                        confirmPermanentRemoval = false
                                    },
                                    label = {
                                        Text(item.character.nameCn?.takeIf(String::isNotBlank) ?: item.character.name)
                                    },
                                    trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "移除", Modifier.size(16.dp)) },
                                )
                            }
                        }
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(selected = !useRemote, onClick = { useRemote = false }, label = { Text("本地资料库") })
                        }
                        item {
                            FilterChip(selected = useRemote, onClick = { useRemote = true }, label = { Text("Bangumi") })
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(if (useRemote) "搜索 Bangumi 角色" else "搜索本地角色") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = if (useRemote) {
                            {
                                IconButton(
                                    enabled = query.isNotBlank() && !remoteLoading,
                                    onClick = {
                                        remoteLoading = true
                                        remoteError = null
                                        scope.launch {
                                            runCatchingCancellable { onSearchRemote(query) }
                                                .onSuccess { remoteResults = it }
                                                .onFailure { remoteError = it.message ?: "搜索失败" }
                                            remoteLoading = false
                                        }
                                    },
                                ) { Icon(Icons.Outlined.Search, contentDescription = "搜索") }
                            }
                        } else null,
                    )
                }
                item {
                    OutlinedTextField(
                        value = roleName,
                        onValueChange = { roleName = it },
                        label = { Text("角色定位（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                when {
                    remoteLoading -> item {
                        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    remoteError != null -> item {
                        Text(remoteError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    }
                    useRemote -> items(remoteResults, key = { "remote_${it.id}" }) { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.displayName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { onImportRemote(item, roleName) }) {
                                Icon(Icons.Rounded.Add, contentDescription = "导入并关联")
                            }
                        }
                    }
                    else -> items(localResults, key = { "local_${it.character.id}" }) { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                item.character.nameCn?.takeIf(String::isNotBlank) ?: item.character.name,
                                Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { onLinkLocal(item.character.id, roleName) }) {
                                Icon(Icons.Rounded.Add, contentDescription = "关联")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = onOpenCharacterCenter) { Text("打开角色中心") } },
    )
    pendingRemoval?.let { target ->
        val displayName = target.character.nameCn?.takeIf(String::isNotBlank) ?: target.character.name
        if (confirmPermanentRemoval) {
            AlertDialog(
                onDismissRequest = {
                    confirmPermanentRemoval = false
                    pendingRemoval = null
                },
                title = { Text("永久删除角色？") },
                text = {
                    Text(
                        "「$displayName」会从所有作品、角色关系、标签和角色组中删除。" +
                            "此操作无法撤销。",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeletePermanently(target.character.id)
                            confirmPermanentRemoval = false
                            pendingRemoval = null
                        },
                    ) {
                        Text("永久删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        confirmPermanentRemoval = false
                        pendingRemoval = null
                    }) { Text("取消") }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { pendingRemoval = null },
                title = { Text("处理「displayName」？") },
                text = { Text("可以只解除它与当前作品的关联，也可以从资料库中永久删除这个角色。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onUnlink(target.character.id)
                            pendingRemoval = null
                        },
                    ) { Text("仅从本作移除") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { pendingRemoval = null }) { Text("取消") }
                        TextButton(onClick = { confirmPermanentRemoval = true }) {
                            Text("永久删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun CharactersCard(
    characters: List<CharacterWithRole>,
    imageAlignment: CharacterImageAlignment,
    onManageCharacters: () -> Unit,
    onCharacterOpen: (Long) -> Unit,
) {
    val alignment = when (imageAlignment) {
        CharacterImageAlignment.TOP -> Alignment.TopCenter
        CharacterImageAlignment.CENTER -> Alignment.Center
        CharacterImageAlignment.BOTTOM -> Alignment.BottomCenter
    }
    DetailSurface(
        modifier = Modifier.fillMaxWidth().motionAnimateContentSize(),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "角色",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onManageCharacters) { Text(if (characters.isEmpty()) "添加" else "管理") }
            }
            if (characters.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("还没有关联角色", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(characters, key = { it.character.id }) { item ->
                        ElevatedCard(
                            modifier = Modifier.width(126.dp).animatedPressClick(
                                onClickLabel = "查看${item.character.nameCn?.takeIf(String::isNotBlank) ?: item.character.name}详情",
                            ) { onCharacterOpen(item.character.id) },
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Column {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(148.dp)
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                ) {
                                    if (!item.character.imageUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = item.character.imageUrl,
                                            contentDescription = item.character.nameCn ?: item.character.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                            alignment = alignment,
                                        )
                                    }
                                }
                                Column(Modifier.padding(9.dp)) {
                                    Text(
                                        item.character.nameCn?.takeIf(String::isNotBlank) ?: item.character.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    item.roleName?.let {
                                        Text(
                                            it,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCard(anime: AnimeEntity, layout: DetailLayout, onCoverClick: () -> Unit) {
    val density = LocalContentDensity.current
    val densityScale = density.scale
    if (layout == DetailLayout.MAGAZINE) {
        DetailSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalContainerColor = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Column {
                Box(
                    Modifier.fillMaxWidth().height((270 * density.itemScale).dp).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    if (!anime.coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            anime.coverUrl,
                            anime.title,
                            Modifier.fillMaxSize().clickable(
                                role = Role.Button,
                                onClickLabel = "查看封面大图",
                                onClick = onCoverClick,
                            ),
                            contentScale = ContentScale.Crop,
                        )
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.24f)))
                    }
                    Column(
                        Modifier.padding((22 * densityScale).dp),
                        verticalArrangement = Arrangement.spacedBy((6 * densityScale).dp),
                    ) {
                        Text(
                            anime.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        anime.originalTitle?.takeIf(String::isNotBlank)?.let {
                            Text(it, color = Color.White.copy(alpha = 0.82f), maxLines = 2)
                        }
                        Text(anime.status, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        return
    }
    val minimal = layout == DetailLayout.MINIMAL
    val usePrimaryContainer = !minimal && LocalDetailCardStyle.current == DetailCardStyle.TONAL
    DetailSurface(
        modifier = Modifier.fillMaxWidth(),
        tonalContainerColor = if (minimal) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer,
        shape = if (minimal) MaterialTheme.shapes.medium else MaterialTheme.shapes.extraLarge,
        tonalElevation = if (minimal) 0.dp else 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(((if (minimal) 12 else 18) * densityScale).dp),
            horizontalArrangement = Arrangement.spacedBy(((if (minimal) 12 else 16) * densityScale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(
                        width = ((if (minimal) 76 else 104) * density.itemScale).dp,
                        height = ((if (minimal) 108 else 148) * density.itemScale).dp,
                    )
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(
                        enabled = !anime.coverUrl.isNullOrBlank(),
                        role = Role.Button,
                        onClickLabel = "查看封面大图",
                        onClick = onCoverClick,
                    ),
            ) {
                if (!anime.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = anime.coverUrl,
                        contentDescription = anime.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = anime.title.take(2),
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy((8 * densityScale).dp),
            ) {
                Text(
                    text = anime.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (usePrimaryContainer) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = anime.status,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                anime.originalTitle?.takeIf(String::isNotBlank)?.let { originalTitle ->
                    Text(
                        text = originalTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (usePrimaryContainer) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                anime.studio?.takeIf(String::isNotBlank)?.let { studio ->
                    Text(
                        text = studio,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (usePrimaryContainer) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(
    anime: AnimeEntity,
    onEditProgress: () -> Unit,
    onCheckIn: () -> Unit,
) {
    val densityScale = LocalContentDensity.current.scale
    val hasTotal = anime.totalEpisodes > 0
    val targetProgress = if (hasTotal) {
        (anime.watchedEpisodes.toFloat() / anime.totalEpisodes).coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = motionAnimationSpec(420),
        label = "detail_progress",
    )
    DetailSurface(
        modifier = Modifier
            .fillMaxWidth()
            .motionAnimateContentSize(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding((18 * densityScale).dp),
            verticalArrangement = Arrangement.spacedBy((12 * densityScale).dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("观看进度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (hasTotal) "${anime.watchedEpisodes} / ${anime.totalEpisodes} 集" else "已看 ${anime.watchedEpisodes} 集",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (hasTotal) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy((10 * densityScale).dp)) {
                Button(
                    onClick = onCheckIn,
                    enabled = !hasTotal || anime.watchedEpisodes < anime.totalEpisodes,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("+1 集")
                }
                OutlinedButton(onClick = onEditProgress) { Text("精确修改") }
            }
        }
    }
}

@Composable
private fun ProgressEditDialog(
    anime: AnimeEntity,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var value by remember(anime.id, anime.watchedEpisodes) {
        mutableStateOf(anime.watchedEpisodes.toString())
    }
    val parsed = value.toIntOrNull()
    val isValid = parsed != null && parsed >= 0 &&
        (anime.totalEpisodes <= 0 || parsed <= anime.totalEpisodes)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("精确修改观看进度") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit) },
                    label = { Text("已看集数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                if (anime.totalEpisodes > 0) {
                    Text(
                        "总集数：${anime.totalEpisodes}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(parsed ?: 0) },
                enabled = isValid,
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailModuleCustomizationSheet(
    settings: AppearanceSettings,
    onOrderChanged: (List<DetailModule>) -> Unit,
    onHiddenChanged: (Set<DetailModule>) -> Unit,
    onCardStyleChanged: (DetailCardStyle) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val order = remember {
        mutableStateListOf<DetailModule>().apply { addAll(settings.detailModuleOrder) }
    }
    LaunchedEffect(settings.detailModuleOrder) {
        if (order.toList() != settings.detailModuleOrder) {
            order.clear()
            order.addAll(settings.detailModuleOrder)
        }
    }

    StableModalBottomSheet(onDismissRequest = onDismissRequest, sheetGesturesEnabled = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("详情页模块", style = MaterialTheme.typography.headlineSmall)
            Text(
                "长按拖动调整顺序；隐藏的模块只影响展示，不会删除数据。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("卡片材质", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DetailCardStyle.entries) { style ->
                    FilterChip(
                        selected = settings.detailCardStyle == style,
                        onClick = { onCardStyleChanged(style) },
                        label = { Text(style.displayName) },
                    )
                }
            }
            Text(
                settings.detailCardStyle.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            order.forEachIndexed { index, module ->
                var dragOffset by remember(module) { mutableFloatStateOf(0f) }
                val visible = module !in settings.hiddenDetailModules
                val canHide = !visible || settings.hiddenDetailModules.size < DetailModule.entries.size - 1
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer(
                            translationY = dragOffset,
                            scaleX = if (dragOffset == 0f) 1f else 1.015f,
                            scaleY = if (dragOffset == 0f) 1f else 1.015f,
                        )
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .pointerInput(module, order.toList()) {
                            val threshold = 44.dp.toPx()
                            detectDragGesturesAfterLongPress(
                                onDragEnd = { dragOffset = 0f },
                                onDragCancel = { dragOffset = 0f },
                            ) { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount.y
                                val currentIndex = order.indexOf(module)
                                when {
                                    dragOffset > threshold && currentIndex < order.lastIndex -> {
                                        order.removeAt(currentIndex)
                                        order.add(currentIndex + 1, module)
                                        dragOffset = 0f
                                        onOrderChanged(order.toList())
                                    }
                                    dragOffset < -threshold && currentIndex > 0 -> {
                                        order.removeAt(currentIndex)
                                        order.add(currentIndex - 1, module)
                                        dragOffset = 0f
                                        onOrderChanged(order.toList())
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.DragHandle, contentDescription = "拖动排序")
                    Column(modifier = Modifier.weight(1f)) {
                        Text(module.displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            "位置 ${index + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = visible,
                        enabled = canHide,
                        onCheckedChange = { show ->
                            onHiddenChanged(
                                if (show) settings.hiddenDetailModules - module
                                else settings.hiddenDetailModules + module,
                            )
                        },
                    )
                }
            }
            TextButton(
                onClick = {
                    val defaults = DetailModule.entries
                    order.clear()
                    order.addAll(defaults)
                    onOrderChanged(defaults)
                    onHiddenChanged(emptySet())
                    onCardStyleChanged(DetailCardStyle.TONAL)
                },
                modifier = Modifier.align(Alignment.End),
            ) { Text("恢复默认") }
        }
    }
}

@Composable
private fun ReminderCard(
    anime: AnimeEntity,
    onEdit: () -> Unit,
) {
    val densityScale = LocalContentDensity.current.scale
    DetailSurface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.fillMaxWidth().padding((18 * densityScale).dp),
            verticalArrangement = Arrangement.spacedBy((10 * densityScale).dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("追番提醒", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onEdit) { Text(if (anime.reminderDay == null || anime.reminderTime.isNullOrBlank()) "设置" else "修改") }
            }
            if (anime.reminderDay == null || anime.reminderTime.isNullOrBlank()) {
                Text("尚未设置每周提醒", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "${detailWeekdayLabel(anime.reminderDay)} · ${anime.reminderTime}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text("到点后通过系统通知提醒，精确闹钟不可用时会自动降级。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SeriesSiblingsCard(
    series: SeriesEntity,
    siblings: List<AnimeEntity>,
    onSeriesOpen: (Long) -> Unit,
    onAnimeOpen: (Long) -> Unit,
) {
    val density = LocalContentDensity.current
    DetailSurface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.fillMaxWidth().padding((18 * density.scale).dp),
            verticalArrangement = Arrangement.spacedBy((10 * density.scale).dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("同系列作品", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(series.name, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = { onSeriesOpen(series.id) }) { Text("系列详情") }
            }
            series.description?.takeIf(String::isNotBlank)?.let {
                Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (siblings.isEmpty()) {
                Text("当前系列暂时只有这一部作品", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy((10 * density.scale).dp)) {
                    items(siblings, key = AnimeEntity::id) { sibling ->
                        ElevatedCard(
                            modifier = Modifier.width((116 * density.itemScale).dp).animatedPressClick(
                                onClickLabel = "查看${sibling.title}详情",
                            ) { onAnimeOpen(sibling.id) },
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Column {
                                Box(
                                    Modifier.fillMaxWidth().height((150 * density.itemScale).dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                ) {
                                    if (!sibling.coverUrl.isNullOrBlank()) {
                                        AsyncImage(sibling.coverUrl, sibling.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    }
                                }
                                Text(
                                    sibling.title,
                                    modifier = Modifier.padding((9 * density.scale).dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun detailWeekdayLabel(day: Int?): String = when (day) {
    1 -> "每周一"
    2 -> "每周二"
    3 -> "每周三"
    4 -> "每周四"
    5 -> "每周五"
    6 -> "每周六"
    0, 7 -> "每周日"
    else -> "每周"
}

@Composable
private fun MetadataCard(anime: AnimeEntity) {
    val densityScale = LocalContentDensity.current.scale
    val uriHandler = LocalUriHandler.current
    val externalUrl = remember(anime.externalUrl, anime.externalSource, anime.externalId, anime.subjectType) {
        resolvedExternalUrl(anime)
    }
    val sourceName = externalSourceName(anime.externalSource)
    DetailSurface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding((18 * densityScale).dp),
            verticalArrangement = Arrangement.spacedBy((9 * densityScale).dp),
        ) {
            Text("作品信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            MetadataRow("类型", subjectTypeDisplayName(anime.subjectType))
            anime.mediaFormat?.takeIf(String::isNotBlank)?.let { MetadataRow("载体", it) }
            anime.airDate?.takeIf(String::isNotBlank)?.let { MetadataRow("放送日期", it) }
            anime.studio?.takeIf(String::isNotBlank)?.let { MetadataRow("制作公司", it) }
            normalizeAnimeRatingGrade(anime.ratingGrade)?.let { MetadataRow("评分", "$it 分") }
                ?: formatAnimeRating(anime.rating).takeIf(String::isNotBlank)?.let { MetadataRow("评分", "$it / 10") }
            sourceName?.let { MetadataRow("资料来源", it) }
            externalUrl?.let { url ->
                OutlinedButton(
                    onClick = { runCatching { uriHandler.openUri(url) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text(sourceName?.let { "在$it 查看" } ?: "打开作品资料")
                }
            }
        }
    }
}

private fun resolvedExternalUrl(anime: AnimeEntity): String? {
    anime.externalUrl?.trim()?.takeIf {
        it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
    }?.let { return it }
    val id = anime.externalId?.trim()?.takeIf { value -> value.isNotEmpty() && value.all(Char::isDigit) }
        ?: return null
    return when (anime.externalSource?.trim()?.lowercase(java.util.Locale.ROOT)) {
        "bangumi", "bgm" -> "https://bgm.tv/subject/$id"
        "anilist" -> if (normalizeSubjectType(anime.subjectType) == "anime") {
            "https://anilist.co/anime/$id"
        } else {
            "https://anilist.co/manga/$id"
        }
        else -> null
    }
}

private fun externalSourceName(source: String?): String? = when (source?.trim()?.lowercase(java.util.Locale.ROOT)) {
    "bangumi", "bgm" -> "Bangumi"
    "anilist" -> "AniList"
    "server" -> "资料库"
    null, "" -> null
    else -> source.trim()
}

@Composable
private fun SynopsisCard(synopsis: String) {
    val densityScale = LocalContentDensity.current.scale
    DetailSurface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding((18 * densityScale).dp),
            verticalArrangement = Arrangement.spacedBy((8 * densityScale).dp),
        ) {
            Text("番剧详情", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(synopsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TagsCard(
    tags: List<com.animeow.app.data.local.TagEntity>,
    onTagOpen: (Long) -> Unit,
) {
    val densityScale = LocalContentDensity.current.scale
    DetailSurface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding((18 * densityScale).dp),
            verticalArrangement = Arrangement.spacedBy((10 * densityScale).dp),
        ) {
            Text("标签", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy((8 * densityScale).dp),
            ) {
                items(tags, key = { it.id }) { tag ->
                    androidx.compose.material3.AssistChip(
                        onClick = { onTagOpen(tag.id) },
                        label = { Text(tag.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(review: String, onEdit: () -> Unit) {
    val densityScale = LocalContentDensity.current.scale
    DetailSurface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding((18 * densityScale).dp),
            verticalArrangement = Arrangement.spacedBy((8 * densityScale).dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("我的评价", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onEdit) {
                    Text(if (review.isBlank()) "写下评价" else "编辑")
                }
            }
            if (review.isBlank()) {
                Text(
                    "还没有写下你的评价，点击「编辑」记录对这部作品的观后感想。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(review, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
