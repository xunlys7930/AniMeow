package com.animeow.app.ui.anime

import com.animeow.app.data.remote.publicRating
import com.animeow.app.data.remote.bangumiSubjectId

import androidx.compose.foundation.layout.aspectRatio

import com.animeow.app.ui.icons.AppIcons

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
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
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import com.animeow.app.ui.components.StableModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.EpisodeDetailEntity
import com.animeow.app.data.local.formatAnimeRating
import com.animeow.app.data.local.normalizeAnimeRatingGrade
import com.animeow.app.data.local.isBookSubjectType
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.local.subjectTypeDisplayName
import com.animeow.app.data.local.CharacterWithRole
import com.animeow.app.data.local.CharacterListItem
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.remote.RemoteCharacter
import com.animeow.app.ui.character.WorkCastDialog
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.components.ZoomableImageViewer
import com.animeow.app.util.runCatchingCancellable
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionAnimationSpec
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut
import com.animeow.app.ui.components.scaledMotionDurationMillis
import com.animeow.app.ui.theme.LocalMotionLevel
import com.animeow.app.ui.theme.MotionLevel
import com.animeow.app.ui.theme.LocalDetailLayout
import com.animeow.app.ui.theme.LocalDetailProgressMode
import com.animeow.app.ui.theme.DetailProgressMode
import com.animeow.app.ui.theme.LocalContentDensity
import com.animeow.app.ui.theme.DetailLayout
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.CharacterImageAlignment
import com.animeow.app.ui.theme.DetailCardStyle
import com.animeow.app.ui.theme.DetailModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal val LocalDetailCardStyle = staticCompositionLocalOf { DetailCardStyle.TONAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onEditEpisodes: (Long) -> Unit,
    settings: AppearanceSettings,
    onDetailModuleOrderChanged: (List<DetailModule>) -> Unit,
    onHiddenDetailModulesChanged: (Set<DetailModule>) -> Unit,
    onDetailCardStyleSelected: (DetailCardStyle) -> Unit,
    onManageCharacters: () -> Unit,
    onAnimeOpen: (Long) -> Unit,
    onSeriesOpen: (Long) -> Unit,
    onTagOpen: (Long) -> Unit,
    onCharacterOpen: (Long) -> Unit,
    onOpenLegadoSync: (() -> Unit)? = null,
    viewModel: AnimeDetailViewModel = viewModel(),
) {
    val detailState by viewModel.detailState.collectAsStateWithLifecycle()
    val anime = (detailState as? AnimeDetailState.Loaded)?.anime
    if (anime == null) {
        AnimeDetailPlaceholder(detailState, onBack, viewModel::retryLoading)
        return
    }
    val episodeDetails by viewModel.episodeDetails.collectAsStateWithLifecycle()
    val numberingState by viewModel.episodeNumbering.collectAsStateWithLifecycle()
    val numbering = numberingState?.takeIf {
        it.first == anime?.let { current -> com.animeow.app.data.bangumi.bangumiSubjectIdOf(current) } && it.second.totalEpisodes == anime?.totalEpisodes
    }?.second
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val tagIds by viewModel.tagIds.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val allCharacters by viewModel.allCharacters.collectAsStateWithLifecycle()
    val workCharacters by viewModel.workCharacters.collectAsStateWithLifecycle()
    val series by viewModel.series.collectAsStateWithLifecycle()
    val siblings by viewModel.siblings.collectAsStateWithLifecycle()
    val linkedCrossMedia by viewModel.linkedCrossMedia.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var showProgressDialog by remember { mutableStateOf(false) }
    var showModuleSheet by remember { mutableStateOf(false) }
    var showStatusSheet by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showCharacterLinker by remember { mutableStateOf(false) }
    var showCastManager by remember { mutableStateOf(false) }
    var showReminderSheet by remember { mutableStateOf(false) }
    var showCoverViewer by remember { mutableStateOf(false) }

    val currentAnime = anime
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
                    message = numbering?.numberForWatchedCount(change.current)?.let { "已记录第 $it 集（本季第 ${change.current} 集）" }
                        ?: "已记录第 ${change.current} 集",
                    actionLabel = "撤销",
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.restoreProgress(change)
            }
        }
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val isGlassDetail = settings.detailCardStyle == DetailCardStyle.GLASS && settings.detailLayout != DetailLayout.MINIMAL
    val showTopBarHeader = DetailModule.HEADER !in sections || scrollBehavior.state.overlappedFraction > 0.65f
    DetailGlassScene(enabled = isGlassDetail, cover = currentAnime.coverUrl, modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = if (isGlassDetail) Color.Transparent else MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        AnimatedVisibility(
                            visible = showTopBarHeader,
                            enter = motionFadeIn(180),
                            exit = motionFadeOut(140),
                        ) {
                            Text(
                                text = currentAnime.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(AppIcons.Back, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (normalizeSubjectType(currentAnime.subjectType) == "anime") {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            AnimatedVisibility(
                                visible = showTopBarHeader,
                                enter = motionFadeIn(180),
                                exit = motionFadeOut(140),
                            ) {
                                IconButton(
                                    onClick = { context.startActivity(com.animeow.app.ui.playback.PlaybackActivity.intent(context, currentAnime.id)) },
                                ) {
                                    Icon(AppIcons.Play, contentDescription = "观看")
                                }
                            }
                            IconButton(
                                onClick = { context.startActivity(com.animeow.app.ui.playback.WatchDiscussionActivity.library(context, currentAnime.id)) },
                            ) {
                                Icon(AppIcons.Chat, contentDescription = "作品讨论")
                            }
                        }
                        com.animeow.app.ui.overlay.ProgressOverlayIconButton(currentAnime.id)
                        IconButton(onClick = { showMoreSheet = true }) {
                            Icon(AppIcons.MoreVertical, contentDescription = "更多操作")
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (isGlassDetail) Color.Transparent else MaterialTheme.colorScheme.background,
                        scrolledContainerColor = if (isGlassDetail) MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
                            else MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            },
            bottomBar = {
                CompositionLocalProvider(LocalEpisodeNumbering provides numbering) {
                    AnimeDetailQuickBar(
                        anime = currentAnime,
                        onCheckIn = ::checkIn,
                        onEditProgress = { showProgressDialog = true },
                        onChangeStatus = { showStatusSheet = true },
                        reminderSet = currentAnime.reminderDay != null && !currentAnime.reminderTime.isNullOrBlank(),
                        onReminder = { showReminderSheet = true },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalDetailCardStyle provides settings.detailCardStyle, LocalEpisodeNumbering provides numbering) {
                    DetailModulesLayout(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        layout = settings.detailLayout,
                        sections = sections,
                        anime = currentAnime,
                        tags = allTags.filter { it.id in tagIds },
                        characters = characters,
                        series = series,
                        siblings = siblings,
                        imageAlignment = settings.characterImageAlignment,
                        onEditProgress = { showProgressDialog = true },
                        onCheckIn = ::checkIn,
                        onEditReminder = { showReminderSheet = true },
                        onEditAnime = { onEdit(currentAnime.id) },
                        onManageCharacters = { showCastManager = true },
                        onCoverClick = { showCoverViewer = true },
                        onAnimeOpen = onAnimeOpen,
                        onSeriesOpen = onSeriesOpen,
                        onTagOpen = onTagOpen,
                        onCharacterOpen = onCharacterOpen,
                        episodeDetails = episodeDetails,
                        onOpenEpisodes = { onEditEpisodes(currentAnime.id) },
                        onSaveMemo = viewModel::updateMemo,
                        linkedCrossMedia = linkedCrossMedia,
                        onOpenLegadoSync = onOpenLegadoSync,
                    )
                }
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = padding.calculateBottomPadding())
                        .padding(16.dp),
                )
            }
        }
    }

    if (showReminderSheet) {
        com.animeow.app.ui.reminder.AnimeReminderSheet(currentAnime, onDismiss = { showReminderSheet = false }, onSave = { day, time, count, untilDate ->
            viewModel.saveReminder(day, time, count, untilDate)
            coroutineScope.launch { snackbarHostState.showSnackbar(if (day == null) "已关闭这部作品的提醒" else "追番提醒已保存") }
        })
    }
    if (showDeleteDialog) {
        val deletingBook = currentAnime.subjectType.isBookSubjectType()
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteDialog = false },
            title = { Text(if (deletingBook) "删除这本书籍？" else "删除这部番剧？") },
            text = { Text(if (deletingBook) "作品会移入回收站，阅读记录和标签关联会保留，可随时恢复。" else "作品会移入回收站，观看记录和标签关联会保留，可随时恢复。") },
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
    val launchOverlayFromDialog = com.animeow.app.ui.overlay.rememberProgressOverlayLauncher(currentAnime.id) {
        showProgressDialog = false
    }
    if (showProgressDialog) {
        ProgressEditDialog(
            anime = currentAnime,
            numbering = numbering,
            onDismiss = { showProgressDialog = false },
            onLaunchOverlay = launchOverlayFromDialog,
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
                if (statuses.isEmpty()) TextButton(onClick = { showStatusSheet = false; onEdit(currentAnime.id) }) { Text("状态菜单已隐藏，前往编辑页") }
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
                                { Icon(AppIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
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
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    "更多操作",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                com.animeow.app.ui.overlay.ProgressOverlayEntry(currentAnime.id) { showMoreSheet = false }
                if (normalizeSubjectType(currentAnime.subjectType) == "anime") {
                    val watchContext = androidx.compose.ui.platform.LocalContext.current
                    ListItem(
                        headlineContent = { Text("作品与单集讨论") },
                        supportingContent = { Text("查看评论、弹幕与社区相关讨论") },
                        leadingContent = { Icon(AppIcons.Chat, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().clickable {
                            showMoreSheet = false
                            watchContext.startActivity(com.animeow.app.ui.playback.WatchDiscussionActivity.library(watchContext, currentAnime.id))
                        },
                    )
                }
                ListItem(
                    headlineContent = { Text("修改进度") },
                    supportingContent = { Text(detailProgressLabel(currentAnime.watchedEpisodes, currentAnime.totalEpisodes, numbering)) },
                    leadingContent = { Icon(AppIcons.CheckCircle, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable {
                        showMoreSheet = false
                        showProgressDialog = true
                    },
                )
                ListItem(
                    headlineContent = { Text("编辑完整资料") },
                    supportingContent = { Text("封面、进度、日期、评分、标签、系列与提醒") },
                    leadingContent = { Icon(AppIcons.Edit, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable {
                        showMoreSheet = false
                        onEdit(currentAnime.id)
                    },
                )
                ListItem(
                    headlineContent = { Text("分集信息") },
                    supportingContent = { Text("逐集记录标题、时长、简介、评价与评分") },
                    leadingContent = { Icon(AppIcons.List, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable {
                        showMoreSheet = false
                        onEditEpisodes(currentAnime.id)
                    },
                )
                ListItem(
                    headlineContent = { Text("自定义此页") },
                    supportingContent = { Text("调整详情模块顺序和显隐，修改会实时预览") },
                    leadingContent = { Icon(AppIcons.Tune, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable {
                        showMoreSheet = false
                        showModuleSheet = true
                    },
                )
                ListItem(
                    headlineContent = { Text("角色与声优") },
                    supportingContent = { Text("逐个绑定角色配音，或添加本作角色") },
                    leadingContent = { Icon(AppIcons.Group, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable {
                        showMoreSheet = false
                        showCastManager = true
                    },
                )
                ListItem(
                    headlineContent = { Text("互换简介与评价") },
                    supportingContent = { Text("将「作品简介」与「我的评价」内容对调") },
                    leadingContent = { Icon(AppIcons.SwapHorizontal, contentDescription = null) },
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
                    leadingContent = { Icon(AppIcons.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.fillMaxWidth().clickable {
                        showMoreSheet = false
                        showDeleteDialog = true
                    },
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }
    if (showCastManager) {
        WorkCastDialog(currentAnime.title, characters, currentAnime.voiceActors,
            onSaveVoiceActor = { characterId, value -> viewModel.setCharacterVoiceActor(characterId, value) },
            onAddCharacter = { showCastManager = false; showCharacterLinker = true },
            onCharacterOpen = onCharacterOpen, onDismiss = { showCastManager = false })
    }
    if (showCharacterLinker) {
        LaunchedEffect(currentAnime.id, currentAnime.externalSource, currentAnime.externalId) { viewModel.loadWorkCharacters() }
        AnimeCharacterLinkDialog(
            workTitle = currentAnime.title,
            workState = workCharacters,
            linked = characters,
            allCharacters = allCharacters,
            onSaveVoiceActor = { characterId, value -> viewModel.setCharacterVoiceActor(characterId, value) },
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
            onSelectWork = { viewModel.loadWorkCharacters(selected = it) },
            onSearchWork = { viewModel.loadWorkCharacters(workQuery = it) },
            onReloadWork = { viewModel.loadWorkCharacters(force = true) },
            onImportRemote = { character, roleName ->
                viewModel.importAndLinkCharacter(character, roleName)
                coroutineScope.launch { snackbarHostState.showSnackbar("已从本作角色名单导入并关联") }
            },
            onOpenCharacterCenter = { showCastManager = false; onManageCharacters() },
            onDismiss = { showCharacterLinker = false; showCastManager = true },
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
internal fun AnimeDetailQuickBar(
    anime: AnimeEntity,
    onCheckIn: () -> Unit,
    onEditProgress: () -> Unit,
    onChangeStatus: () -> Unit,
    reminderSet: Boolean,
    onReminder: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
      Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp).heightIn(min = 64.dp),
          verticalAlignment = Alignment.CenterVertically) {
        if (LocalDetailProgressMode.current == DetailProgressMode.COMBINED) {
            CombinedProgressButton(anime, onCheckIn, onEditProgress, Modifier.weight(2f), compact = true)
        } else {
        DetailQuickAction(
            modifier = Modifier.weight(1f),
            icon = AppIcons.Add,
            label = "+1 集",
            emphasized = true,
            onClick = onCheckIn,
        )
        DetailQuickAction(
            modifier = Modifier.weight(1f),
            icon = AppIcons.Edit,
            label = "修改进度",
            onClick = onEditProgress,
        )
        }
        DetailQuickAction(
            modifier = Modifier.weight(1f),
            icon = AppIcons.CheckCircle,
            label = "状态",
            onClick = onChangeStatus,
        )
        DetailQuickAction(
            modifier = Modifier.weight(1f),
            icon = if (reminderSet) AppIcons.Notification else AppIcons.Notification,
            label = if (reminderSet) "已设提醒" else "追番提醒",
            emphasized = reminderSet,
            onClick = onReminder,
        )
      }
    }
}

@Composable
private fun DetailQuickAction(
    modifier: Modifier,
    icon: com.animeow.app.ui.icons.IconGlyph,
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
        ).heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun DetailSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    tonalContainerColor: Color? = null,
    tonalElevation: androidx.compose.ui.unit.Dp = 1.dp,
    style: DetailCardStyle = LocalDetailCardStyle.current,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalDetailLayout.current == DetailLayout.MINIMAL) {
        Column(modifier) {
            content()
            androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 18.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }
        return
    }
    val tonalColor = tonalContainerColor ?: MaterialTheme.colorScheme.surfaceContainerLow
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
        DetailCardStyle.GLASS -> DetailGlassSurface(modifier, content)
    }
}

@Composable
internal fun DetailModulesLayout(
    modifier: Modifier,
    layout: DetailLayout,
    sections: List<DetailModule>,
    anime: AnimeEntity,
    tags: List<com.animeow.app.data.local.TagEntity>,
    characters: List<CharacterWithRole>,
    series: SeriesEntity?,
    siblings: List<AnimeEntity>,
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
    episodeDetails: List<EpisodeDetailEntity> = emptyList(),
    onOpenEpisodes: (() -> Unit)? = null,
    onSaveMemo: (String?) -> Unit = {},
    linkedCrossMedia: List<com.animeow.app.data.local.SeriesLinkedCrossMediaEntry> = emptyList(),
    onOpenLegadoSync: (() -> Unit)? = null,
) {
    val density = LocalContentDensity.current
    val densityScale = density.scale
    if (layout == DetailLayout.DASHBOARD) {
      BoxWithConstraints(modifier) {
        val columns = if (maxWidth >= 340.dp && LocalDensity.current.fontScale <= 1.2f) 2 else 1
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize().testTag("detail-modules"),
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
              Box(Modifier.testTag("detail-module-${module.storageKey}")) {
                DetailModuleContent(
                    module,
                    layout,
                    anime,
                    tags,
                    characters,
                    series,
                    siblings,
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
                    episodeDetails,
                    onOpenEpisodes,
                    onSaveMemo,
                    linkedCrossMedia,
                    onOpenLegadoSync,
                )
              }
            }
        }
      }
        return
    }
    val baseSpacing = when (layout) {
        DetailLayout.MAGAZINE -> 20.dp
        DetailLayout.MINIMAL -> 2.dp
        else -> 14.dp
    }
    val spacing = baseSpacing * densityScale
    val horizontalPadding = (if (layout == DetailLayout.MAGAZINE) 10 else 16) * densityScale
    LazyColumn(
        modifier = modifier.testTag("detail-modules"),
        contentPadding = PaddingValues(
            start = horizontalPadding.dp,
            end = horizontalPadding.dp,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        items(sections, key = DetailModule::storageKey) { module ->
          Box(Modifier.testTag("detail-module-${module.storageKey}")) {
            DetailModuleContent(
                module,
                layout,
                anime,
                tags,
                characters,
                series,
                siblings,
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
                episodeDetails,
                onOpenEpisodes,
                onSaveMemo,
                linkedCrossMedia,
                onOpenLegadoSync,
            )
          }
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
    episodeDetails: List<EpisodeDetailEntity>,
    onOpenEpisodes: (() -> Unit)?,
    onSaveMemo: (String?) -> Unit,
    linkedCrossMedia: List<com.animeow.app.data.local.SeriesLinkedCrossMediaEntry>,
    onOpenLegadoSync: (() -> Unit)? = null,
) {
    val motionLevel = LocalMotionLevel.current
    when (module) {
        DetailModule.HEADER -> AnimatedVisibility(
            visible = true,
            enter = when (motionLevel) {
                MotionLevel.NONE -> EnterTransition.None
                MotionLevel.REDUCED -> motionFadeIn(260)
                MotionLevel.FULL -> motionFadeIn(260) + scaleIn(
                    tween(scaledMotionDurationMillis(260, motionLevel)),
                    initialScale = 0.94f,
                )
            },
        ) { Column {
            HeaderCard(anime, layout, onCoverClick)
            com.animeow.app.ui.playback.PlaybackEntryButton(anime)
            com.animeow.app.ui.playback.WatchDiscussionEntryButton(anime)
            com.animeow.app.ui.reader.BookReadingEntryButton(anime = anime, onOpenLegadoSync = onOpenLegadoSync)
        } }
        DetailModule.PROGRESS -> {
            val episodeEntry: (@Composable () -> Unit)? = onOpenEpisodes?.let { open ->
                { EpisodeOverviewCard(episodeDetails, open, com.animeow.app.data.local.episodeUnitLabel(anime.subjectType)) }
            }
            if (layout in setOf(DetailLayout.DASHBOARD, DetailLayout.MINIMAL, DetailLayout.THEATER)) {
                DistinctDetailProgress(anime, layout, onEditProgress, onCheckIn, episodeEntry)
            } else ProgressCard(anime, onEditProgress, onCheckIn, episodeEntry)
        }
        DetailModule.WATCH_DATES -> if (layout == DetailLayout.DASHBOARD) DashboardWatchDates(anime, onEditAnime)
            else DetailSurface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                WatchTimelineContent(anime, onEditAnime, Modifier.padding(18.dp))
            }
        DetailModule.METADATA -> MetadataCard(anime)
        DetailModule.SYNOPSIS -> SynopsisCard(anime.synopsis.orEmpty())
        DetailModule.TAGS -> TagsCard(anime.id, tags, onTagOpen, onEditAnime)
        DetailModule.REMINDER -> ReminderCard(anime, onEditReminder)
        DetailModule.SERIES -> series?.let {
            SeriesSiblingsCard(it, siblings, onSeriesOpen, onAnimeOpen, linkedCrossMedia)
        }
        DetailModule.CHARACTERS -> CharactersCard(characters, imageAlignment, onManageCharacters, onCharacterOpen)
        DetailModule.REVIEW -> AnimatedVisibility(
            visible = true,
            enter = motionFadeIn(260),
            exit = motionFadeOut(260),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy((12 * LocalContentDensity.current.scale).dp)) {
                ReviewCard(anime.review.orEmpty(), onEditAnime)
                PrivateMemoCard(anime.memo.orEmpty(), onSaveMemo)
            }
        }
    }
}

private val DASHBOARD_FULL_WIDTH_MODULES = setOf(
    DetailModule.HEADER,
    DetailModule.METADATA,
    DetailModule.TAGS,
    DetailModule.REMINDER,
    DetailModule.SYNOPSIS,
    DetailModule.SERIES,
    DetailModule.CHARACTERS,
    DetailModule.REVIEW,
)

@Composable
internal fun EpisodeOverviewCard(details: List<EpisodeDetailEntity>, onOpen: () -> Unit, unit: String = "集") {
    Column(Modifier.fillMaxWidth()) {
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = "查看分集信息", onClick = onOpen)
            .padding(vertical = 10.dp).testTag("episode-entry"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("分集信息", style = MaterialTheme.typography.labelLarge)
                Text(if (details.isEmpty()) "逐${unit}记录与查看" else "${details.size} ${unit}已有资料",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnimeCharacterLinkDialog(
    workTitle: String,
    workState: WorkCharactersUiState,
    linked: List<CharacterWithRole>,
    allCharacters: List<CharacterListItem>,
    onSaveVoiceActor: suspend (Long, String) -> Unit,
    onLinkLocal: (Long, String?) -> Unit,
    onUnlink: (Long) -> Unit,
    onDeletePermanently: (Long) -> Unit,
    onSelectWork: (com.animeow.app.data.remote.RemoteAnime) -> Unit,
    onSearchWork: (String) -> Unit,
    onReloadWork: () -> Unit,
    onImportRemote: suspend (RemoteCharacter, String?) -> Unit,
    onOpenCharacterCenter: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var workQuery by remember(workTitle) { mutableStateOf(workTitle) }
    var roleName by remember { mutableStateOf("") }
    var useRemote by remember { mutableStateOf(true) }
    var importingId by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingRemoval by remember { mutableStateOf<CharacterWithRole?>(null) }
    var confirmPermanentRemoval by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val linkedIds = linked.mapTo(mutableSetOf()) { it.character.id }
    val linkedRemoteIds = linked.flatMap { listOfNotNull(it.character.bgmId?.let { id -> "bangumi:$id" }, it.character.anilistId?.let { id -> "anilist:$id" }) }.toSet()
    val localResults = allCharacters.filter { item ->
        item.character.id !in linkedIds && (
            query.isBlank() || item.character.name.contains(query, true) || item.character.nameCn.orEmpty().contains(query, true))
    }
    val catalog = workState.catalog
    val remoteResults = catalog?.characters.orEmpty().filter {
        query.isBlank() || it.name.contains(query, true) || it.nameCn.orEmpty().contains(query, true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("作品角色 · $workTitle", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (linked.isNotEmpty()) {
                    item { Text("已关联 ${linked.size} 位 · 点选可填写声优或管理关联", style = MaterialTheme.typography.labelMedium) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(linked, key = { "linked_${it.character.id}" }) { item ->
                                FilterChip(selected = true, onClick = {
                                    pendingRemoval = item
                                    confirmPermanentRemoval = false
                                }, label = { Text(item.character.nameCn?.takeIf(String::isNotBlank) ?: item.character.name) },
                                    trailingIcon = { Icon(AppIcons.Edit, "编辑声优与关联", Modifier.size(16.dp)) })
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = useRemote, onClick = { useRemote = true }, label = { Text("本作角色") })
                        FilterChip(selected = !useRemote, onClick = { useRemote = false }, label = { Text("本地角色") })
                    }
                }
                if (useRemote) item {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Text("联网获取本作角色列表；若网络较慢，也可切到「本地角色」直接关联。",
                            Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                if (!useRemote || catalog?.subjectId != null) {
                    item {
                        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                            label = { Text(if (useRemote) "筛选本作角色" else "搜索本地角色") }, modifier = Modifier.fillMaxWidth())
                    }
                    item {
                        OutlinedTextField(value = roleName, onValueChange = { roleName = it }, singleLine = true,
                            label = { Text("角色定位（可选）") }, placeholder = { Text("留空时采用作品中的定位") }, modifier = Modifier.fillMaxWidth())
                    }
                }
                importError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                when {
                    useRemote && workState.error != null -> item {
                        Column {
                            Text(workState.error, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onReloadWork) { Text("重试角色列表") }
                        }
                    }
                    useRemote && (workState.loading || catalog == null) -> item {
                        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                            Text("正在读取本作角色", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    useRemote && catalog?.subjectId == null -> {
                        item {
                            Text("这部作品还没有 Bangumi 关联。请先确认对应作品，再读取它的角色名单。", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = workQuery, onValueChange = { workQuery = it }, singleLine = true,
                                label = { Text("查找对应作品") }, modifier = Modifier.fillMaxWidth(),
                                trailingIcon = { IconButton(onClick = { onSearchWork(workQuery) }, enabled = workQuery.isNotBlank()) {
                                    Icon(AppIcons.Search, "匹配作品")
                                } })
                        }
                        if (catalog?.candidates.isNullOrEmpty()) item { Text("未找到对应作品，可调整作品标题，或关联本地角色。") }
                        items(catalog?.candidates.orEmpty(), key = { "work_${it.uniqueKey}" }) { work ->
                            Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "查看这部作品的角色", onClick = { onSelectWork(work) })
                                .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AsyncImage(work.coverUrl, null, Modifier.size(40.dp, 56.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                                Column(Modifier.weight(1f)) {
                                    Text(work.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    Text(listOfNotNull(work.airDate, work.originalTitle).joinToString(" · "), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                                }
                            }
                        }
                    }
                    useRemote -> {
                        item { Text("${catalog?.source?.label ?: "角色资料"} · ${catalog?.subjectTitle.orEmpty()} · ${catalog?.characters?.size ?: 0} 位", style = MaterialTheme.typography.labelSmall) }
                        if (remoteResults.isEmpty()) item { Text(if (query.isBlank()) "这部作品暂时没有公开角色资料，可关联本地角色。" else "本作没有匹配的角色") }
                        items(remoteResults, key = { "remote_${it.sourceKey}" }) { item ->
                            val alreadyLinked = item.sourceKey in linkedRemoteIds
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AsyncImage(item.imageUrl, null, Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentScale = ContentScale.Crop)
                                Column(Modifier.weight(1f)) {
                                    Text(item.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    item.roleName?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                if (alreadyLinked) Icon(AppIcons.CheckCircle, "已关联", tint = MaterialTheme.colorScheme.primary)
                                else IconButton(enabled = importingId == null, onClick = {
                                    importingId = item.sourceKey
                                    importError = null
                                    scope.launch {
                                        runCatchingCancellable { onImportRemote(item, roleName.takeIf(String::isNotBlank) ?: item.roleName) }
                                            .onFailure { importError = it.message ?: "导入失败，请重试" }
                                        importingId = null
                                    }
                                }) {
                                    if (importingId == item.sourceKey) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    else Icon(AppIcons.Add, "导入并关联 ${item.displayName}")
                                }
                            }
                        }
                    }
                    else -> {
                        if (localResults.isEmpty()) item { Text("暂无可关联的本地角色") }
                        items(localResults, key = { "local_${it.character.id}" }) { item ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(item.character.nameCn?.takeIf(String::isNotBlank) ?: item.character.name,
                                    Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { onLinkLocal(item.character.id, roleName) }) { Icon(AppIcons.Add, "关联") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = { onDismiss(); onOpenCharacterCenter() }) { Text("角色中心") } },
    )
    pendingRemoval?.let { target ->
        var actorDraft by remember(target.character.id) { mutableStateOf(target.voiceActor.orEmpty()) }
        var actorSaving by remember(target.character.id) { mutableStateOf(false) }
        var actorError by remember(target.character.id) { mutableStateOf<String?>(null) }
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
                onDismissRequest = { if (!actorSaving) pendingRemoval = null },
                title = { Text("管理「$displayName」") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = actorDraft, onValueChange = { actorDraft = it }, enabled = !actorSaving,
                            label = { Text("声优姓名") }, singleLine = true, supportingText = { Text("填写一位声优；留空保存可解除绑定。") })
                        actorError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text("移除只解除当前作品关联；永久删除会移除所有作品关联。")
                    }
                },
                confirmButton = {
                    Column {
                    TextButton(enabled = !actorSaving, onClick = {
                        actorSaving = true
                        scope.launch {
                            runCatchingCancellable { onSaveVoiceActor(target.character.id, actorDraft) }
                                .onSuccess { pendingRemoval = null }
                                .onFailure { actorError = "声优保存失败，请重试" }
                            actorSaving = false
                        }
                    }) { Text(if (actorSaving) "保存中…" else "保存声优") }
                    TextButton(
                        enabled = !actorSaving,
                        onClick = {
                            onUnlink(target.character.id)
                            pendingRemoval = null
                        },
                    ) { Text("仅从本作移除") }
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(enabled = !actorSaving, onClick = { pendingRemoval = null }) { Text("取消") }
                        TextButton(enabled = !actorSaving, onClick = { confirmPermanentRemoval = true }) {
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
                    "角色与声优",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onManageCharacters) { Text("管理配音") }
            }
            if (characters.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(AppIcons.Group, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                                    modifier = Modifier.fillMaxWidth()
                                        .then(if (item.character.imageDisplayMode == "fit") Modifier.aspectRatio(1f) else Modifier.height(148.dp))
                                        .background(if (item.character.imageDisplayMode == "fit") Color.Transparent else MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (!item.character.imageUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = item.character.imageUrl,
                                            contentDescription = item.character.nameCn ?: item.character.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = if (item.character.imageDisplayMode == "fit") ContentScale.Fit else ContentScale.Crop,
                                            alignment = if (item.character.imageDisplayMode == "fit") Alignment.BottomCenter
                                                else if (item.character.imageDisplayMode == "crop") Alignment.Center else alignment,
                                        )
                                    } else {
                                        Icon(
                                            AppIcons.Account,
                                            contentDescription = null,
                                            modifier = Modifier.size(36.dp),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.42f),
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
                                    Text("声优", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(item.voiceActor?.takeIf(String::isNotBlank) ?: "未绑定",
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary)
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
    if (com.animeow.app.ui.legacy.LocalFrontendMode.current == com.animeow.app.ui.theme.FrontendMode.LEGACY) {
        com.animeow.app.ui.legacy.LegacyAnimeHeader(anime, onCoverClick)
        return
    }
    val density = LocalContentDensity.current
    val densityScale = density.scale
    when (layout) {
        DetailLayout.MINIMAL -> { MinimalDetailHeader(anime, onCoverClick); return }
        DetailLayout.DASHBOARD -> { DashboardDetailHeader(anime, onCoverClick); return }
        DetailLayout.THEATER -> { TheaterDetailHeader(anime, onCoverClick); return }
        else -> Unit
    }
    if (layout == DetailLayout.MAGAZINE) {
        DetailSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalContainerColor = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Column {
                Box(
                    Modifier.fillMaxWidth().then(if (anime.coverOrientation in listOf("portrait", "landscape")) Modifier.aspectRatio(com.animeow.app.data.local.coverAspectRatio(anime.coverOrientation, 2f / 3f)) else Modifier.height((270 * density.itemScale).dp)).background(MaterialTheme.colorScheme.primary),
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
                        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.08f), Color.Black.copy(alpha = 0.8f)))))
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
    val isLandscape = anime.coverOrientation == "landscape"
    val coverShape = if (LocalDetailCardStyle.current == DetailCardStyle.GLASS) androidx.compose.foundation.shape.RoundedCornerShape(12.dp) else MaterialTheme.shapes.large
    DetailSurface(
        modifier = Modifier.fillMaxWidth(),
        tonalContainerColor = if (minimal) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer,
        shape = if (minimal) MaterialTheme.shapes.medium else MaterialTheme.shapes.extraLarge,
        tonalElevation = if (minimal) 0.dp else 1.dp,
    ) {
        if (isLandscape) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(((if (minimal) 12 else 18) * densityScale).dp),
                verticalArrangement = Arrangement.spacedBy(((if (minimal) 12 else 14) * densityScale).dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(coverShape)
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
                Column(verticalArrangement = Arrangement.spacedBy((8 * densityScale).dp)) {
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
        } else {
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
                            height = (((if (minimal) 76 else 104) * density.itemScale) / com.animeow.app.data.local.coverAspectRatio(anime.coverOrientation, if (minimal) 76f / 108f else 104f / 148f)).dp,
                        )
                        .clip(coverShape)
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgressCard(
    anime: AnimeEntity,
    onEditProgress: () -> Unit,
    onCheckIn: () -> Unit,
    episodeEntry: (@Composable () -> Unit)? = null,
) {
    val densityScale = LocalContentDensity.current.scale
    val hasTotal = anime.totalEpisodes > 0
    val combined = LocalDetailProgressMode.current == DetailProgressMode.COMBINED
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
                Text("观看进度", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (combined) ProgressEditAffordance(onEditProgress)
            }
            if (!combined) {
                Text(detailProgressLabel(anime.watchedEpisodes, anime.totalEpisodes, LocalEpisodeNumbering.current),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            EpisodeNumberingSummary(anime)
            if (hasTotal) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (combined) {
                CombinedProgressButton(anime, onCheckIn, onEditProgress, Modifier.fillMaxWidth())
            } else FlowRow(horizontalArrangement = Arrangement.spacedBy((10 * densityScale).dp)) {
                Button(
                    onClick = onCheckIn,
                    enabled = !hasTotal || anime.watchedEpisodes < anime.totalEpisodes,
                ) {
                    Text("+1 集")
                }
                OutlinedButton(onClick = onEditProgress) { Text("修改进度") }
            }
            episodeEntry?.invoke()
        }
    }
}

@Composable
internal fun ProgressEditDialog(
    anime: AnimeEntity,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    numbering: com.animeow.app.data.bangumi.EpisodeNumbering? = null,
    onLaunchOverlay: (() -> Unit)? = null,
) {
    // Freeze metadata for this edit session so a late network result cannot reinterpret typed digits.
    val editNumbering = remember(anime.id) { numbering?.takeIf { it.usesContinuousNumbering && it.totalEpisodes == anime.totalEpisodes } }
    var useContinuousNumber by rememberSaveable(anime.id) { mutableStateOf(false) }
    var value by rememberSaveable(anime.id, anime.watchedEpisodes, stateSaver = TextFieldValue.Saver) {
        val initial = anime.watchedEpisodes.toString()
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val parsed = value.text.toIntOrNull()
    val resolvedCount = resolveEpisodeProgressInput(value.text, anime.totalEpisodes, useContinuousNumber, editNumbering)
    val isValid = resolvedCount != null
    val error = when {
        useContinuousNumber -> if (resolvedCount == null) "请输入第 ${editNumbering?.firstNumber}～${editNumbering?.lastNumber} 集；尚未观看请切换到本季集数并填 0。" else null
        parsed == null || parsed < 0 -> "请输入有效的已看集数"
        anime.totalEpisodes > 0 && parsed > anime.totalEpisodes -> "不能超过总集数；如资料有误，请在「编辑完整资料」中修改总集数。"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("精确修改观看进度") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(episodeProgressLabel(anime.watchedEpisodes, anime.totalEpisodes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (editNumbering != null) {
                    Text("本季编号：第 ${editNumbering.firstNumber}～${editNumbering.lastNumber} 集", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !useContinuousNumber, onClick = {
                            if (useContinuousNumber) {
                                val count = value.text.toIntOrNull()?.let(editNumbering::watchedCountForNumber)
                                val text = count?.toString().orEmpty()
                                value = TextFieldValue(text, TextRange(0, text.length))
                                useContinuousNumber = false
                            }
                        }, label = { Text("本季集数") })
                        FilterChip(selected = useContinuousNumber, onClick = {
                            if (!useContinuousNumber) {
                                val number = value.text.toIntOrNull()?.let(editNumbering::numberForWatchedCount)
                                val text = number?.toString().orEmpty()
                                value = TextFieldValue(text, TextRange(0, text.length))
                                useContinuousNumber = true
                            }
                        }, label = { Text("连续集号") })
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.text.all(Char::isDigit)) value = it },
                    label = { Text(if (useContinuousNumber) "看到第几集" else if (editNumbering != null) "本季已看集数" else "已看集数") },
                    isError = !isValid,
                    supportingText = error?.let { message -> ({ Text(message) }) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { resolvedCount?.let(onConfirm) }),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("exact-progress-input"),
                )
                if (useContinuousNumber && resolvedCount != null) {
                    Text("将保存为本季已看 $resolvedCount / ${anime.totalEpisodes} 集", style = MaterialTheme.typography.bodySmall)
                }
                if (onLaunchOverlay != null) {
                    TextButton(
                        onClick = onLaunchOverlay,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(AppIcons.Widgets, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("开启桌面悬浮打卡", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { resolvedCount?.let(onConfirm) },
                enabled = isValid,
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
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
    linkedCrossMedia: List<com.animeow.app.data.local.SeriesLinkedCrossMediaEntry> = emptyList(),
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
            if (siblings.isEmpty() && linkedCrossMedia.isEmpty()) {
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
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (!sibling.coverUrl.isNullOrBlank()) {
                                        AsyncImage(sibling.coverUrl, sibling.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    } else {
                                        Icon(
                                            AppIcons.Collection,
                                            contentDescription = null,
                                            modifier = Modifier.size(32.dp),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.42f),
                                        )
                                    }
                                }
                                Text(
                                    listOfNotNull(sibling.title, sibling.seriesRole).joinToString(" · "),
                                    modifier = Modifier.padding((9 * density.scale).dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                    items(linkedCrossMedia, key = { it.key }) { media ->
                        ElevatedCard(
                            modifier = Modifier.width((116 * density.itemScale).dp).animatedPressClick(
                                onClickLabel = "查看${media.title}所在系列",
                            ) { onSeriesOpen(series.id) },
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Column {
                                Box(
                                    Modifier.fillMaxWidth().height((150 * density.itemScale).dp)
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (media.coverUrl.isNotBlank()) {
                                        AsyncImage(media.coverUrl, media.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    } else {
                                        Text(
                                            media.kindLabel,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Surface(
                                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                                        shape = MaterialTheme.shapes.extraSmall,
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                    ) {
                                        Text(
                                            media.kindLabel,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                                Text(
                                    listOfNotNull(media.title, media.role).joinToString(" · "),
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
            Text("作品资料", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (LocalDetailLayout.current == DetailLayout.DASHBOARD) DetailMetadataHighlights(anime)
            MetadataRow("类型", subjectTypeDisplayName(anime.subjectType))
            anime.mediaFormat?.takeIf(String::isNotBlank)?.let { MetadataRow("载体", it) }
            anime.airDate?.takeIf(String::isNotBlank)?.let { MetadataRow("放送日期", it) }
            anime.studio?.takeIf(String::isNotBlank)?.let { MetadataRow("制作公司", it) }
            if (LocalDetailLayout.current != DetailLayout.DASHBOARD) {
                normalizeAnimeRatingGrade(anime.ratingGrade)?.let { MetadataRow("资料评分", "$it 分") }
                    ?: formatAnimeRating(anime.rating).takeIf(String::isNotBlank)?.let { MetadataRow("资料评分", "$it / 10") }
            }
            sourceName?.let { MetadataRow("资料来源", it) }
            anime.publicRating()?.let { stats ->
                val label = externalSourceName(stats.optString("source")) ?: "来源"
                val count = stats.optInt("count", -1).takeIf { !stats.isNull("count") && it >= 0 }
                MetadataRow("$label 评分人数", count?.let { "$it 人" } ?: "暂无")
                stats.optInt("rank", 0).takeIf { it > 0 }?.let { MetadataRow("$label 综合排名", "#$it") }
            }
            val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.animeow.app.AniMeowApplication
            BangumiExtrasEntry(bangumiSubjectId(anime), anime.title) { remote -> app.libraryRepository.updatePublicRating(anime.id, remote) }
            val otherTitles = com.animeow.app.data.local.parseAnimeSourceReferences(anime.mergedSourcesJson)
                .flatMap { listOfNotNull(it.title, it.originalTitle) }.filter { it.isNotBlank() && it != anime.title }.distinct()
            if (otherTitles.isNotEmpty()) MetadataRow("其他名称", otherTitles.joinToString(" / "))
            val otherSources = com.animeow.app.data.local.parseAnimeSourceReferences(anime.mergedSourcesJson)
                .filter { !it.source.isNullOrBlank() && !it.externalId.isNullOrBlank() }
                .filterNot { it.source == anime.externalSource && it.externalId == anime.externalId }
                .distinctBy { "${it.source}:${it.externalId}" }
            if (otherSources.isNotEmpty()) {
                Text("合并保留的资料来源", style = MaterialTheme.typography.labelLarge)
                otherSources.forEach { reference ->
                    val url = resolvedExternalUrl(anime.copy(externalSource = reference.source, externalId = reference.externalId, externalUrl = reference.url))
                    if (url != null) TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
                        Icon(AppIcons.OpenExternal, null, Modifier.size(16.dp))
                        Text("${externalSourceName(reference.source) ?: reference.source} · ${reference.externalId}", Modifier.padding(start = 8.dp))
                    }
                }
            }
            externalUrl?.let { url ->
                OutlinedButton(
                    onClick = { runCatching { uriHandler.openUri(url) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(AppIcons.OpenExternal, contentDescription = null)
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
            Text("作品简介", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(synopsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = if (LocalDetailLayout.current == DetailLayout.MAGAZINE) MaterialTheme.typography.bodyLarge
                    else MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, Modifier.weight(0.3f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.7f), fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@Composable
internal fun TagsCard(
    workId: Long,
    tags: List<com.animeow.app.data.local.TagEntity>,
    onTagOpen: (Long) -> Unit,
    onEdit: () -> Unit,
) {
    val densityScale = LocalContentDensity.current.scale
    DetailSurface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding((18 * densityScale).dp),
            verticalArrangement = Arrangement.spacedBy((10 * densityScale).dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("标签", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("编辑") }
            }
            com.animeow.app.ui.components.ExpandableTags(tags, onTagOpen, workId = workId)
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
                    "还没有写下评价，点击「写下评价」记录你的观后感。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(review, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PrivateMemoCard(memo: String, onSaveMemo: (String?) -> Unit) {
    val densityScale = LocalContentDensity.current.scale
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(memo) { mutableStateOf(memo) }
    DetailSurface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding((18 * densityScale).dp),
            verticalArrangement = Arrangement.spacedBy((8 * densityScale).dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("私密备注", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "仅保存在本机与备份中，不参与 Bangumi 同步或公开分享",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { draft = memo; editing = true }) {
                    Text(if (memo.isBlank()) "写备注" else "编辑备注")
                }
            }
            if (memo.isBlank()) {
                Text(
                    "可记录资源来源、观看顺序备忘或不想公开的个人随记。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(memo, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("私密备注") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "仅保存在本机与备份中，不随 Bangumi 同步覆盖，也不会出现在分享卡片中。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(10_000) },
                        label = { Text("备注内容") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSaveMemo(draft)
                    editing = false
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (memo.isNotBlank()) {
                        TextButton(onClick = {
                            onSaveMemo(null)
                            editing = false
                        }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { editing = false }) { Text("取消") }
                }
            },
        )
    }
}
