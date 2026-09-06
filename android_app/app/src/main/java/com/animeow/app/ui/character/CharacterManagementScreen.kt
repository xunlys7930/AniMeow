package com.animeow.app.ui.character

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bloodtype
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Female
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Male
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.CharacterEntity
import com.animeow.app.data.local.CharacterListItem
import com.animeow.app.data.local.CharacterRelationItem
import com.animeow.app.data.local.CharacterTagEntity
import com.animeow.app.data.local.CharacterWorkItem
import com.animeow.app.data.local.subjectTypeDisplayName
import com.animeow.app.data.remote.RemoteCharacter
import com.animeow.app.ui.components.DisplayPill
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.CharacterLayout
import com.animeow.app.ui.theme.CharacterImageAlignment
import com.animeow.app.ui.theme.LocalMotionLevel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch
import org.json.JSONArray

private enum class CharacterDetailTab(val label: String) {
    PROFILE("资料"),
    WORKS("作品"),
    RELATIONS("关系星图"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterManagementScreen(
    settings: AppearanceSettings,
    onBack: () -> Unit,
    onGroupsRequested: () -> Unit,
    onLayoutSelected: (CharacterLayout) -> Unit,
    onImageAlignmentSelected: (CharacterImageAlignment) -> Unit,
    onShowMetadataChanged: (Boolean) -> Unit,
    onShowRatingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    initialCharacterId: Long? = null,
    viewModel: CharacterManagementViewModel = viewModel(),
) {
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val allCharacters by viewModel.allCharacterItems.collectAsStateWithLifecycle()
    val selected by viewModel.selectedCharacter.collectAsStateWithLifecycle()
    val selectedTags by viewModel.selectedTags.collectAsStateWithLifecycle()
    val selectedWorks by viewModel.selectedWorks.collectAsStateWithLifecycle()
    val selectedRelations by viewModel.selectedRelations.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val remoteSearch by viewModel.remoteSearch.collectAsStateWithLifecycle()
    val tools by viewModel.tools.collectAsStateWithLifecycle()
    val relationWorkSync by viewModel.relationWorkSync.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var searchText by rememberSaveable { mutableStateOf("") }
    var compactDetailVisible by rememberSaveable { mutableStateOf(false) }
    var showRemoteSearch by rememberSaveable { mutableStateOf(false) }
    var showManualEditor by remember { mutableStateOf<CharacterEntity?>(null) }
    var creatingManualCharacter by remember { mutableStateOf(false) }
    var showCustomization by rememberSaveable { mutableStateOf(false) }
    var showReviewEditor by rememberSaveable { mutableStateOf(false) }
    var showTagEditor by rememberSaveable { mutableStateOf(false) }
    var showWorkLinker by rememberSaveable { mutableStateOf(false) }
    var showRelationEditor by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var duplicateDeleteTarget by remember { mutableStateOf<CharacterListItem?>(null) }
    var duplicateMergeTarget by remember {
        mutableStateOf<Pair<List<CharacterListItem>, CharacterListItem>?>(null)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    LaunchedEffect(showWorkLinker) {
        if (showWorkLinker) viewModel.prepareLinkWorks()
    }
    LaunchedEffect(initialCharacterId) {
        initialCharacterId?.let { characterId ->
            viewModel.selectCharacter(characterId)
            compactDetailVisible = true
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val masterDetail = wide && settings.characterLayout == CharacterLayout.ADAPTIVE
        val showingCompactDetail = !masterDetail && compactDetailVisible && selected != null
        BackHandler(showingCompactDetail && initialCharacterId == null) { compactDetailVisible = false }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (showingCompactDetail) selected?.character?.displayName.orEmpty()
                            else "角色中心",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = if (showingCompactDetail && initialCharacterId == null) {
                            { compactDetailVisible = false }
                        } else onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (!showingCompactDetail) {
                            IconButton(onClick = { showCustomization = true }) {
                                Icon(Icons.Outlined.Tune, contentDescription = "定制角色页面")
                            }
                            IconButton(onClick = viewModel::scanDuplicates) {
                                if (tools.isScanningDuplicates) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Outlined.FindReplace, contentDescription = "角色查重")
                                }
                            }
                            IconButton(onClick = onGroupsRequested) {
                                Icon(Icons.Outlined.Groups, contentDescription = "角色组")
                            }
                            FilledIconButton(onClick = { showRemoteSearch = true }) {
                                Icon(Icons.Outlined.PersonAdd, contentDescription = "添加角色")
                            }
                            Spacer(Modifier.width(6.dp))
                        } else {
                            IconButton(onClick = {
                                showManualEditor = selected?.character
                                creatingManualCharacter = false
                            }) {
                                Icon(Icons.Outlined.Edit, contentDescription = "编辑角色")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
        ) { padding ->
            if (masterDetail) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    CharacterCatalog(
                        characters = characters,
                        selectedId = selected?.character?.id,
                        searchText = searchText,
                        layout = CharacterLayout.LIST,
                        gridColumns = settings.gridColumns,
                        showMetadata = settings.characterShowMetadata,
                        showRating = settings.characterShowRating,
                        imageAlignment = settings.characterImageAlignment,
                        onSearchTextChanged = {
                            searchText = it
                            viewModel.setQuery(it)
                        },
                        onCharacterClick = viewModel::selectCharacter,
                        modifier = Modifier.width(380.dp).fillMaxHeight(),
                    )
                    HorizontalDivider(
                        modifier = Modifier.fillMaxHeight().width(1.dp),
                    )
                    CharacterDetailPane(
                        selected = selected,
                        tags = selectedTags,
                        works = selectedWorks,
                        relations = selectedRelations,
                        settings = settings,
                        onEdit = {
                            showManualEditor = selected?.character
                            creatingManualCharacter = false
                        },
                        onEditReview = { showReviewEditor = true },
                        onEditTags = { showTagEditor = true },
                        onLinkWork = { showWorkLinker = true },
                        onUnlinkWork = viewModel::unlinkWork,
                        onAddRelation = { showRelationEditor = true },
                        onDeleteRelation = viewModel::deleteRelation,
                        onCharacterClick = { id -> viewModel.selectCharacter(id) },
                        onDeleteCharacter = { showDeleteConfirm = true },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            } else {
                AnimatedContent(
                    targetState = showingCompactDetail,
                    transitionSpec = {
                        val duration = (240 * settings.motionLevel.durationScale).toInt()
                        if (targetState) {
                            (fadeIn(tween(duration)) + slideInHorizontally(tween(duration)) { it / 8 })
                                .togetherWith(fadeOut(tween(duration)))
                        } else {
                            fadeIn(tween(duration)).togetherWith(
                                fadeOut(tween(duration)) + slideOutHorizontally(tween(duration)) { it / 8 },
                            )
                        }
                    },
                    label = "character_master_detail",
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) { detailVisible ->
                    if (detailVisible) {
                        CharacterDetailPane(
                            selected = selected,
                            tags = selectedTags,
                            works = selectedWorks,
                            relations = selectedRelations,
                            settings = settings,
                            onEdit = {
                                showManualEditor = selected?.character
                                creatingManualCharacter = false
                            },
                            onEditReview = { showReviewEditor = true },
                            onEditTags = { showTagEditor = true },
                            onLinkWork = { showWorkLinker = true },
                            onUnlinkWork = viewModel::unlinkWork,
                            onAddRelation = { showRelationEditor = true },
                            onDeleteRelation = viewModel::deleteRelation,
                            onCharacterClick = { id ->
                                viewModel.selectCharacter(id)
                                compactDetailVisible = true
                            },
                            onDeleteCharacter = { showDeleteConfirm = true },
                        )
                    } else {
                        CharacterCatalog(
                            characters = characters,
                            selectedId = selected?.character?.id,
                            searchText = searchText,
                            layout = settings.characterLayout,
                            gridColumns = settings.gridColumns,
                            showMetadata = settings.characterShowMetadata,
                            showRating = settings.characterShowRating,
                            imageAlignment = settings.characterImageAlignment,
                            onSearchTextChanged = {
                                searchText = it
                                viewModel.setQuery(it)
                            },
                            onCharacterClick = { characterId ->
                                viewModel.selectCharacter(characterId)
                                compactDetailVisible = true
                            },
                        )
                    }
                }
            }
        }
    }

    if (showRemoteSearch) {
        RemoteCharacterSearchDialog(
            state = remoteSearch,
            onSearch = viewModel::searchRemote,
            onImport = { character ->
                viewModel.importRemote(character)
                showRemoteSearch = false
            },
            onManualCreate = {
                showRemoteSearch = false
                creatingManualCharacter = true
                showManualEditor = CharacterEntity(name = "")
            },
            onDismiss = {
                showRemoteSearch = false
                viewModel.clearRemoteSearch()
            },
        )
    }
    showManualEditor?.let { character ->
        CharacterEditorDialog(
            character = character,
            isNew = creatingManualCharacter,
            onSave = {
                viewModel.saveManual(it)
                showManualEditor = null
                creatingManualCharacter = false
            },
            onDismiss = {
                showManualEditor = null
                creatingManualCharacter = false
            },
        )
    }
    if (showCustomization) {
        CharacterCustomizationSheet(
            settings = settings,
            onLayoutSelected = onLayoutSelected,
            onImageAlignmentSelected = onImageAlignmentSelected,
            onShowMetadataChanged = onShowMetadataChanged,
            onShowRatingChanged = onShowRatingChanged,
            onDismiss = { showCustomization = false },
        )
    }
    if (showReviewEditor && selected != null) {
        CharacterReviewDialog(
            character = selected!!.character,
            onSave = { rating, review ->
                viewModel.updateReview(rating, review)
                showReviewEditor = false
            },
            onDismiss = { showReviewEditor = false },
        )
    }
    if (showTagEditor && selected != null) {
        CharacterTagsDialog(
            allTags = tags,
            selectedIds = selectedTags.mapTo(mutableSetOf(), CharacterTagEntity::id),
            onSave = {
                viewModel.setTags(it)
                showTagEditor = false
            },
            onCreate = { name, current -> viewModel.createAndAttachTag(name, current) },
            onDismiss = { showTagEditor = false },
        )
    }
    if (showWorkLinker && selected != null) {
        WorkLinkDialog(
            isLoading = tools.isLoadingWorks,
            works = tools.linkableWorks,
            onConfirm = { ids, role ->
                viewModel.linkWorks(ids, role)
                showWorkLinker = false
            },
            onDismiss = { showWorkLinker = false },
        )
    }
    if (showRelationEditor && selected != null) {
        RelationEditorDialog(
            sourceCharacterId = selected!!.character.id,
            candidates = allCharacters,
            onConfirm = { id, type, note, strength ->
                viewModel.saveRelation(id, type, note, strength)
                showRelationEditor = false
            },
            onDismiss = { showRelationEditor = false },
        )
    }
    if (showDeleteConfirm && selected != null) {
        DeleteCharacterDialog(
            character = selected!!,
            onConfirm = {
                viewModel.deleteCharacter(selected!!.character.id)
                showDeleteConfirm = false
                compactDetailVisible = false
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
    if (tools.duplicateGroups.isNotEmpty()) {
        DuplicateCharactersDialog(
            groups = tools.duplicateGroups,
            imageAlignment = settings.characterImageAlignment,
            onDelete = { duplicateDeleteTarget = it },
            onMerge = { group, keep -> duplicateMergeTarget = group to keep },
            onDismiss = viewModel::dismissDuplicates,
        )
    }
    duplicateDeleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { duplicateDeleteTarget = null },
            title = { Text("删除重复角色？") },
            text = {
                Text("「${target.character.displayName}」的作品关联、关系、标签和角色组成员资格会一并删除，且无法撤销。")
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteCharacter(target.character.id)
                    duplicateDeleteTarget = null
                }) { Text("永久删除") }
            },
            dismissButton = {
                TextButton(onClick = { duplicateDeleteTarget = null }) { Text("取消") }
            },
        )
    }
    duplicateMergeTarget?.let { (group, keep) ->
        AlertDialog(
            onDismissRequest = { duplicateMergeTarget = null },
            title = { Text("合并为「${keep.character.displayName}」？") },
            text = {
                Text(
                    "其余 ${group.size - 1} 份资料的作品、标签、关系和角色组成员资格会汇总到保留项，重复档案随后永久删除。",
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.mergeDuplicateGroup(
                        keepId = keep.character.id,
                        sourceIds = group.map { it.character.id }.filterNot { it == keep.character.id },
                    )
                    duplicateMergeTarget = null
                }) { Text("合并") }
            },
            dismissButton = {
                TextButton(onClick = { duplicateMergeTarget = null }) { Text("取消") }
            },
        )
    }
    relationWorkSync?.let { sync ->
        val sourceName = allCharacters.firstOrNull { it.character.id == sync.sourceCharacterId }
            ?.character?.displayName ?: "当前角色"
        val targetName = allCharacters.firstOrNull { it.character.id == sync.targetCharacterId }
            ?.character?.displayName ?: "目标角色"
        RelationWorkSyncDialog(
            state = sync,
            sourceName = sourceName,
            targetName = targetName,
            onConfirm = viewModel::syncRelationWorks,
            onDismiss = viewModel::dismissRelationWorkSync,
        )
    }
}

private val CharacterEntity.displayName: String
    get() = nameCn?.takeIf(String::isNotBlank) ?: name

private fun CharacterImageAlignment.toAlignment(): Alignment = when (this) {
    CharacterImageAlignment.TOP -> Alignment.TopCenter
    CharacterImageAlignment.CENTER -> Alignment.Center
    CharacterImageAlignment.BOTTOM -> Alignment.BottomCenter
}

@Composable
private fun CharacterCatalog(
    characters: List<CharacterListItem>,
    selectedId: Long?,
    searchText: String,
    layout: CharacterLayout,
    gridColumns: Int,
    showMetadata: Boolean,
    showRating: Boolean,
    imageAlignment: CharacterImageAlignment,
    onSearchTextChanged: (String) -> Unit,
    onCharacterClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchText,
            onValueChange = onSearchTextChanged,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            placeholder = { Text("搜索姓名或角色标签") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                AnimatedVisibility(
                    visible = searchText.isNotEmpty(),
                    enter = motionFadeIn(),
                    exit = motionFadeOut(),
                ) {
                    IconButton(onClick = { onSearchTextChanged("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = "清除搜索")
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
        )
        if (characters.isEmpty()) {
            EmptyCharacterState(
                searching = searchText.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        } else if (layout == CharacterLayout.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns.coerceIn(2, 5)),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                gridItems(characters, key = { it.character.id }) { item ->
                    CharacterGridCard(
                        item = item,
                        selected = item.character.id == selectedId,
                        showMetadata = showMetadata,
                        showRating = showRating,
                        imageAlignment = imageAlignment,
                        onClick = { onCharacterClick(item.character.id) },
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(characters, key = { it.character.id }) { item ->
                    CharacterListCard(
                        item = item,
                        selected = item.character.id == selectedId,
                        showMetadata = showMetadata,
                        showRating = showRating,
                        imageAlignment = imageAlignment,
                        onClick = { onCharacterClick(item.character.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyCharacterState(searching: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (searching) Icons.Outlined.Search else Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                if (searching) "没有匹配的角色" else "还没有角色资料",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (searching) "可以尝试姓名、中文名或标签" else "从 Bangumi 搜索，或手动创建第一位角色",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CharacterListCard(
    item: CharacterListItem,
    selected: Boolean,
    showMetadata: Boolean,
    showRating: Boolean,
    imageAlignment: CharacterImageAlignment,
    onClick: () -> Unit,
) {
    val character = item.character
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .animatedPressClick(
                onClickLabel = "查看${character.displayName}详情",
                onClick = onClick,
            )
            .motionAnimateContentSize(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CharacterPortrait(character, Modifier.size(68.dp), contentAlignment = imageAlignment.toAlignment())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(character.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                character.name.takeUnless { it == character.displayName }?.let {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (showMetadata) {
                    Text(
                        "${item.workCount} 部作品 · ${item.relationCount} 条关系 · ${item.tagCount} 个标签",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showRating) RatingBadge(character.rating)
        }
    }
}

@Composable
private fun CharacterGridCard(
    item: CharacterListItem,
    selected: Boolean,
    showMetadata: Boolean,
    showRating: Boolean,
    imageAlignment: CharacterImageAlignment,
    onClick: () -> Unit,
) {
    val character = item.character
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .animatedPressClick(
                onClickLabel = "查看${character.displayName}详情",
                onClick = onClick,
            ),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(184.dp)) {
                CharacterPortrait(character, Modifier.fillMaxSize(), rounded = false, contentAlignment = imageAlignment.toAlignment())
                if (showRating && character.rating != null) {
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { RatingBadge(character.rating) }
                }
            }
            Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(character.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                if (showMetadata) {
                    Text(
                        "${item.workCount} 作品 · ${item.relationCount} 关系",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CharacterPortrait(
    character: CharacterEntity,
    modifier: Modifier,
    rounded: Boolean = true,
    contentAlignment: Alignment = Alignment.TopCenter,
) {
    Box(
        modifier = modifier
            .then(if (rounded) Modifier.clip(MaterialTheme.shapes.large) else Modifier)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (!character.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = character.imageUrl,
                contentDescription = character.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = contentAlignment,
            )
        } else {
            Text(
                character.displayName.take(2),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun RatingBadge(rating: Int?) {
    if (rating == null) return
    DisplayPill(rating.toString(), icon = Icons.Outlined.StarOutline)
}

@Composable
private fun CharacterDetailPane(
    selected: CharacterListItem?,
    tags: List<CharacterTagEntity>,
    works: List<CharacterWorkItem>,
    relations: List<CharacterRelationItem>,
    settings: AppearanceSettings,
    onEdit: () -> Unit,
    onEditReview: () -> Unit,
    onEditTags: () -> Unit,
    onLinkWork: () -> Unit,
    onUnlinkWork: (Long) -> Unit,
    onAddRelation: () -> Unit,
    onDeleteRelation: (Long) -> Unit,
    onCharacterClick: (Long) -> Unit,
    onDeleteCharacter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("选择一位角色查看完整资料", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val character = selected.character
    var tabIndex by rememberSaveable(character.id) { mutableIntStateOf(0) }
    val tab = CharacterDetailTab.entries[tabIndex.coerceIn(0, CharacterDetailTab.entries.lastIndex)]
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CharacterPortrait(character, Modifier.size(96.dp), contentAlignment = settings.characterImageAlignment.toAlignment())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(character.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                character.name.takeUnless { it == character.displayName }?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (settings.characterShowMetadata) {
                    Text(
                        "${selected.workCount} 部作品 · ${selected.relationCount} 条关系 · ${selected.tagCount} 个标签",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "编辑角色资料") }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(CharacterDetailTab.entries) { candidate ->
                FilterChip(
                    selected = tab == candidate,
                    onClick = { tabIndex = candidate.ordinal },
                    label = { Text(candidate.label) },
                )
            }
        }
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val duration = (220 * settings.motionLevel.durationScale).toInt()
                (fadeIn(tween(duration)) + scaleIn(tween(duration), initialScale = 0.985f))
                    .togetherWith(fadeOut(tween(duration)))
            },
            modifier = Modifier.weight(1f),
            label = "character_detail_tab",
        ) { currentTab ->
            when (currentTab) {
                CharacterDetailTab.PROFILE -> CharacterProfileTab(
                    item = selected,
                    tags = tags,
                    settings = settings,
                    onEditReview = onEditReview,
                    onEditTags = onEditTags,
                    onDeleteCharacter = onDeleteCharacter,
                )
                CharacterDetailTab.WORKS -> CharacterWorksTab(
                    works = works,
                    onLinkWork = onLinkWork,
                    onUnlinkWork = onUnlinkWork,
                )
                CharacterDetailTab.RELATIONS -> CharacterRelationsTab(
                    center = character,
                    relations = relations,
                    onAddRelation = onAddRelation,
                    onDeleteRelation = onDeleteRelation,
                    onCharacterClick = onCharacterClick,
                )
            }
        }
    }
}

@Composable
private fun CharacterProfileTab(
    item: CharacterListItem,
    tags: List<CharacterTagEntity>,
    settings: AppearanceSettings,
    onEditReview: () -> Unit,
    onEditTags: () -> Unit,
    onDeleteCharacter: () -> Unit,
) {
    val character = item.character
    val infoboxRows = remember(character.infoboxJson) { parseInfobox(character.infoboxJson) }
    LazyColumn(
        contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (settings.characterShowMetadata) {
            item {
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    LazyRow(
                        contentPadding = PaddingValues(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        character.gender?.let { gender ->
                            item { InfoChip(gender, if (gender.contains("女")) Icons.Outlined.Female else Icons.Outlined.Male) }
                        }
                        birthdayText(character)?.let { item { InfoChip(it, Icons.Outlined.Cake) } }
                        character.bloodType?.let { item { InfoChip("$it 型", Icons.Outlined.Bloodtype) } }
                        character.bgmId?.let { item { InfoChip("Bangumi #$it", Icons.Outlined.MoreHoriz) } }
                    }
                }
            }
        }
        character.summary?.takeIf(String::isNotBlank)?.let { summary ->
            item {
                DetailCard(title = "角色简介") {
                    Text(summary, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        item {
            DetailCard(
                title = "角色标签",
                action = { TextButton(onClick = onEditTags) { Text("编辑") } },
            ) {
                if (tags.isEmpty()) {
                    Text("还没有角色标签", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(tags, key = CharacterTagEntity::id) { tag ->
                            DisplayPill(tag.name, icon = Icons.Outlined.Tag)
                        }
                    }
                }
            }
        }
        if (settings.characterShowRating) {
            item {
                DetailCard(
                    title = "我的评价",
                    action = { TextButton(onClick = onEditReview) { Text("编辑") } },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            character.rating?.let { "$it / 10" } ?: "未评分",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    character.review?.takeIf(String::isNotBlank)?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it)
                    }
                }
            }
        }
        if (settings.characterShowMetadata && infoboxRows.isNotEmpty()) {
            item {
                DetailCard(title = "更多资料") {
                    infoboxRows.take(10).forEach { (key, value) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(key, Modifier.width(100.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = onDeleteCharacter, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.DeleteForever, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("永久删除角色")
            }
        }
    }
}

@Composable
private fun DetailCard(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                action?.invoke()
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun InfoChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    DisplayPill(text, icon = icon)
}

@Composable
private fun CharacterWorksTab(
    works: List<CharacterWorkItem>,
    onLinkWork: () -> Unit,
    onUnlinkWork: (Long) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            FilledTonalButton(onClick = onLinkWork, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Link, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("关联资料库作品")
            }
        }
        if (works.isEmpty()) {
            item { EmptySection("还没有关联作品", "可将动画或书籍与这位角色关联。") }
        } else {
            items(works, key = { it.anime.id }) { work ->
                WorkCard(work, onUnlink = { onUnlinkWork(work.anime.id) })
            }
        }
    }
}

@Composable
private fun WorkCard(item: CharacterWorkItem, onUnlink: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(width = 54.dp, height = 76.dp).clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            ) {
                item.anime.coverUrl?.let {
                    AsyncImage(it, item.anime.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(item.anime.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    item.roleName ?: subjectTypeDisplayName(item.anime.subjectType),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onUnlink) { Icon(Icons.Outlined.Close, contentDescription = "移除关联") }
        }
    }
}

@Composable
private fun CharacterRelationsTab(
    center: CharacterEntity,
    relations: List<CharacterRelationItem>,
    onAddRelation: () -> Unit,
    onDeleteRelation: (Long) -> Unit,
    onCharacterClick: (Long) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            FilledTonalButton(onClick = onAddRelation, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Workspaces, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("添加角色关系")
            }
        }
        item {
            RelationGraph(center, relations, onCharacterClick, Modifier.fillMaxWidth().height(280.dp))
        }
        if (relations.isEmpty()) {
            item { EmptySection("关系星图还是空的", "添加亲友、宿敌、搭档或自定义关系。") }
        } else {
            items(relations, key = { it.relation.id }) { relation ->
                ElevatedCard(Modifier.fillMaxWidth().clickable {
                    onCharacterClick(relation.relatedCharacterId)
                }, shape = MaterialTheme.shapes.large) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!relation.relatedImageUrl.isNullOrBlank()) {
                                AsyncImage(
                                    relation.relatedImageUrl,
                                    relation.displayName,
                                    Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            } else Text(relation.displayName.take(1), fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(relation.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${relation.relation.relationType} · 强度 ${relation.relation.strength}/5",
                                color = MaterialTheme.colorScheme.primary,
                            )
                            relation.relation.note?.let {
                                Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { onDeleteRelation(relation.relation.id) }) {
                            Icon(Icons.Outlined.Close, contentDescription = "删除关系")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RelationGraph(
    center: CharacterEntity,
    relations: List<CharacterRelationItem>,
    onCharacterClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotionLevel.current
    val progress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween((500 * motion.durationScale).toInt()),
        label = "relation_graph_reveal",
    )
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val onSurface = MaterialTheme.colorScheme.onSurface
    val visibleRelations = relations.take(10)

    ElevatedCard(modifier, shape = MaterialTheme.shapes.extraLarge) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            val centerX = widthPx / 2f
            val centerY = heightPx / 2f
            val radiusPx = min(widthPx, heightPx) * 0.36f

            Canvas(Modifier.matchParentSize()) {
                visibleRelations.forEachIndexed { index, relation ->
                    val angle = (2.0 * PI * index / maxOf(visibleRelations.size, 1)) - PI / 2
                    val ex = centerX + (cos(angle) * radiusPx * progress).toFloat()
                    val ey = centerY + (sin(angle) * radiusPx * progress).toFloat()
                    drawLine(
                        color = primary.copy(alpha = 0.3f + relation.relation.strength * 0.1f),
                        start = Offset(centerX, centerY),
                        end = Offset(ex, ey),
                        strokeWidth = relation.relation.strength.dp.toPx(),
                    )
                }
            }

            RelationGraphNode(
                imageUrl = center.imageUrl,
                name = center.displayName.take(6),
                size = 68.dp,
                circleColor = primary,
                textColor = Color.White,
                onClick = null,
            )

            visibleRelations.forEachIndexed { index, relation ->
                val angle = (2.0 * PI * index / maxOf(visibleRelations.size, 1)) - PI / 2
                val dx = (cos(angle) * radiusPx * progress).toFloat()
                val dy = (sin(angle) * radiusPx * progress).toFloat()

                RelationGraphNode(
                    imageUrl = relation.relatedImageUrl,
                    name = relation.displayName.take(6),
                    size = 50.dp,
                    circleColor = surface,
                    textColor = onSurface,
                    onClick = { onCharacterClick(relation.relatedCharacterId) },
                    modifier = Modifier.offset { IntOffset(dx.roundToInt(), dy.roundToInt()) },
                )
            }
        }
    }
}

@Composable
private fun RelationGraphNode(
    imageUrl: String?,
    name: String,
    size: androidx.compose.ui.unit.Dp,
    circleColor: Color,
    textColor: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(circleColor)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(name.take(1), fontWeight = FontWeight.Bold, color = textColor)
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptySection(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun birthdayText(character: CharacterEntity): String? {
    val month = character.birthMonth
    val day = character.birthDay
    return when {
        character.birthYear != null && month != null && day != null -> "${character.birthYear}年${month}月${day}日"
        month != null && day != null -> "${month}月${day}日"
        character.birthYear != null -> "${character.birthYear}年"
        else -> null
    }
}

private fun parseInfobox(raw: String?): List<Pair<String, String>> = runCatching {
    val array = JSONArray(raw ?: "[]")
    buildList {
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val key = row.optString("key").takeIf(String::isNotBlank) ?: continue
            val value = row.opt("value")?.toString()?.takeIf(String::isNotBlank) ?: continue
            add(key to value)
        }
    }
}.getOrDefault(emptyList())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CharacterCustomizationSheet(
    settings: AppearanceSettings,
    onLayoutSelected: (CharacterLayout) -> Unit,
    onImageAlignmentSelected: (CharacterImageAlignment) -> Unit,
    onShowMetadataChanged: (Boolean) -> Unit,
    onShowRatingChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("定制角色中心", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("设置会即时生效并写入完整备份。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("布局方式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CharacterLayout.entries.filter { it != CharacterLayout.ADAPTIVE }.forEach { layout ->
                FilterChip(
                    selected = settings.characterLayout == layout,
                    onClick = { onLayoutSelected(layout) },
                    label = { Text(layout.displayName) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("图片显示范围", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("高比例图片截取显示的位置，默认顶部以保留头部", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CharacterImageAlignment.entries.forEach { alignment ->
                FilterChip(
                    selected = settings.characterImageAlignment == alignment,
                    onClick = { onImageAlignmentSelected(alignment) },
                    label = { Text(alignment.displayName) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ToggleRow("显示作品、关系与标签计数", settings.characterShowMetadata, onShowMetadataChanged)
            ToggleRow("显示个人评分", settings.characterShowRating, onShowRatingChanged)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked, onCheckedChange = onChanged)
    }
}

@Composable
private fun RemoteCharacterSearchDialog(
    state: RemoteCharacterSearchState,
    onSearch: (String) -> Unit,
    onImport: (RemoteCharacter) -> Unit,
    onManualCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从 Bangumi 添加角色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("角色姓名") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { onSearch(query) }, enabled = query.isNotBlank() && !state.isSearching) {
                            Icon(Icons.Outlined.Search, contentDescription = "搜索")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    state.isSearching -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
                    state.results.isEmpty() -> Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                        Text("输入姓名后搜索；中文、日文或英文均可。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.results, key = RemoteCharacter::id) { character ->
                            ElevatedCard(
                                Modifier.fillMaxWidth().animatedPressClick(
                                    onClickLabel = "导入${character.displayName}",
                                ) { onImport(character) },
                                shape = MaterialTheme.shapes.large,
                            ) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(58.dp).clip(MaterialTheme.shapes.medium)
                                            .background(MaterialTheme.colorScheme.secondaryContainer),
                                    ) {
                                        character.imageUrl?.let {
                                            AsyncImage(it, character.displayName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(character.displayName, fontWeight = FontWeight.SemiBold)
                                        if (character.name != character.displayName) {
                                            Text(character.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSearch(query) }, enabled = query.isNotBlank() && !state.isSearching) { Text("搜索") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onManualCreate) { Text("手动创建") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun CharacterEditorDialog(
    character: CharacterEntity,
    isNew: Boolean,
    onSave: (CharacterEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(character.id, isNew) { mutableStateOf(character.name) }
    var nameCn by remember(character.id, isNew) { mutableStateOf(character.nameCn.orEmpty()) }
    var imageUrl by remember(character.id, isNew) { mutableStateOf(character.imageUrl.orEmpty()) }
    var gender by remember(character.id, isNew) { mutableStateOf(character.gender.orEmpty()) }
    var summary by remember(character.id, isNew) { mutableStateOf(character.summary.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "手动创建角色" else "编辑角色资料") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { OutlinedTextField(name, { name = it }, label = { Text("原名 *") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(nameCn, { nameCn = it }, label = { Text("中文名") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(imageUrl, { imageUrl = it }, label = { Text("图片地址") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(gender, { gender = it }, label = { Text("性别") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    OutlinedTextField(
                        summary,
                        { summary = it },
                        label = { Text("角色简介") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        character.copy(
                            name = name,
                            nameCn = nameCn.takeIf(String::isNotBlank),
                            imageUrl = imageUrl.takeIf(String::isNotBlank),
                            gender = gender.takeIf(String::isNotBlank),
                            summary = summary.takeIf(String::isNotBlank),
                        ),
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CharacterReviewDialog(
    character: CharacterEntity,
    onSave: (Int?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var rating by remember(character.id) { mutableStateOf(character.rating?.toFloat() ?: 0f) }
    var review by remember(character.id) { mutableStateOf(character.review.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("评价${character.displayName}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (rating < 1f) "未评分" else "${rating.toInt()} / 10", style = MaterialTheme.typography.titleLarge)
                Slider(rating, { rating = it }, valueRange = 0f..10f, steps = 9)
                OutlinedTextField(
                    review,
                    { review = it },
                    label = { Text("个人评价") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(rating.toInt().takeIf { it > 0 }, review) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CharacterTagsDialog(
    allTags: List<CharacterTagEntity>,
    selectedIds: Set<Long>,
    onSave: (Set<Long>) -> Unit,
    onCreate: (String, Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selection by remember(selectedIds) { mutableStateOf(selectedIds) }
    var newTag by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑角色标签") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            newTag,
                            { newTag = it },
                            label = { Text("新标签") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                onCreate(newTag, selection)
                                newTag = ""
                            },
                            enabled = newTag.isNotBlank(),
                        ) { Icon(Icons.Outlined.Add, contentDescription = "创建标签") }
                    }
                }
                items(allTags, key = CharacterTagEntity::id) { tag ->
                    FilterChip(
                        selected = tag.id in selection,
                        onClick = {
                            selection = if (tag.id in selection) selection - tag.id else selection + tag.id
                        },
                        label = { Text(tag.name) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(selection) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun WorkLinkDialog(
    isLoading: Boolean,
    works: List<AnimeEntity>,
    onConfirm: (Set<Long>, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var roleName by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(works, query) { works.filter { it.title.contains(query, ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关联作品") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    OutlinedTextField(query, { query = it }, label = { Text("搜索资料库") }, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(roleName, { roleName = it }, label = { Text("角色定位（可选）") }, modifier = Modifier.fillMaxWidth())
                }
                if (isLoading) {
                    item {
                        Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else {
                    items(filtered, key = AnimeEntity::id) { anime ->
                        FilterChip(
                            selected = anime.id in selection,
                            onClick = { selection = if (anime.id in selection) selection - anime.id else selection + anime.id },
                            label = { Text(anime.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selection, roleName) }, enabled = selection.isNotEmpty()) {
                Text("关联 ${selection.size} 部")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RelationEditorDialog(
    sourceCharacterId: Long,
    candidates: List<CharacterListItem>,
    onConfirm: (Long, String, String?, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var relationType by rememberSaveable { mutableStateOf("关联") }
    var note by rememberSaveable { mutableStateOf("") }
    var strength by rememberSaveable { mutableStateOf(3f) }
    val filtered = remember(candidates, query, sourceCharacterId) {
        candidates.filter { item ->
            item.character.id != sourceCharacterId && (
                query.isBlank() || item.character.displayName.contains(query, ignoreCase = true) ||
                    item.character.name.contains(query, ignoreCase = true)
                )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加角色关系") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { OutlinedTextField(query, { query = it }, label = { Text("搜索目标角色") }, modifier = Modifier.fillMaxWidth()) }
                items(filtered.take(30), key = { it.character.id }) { item ->
                    FilterChip(
                        selected = selectedId == item.character.id,
                        onClick = { selectedId = item.character.id },
                        label = { Text(item.character.displayName) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item { OutlinedTextField(relationType, { relationType = it }, label = { Text("关系类型") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(note, { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    Text("关系强度：${strength.toInt()} / 5")
                    Slider(strength, { strength = it }, valueRange = 1f..5f, steps = 3)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedId?.let { onConfirm(it, relationType, note, strength.toInt()) } },
                enabled = selectedId != null,
            ) { Text("保存关系") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RelationWorkSyncDialog(
    state: RelationWorkSyncState,
    sourceName: String,
    targetName: String,
    onConfirm: (Set<Long>, Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var targetToSource by remember(state) {
        mutableStateOf<Set<Long>>(state.targetWorksMissingFromSource.mapTo(mutableSetOf()) { it.anime.id })
    }
    var sourceToTarget by remember(state) {
        mutableStateOf<Set<Long>>(state.sourceWorksMissingFromTarget.mapTo(mutableSetOf()) { it.anime.id })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("同步关系角色的作品？") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        "两位角色的作品关联不一致。取消不需要补齐的项目后再确认。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.targetWorksMissingFromSource.isNotEmpty()) {
                    item { Text("补到 $sourceName", fontWeight = FontWeight.SemiBold) }
                    items(state.targetWorksMissingFromSource, key = { "target_to_source_${it.anime.id}" }) { work ->
                        FilterChip(
                            selected = work.anime.id in targetToSource,
                            onClick = {
                                targetToSource = if (work.anime.id in targetToSource) {
                                    targetToSource - work.anime.id
                                } else targetToSource + work.anime.id
                            },
                            label = { Text(work.anime.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (state.sourceWorksMissingFromTarget.isNotEmpty()) {
                    item { Text("补到 $targetName", fontWeight = FontWeight.SemiBold) }
                    items(state.sourceWorksMissingFromTarget, key = { "source_to_target_${it.anime.id}" }) { work ->
                        FilterChip(
                            selected = work.anime.id in sourceToTarget,
                            onClick = {
                                sourceToTarget = if (work.anime.id in sourceToTarget) {
                                    sourceToTarget - work.anime.id
                                } else sourceToTarget + work.anime.id
                            },
                            label = { Text(work.anime.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(targetToSource, sourceToTarget) }) {
                Text("同步 ${targetToSource.size + sourceToTarget.size} 项")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("跳过") } },
    )
}

@Composable
private fun DeleteCharacterDialog(
    character: CharacterListItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("永久删除角色？") },
        text = {
            Text(
                "「${character.character.displayName}」将从 ${character.workCount} 部作品、${character.relationCount} 条关系、标签和角色组中移除。此操作无法撤销。",
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("永久删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DuplicateCharactersDialog(
    groups: List<List<CharacterListItem>>,
    imageAlignment: CharacterImageAlignment,
    onDelete: (CharacterListItem) -> Unit,
    onMerge: (List<CharacterListItem>, CharacterListItem) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现 ${groups.sumOf { it.size - 1 }} 个重复角色") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                groups.forEachIndexed { index, group ->
                    item(key = "title_$index") {
                        Text("重复组 ${index + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    items(group, key = { "duplicate_${it.character.id}" }) { item ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CharacterPortrait(item.character, Modifier.size(48.dp), contentAlignment = imageAlignment.toAlignment())
                                Column(Modifier.weight(1f)) {
                                    Text(item.character.displayName, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${item.workCount} 作品 · ${item.relationCount} 关系 · ID ${item.character.id}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { onMerge(group, item) }) { Text("保留并合并") }
                                IconButton(onClick = { onDelete(item) }) {
                                    Icon(Icons.Outlined.DeleteForever, contentDescription = "删除此重复项")
                                }
                            }
                        }
                    }
                    item(key = "divider_$index") { HorizontalDivider() }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}
