package com.animeow.app.ui.anime

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.ANIME_RATING_GRADES
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.media.copyUriToCacheFile
import com.animeow.app.data.preferences.AnimeEditorDensity
import com.animeow.app.data.preferences.AnimeEditorModule
import com.animeow.app.data.preferences.AnimeEditorPreset
import com.animeow.app.data.preferences.EditorExitBehavior
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.ui.components.CustomizableColorSelector
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.components.ZoomableImageViewer
import com.animeow.app.ui.components.motionAnimateContentSize
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

private val LocalEditorDensity = staticCompositionLocalOf { AnimeEditorDensity.COMFORTABLE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeEditorScreen(
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    viewModel: AnimeEditorViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingCoverUri by remember { mutableStateOf<Uri?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showCustomization by remember { mutableStateOf(false) }
    var showCreateTag by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coverPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            scope.launch {
                pendingCoverUri = runCatching {
                    copyUriToCacheFile(context, uri, "cover_src")
                }.getOrNull() ?: uri
            }
        }
    }
    val screenshotPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            scope.launch {
                val cached = runCatching {
                    copyUriToCacheFile(context, uri, "screenshot_src")
                }.getOrNull() ?: uri
                viewModel.searchByImage(cached)
            }
        }
    }

    fun requestBack() {
        if (state.isSaving) return
        if (!state.isDirty) {
            onBack()
            return
        }
        when (state.editorSettings.exitBehavior) {
            EditorExitBehavior.ASK -> showExitDialog = true
            EditorExitBehavior.AUTO_SAVE -> viewModel.save()
            EditorExitBehavior.DISCARD -> onBack()
        }
    }

    BackHandler(onBack = ::requestBack)

    LaunchedEffect(viewModel) {
        viewModel.saved.collect { result ->
            result.warning?.let { warning ->
                snackbarHostState.showSnackbar(
                    message = warning,
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
            }
            onSaved(result.animeId)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.isEditing -> "编辑作品"
                            state.subjectType == "book" -> "添加书籍"
                            else -> "添加动画"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showCustomization = true }) {
                        Icon(Icons.Outlined.Tune, contentDescription = "自定义编辑器")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = ::requestBack,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isSaving,
                ) { Text("取消") }
                FilledTonalButton(
                    onClick = viewModel::save,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isSaving && !state.isLoading,
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Outlined.Save, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("保存")
                    }
                }
            }
        },
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        } else {
            val editorDensity = state.editorSettings.density
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = (16 * editorDensity.spacingScale).dp,
                    end = (16 * editorDensity.spacingScale).dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(
                    (12 * state.editorSettings.density.spacingScale).dp,
                ),
            ) {
                val modules = state.editorSettings.moduleOrder.filterNot(state.editorSettings.hiddenModules::contains)
                items(modules, key = AnimeEditorModule::storageKey) { module ->
                    CompositionLocalProvider(LocalEditorDensity provides editorDensity) {
                        when (module) {
                            AnimeEditorModule.BASIC -> BasicEditorCard(
                                state = state,
                                viewModel = viewModel,
                                onPickCover = { coverPicker.launch(arrayOf("image/*")) },
                                onSearchByImage = { screenshotPicker.launch(arrayOf("image/*")) },
                            )
                            AnimeEditorModule.PROGRESS -> ProgressEditorCard(state, viewModel)
                            AnimeEditorModule.TAGS -> TagEditorCard(
                                state = state,
                                viewModel = viewModel,
                                onCreateTag = { showCreateTag = true },
                            )
                            AnimeEditorModule.DETAILS -> DetailsEditorCard(state, viewModel)
                            AnimeEditorModule.REMINDER -> ReminderEditorCard(state, viewModel)
                            AnimeEditorModule.RELATED -> RelatedEditorCard(state, viewModel)
                        }
                    }
                }
                item {
                    state.error?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }

    pendingCoverUri?.let { uri ->
        CoverCropDialog(
            uri = uri,
            processing = state.isCoverProcessing,
            onDismiss = { pendingCoverUri = null },
            onConfirm = { focusX, focusY, zoom ->
                viewModel.importCover(uri, focusX, focusY, zoom)
                pendingCoverUri = null
            },
        )
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("保存本次修改？") },
            text = { Text("你可以保存后退出、放弃修改，或返回继续编辑。") },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        showExitDialog = false
                        viewModel.save()
                    },
                ) { Text("保存并退出") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showExitDialog = false }) { Text("继续编辑") }
                    TextButton(
                        onClick = {
                            showExitDialog = false
                            onBack()
                        },
                    ) { Text("放弃") }
                }
            },
        )
    }

    if (showCustomization) {
        EditorCustomizationSheet(
            settings = state.editorSettings,
            onPresetSelected = viewModel::selectEditorPreset,
            onDensitySelected = viewModel::setEditorDensity,
            onExitBehaviorSelected = viewModel::setExitBehavior,
            onModuleToggled = viewModel::toggleEditorModule,
            onModuleMoved = viewModel::moveEditorModule,
            onDismiss = { showCustomization = false },
        )
    }

    if (showCreateTag) {
        CreateTagDialog(
            onCreate = { name, color ->
                viewModel.createAndSelectTag(name, color)
                showCreateTag = false
            },
            onDismiss = { showCreateTag = false },
        )
    }

    if (state.remoteResults.isNotEmpty()) {
        RemoteResultSheet(
            results = state.remoteResults,
            warnings = state.remoteWarnings,
            onFillBlank = { viewModel.applyRemoteAnime(it, overwrite = false) },
            onOverwrite = { viewModel.applyRemoteAnime(it, overwrite = true) },
            onDismiss = viewModel::dismissRemoteResults,
        )
    }

    if (state.duplicateCandidates.isNotEmpty()) {
        DuplicateTitleDialog(
            candidates = state.duplicateCandidates,
            onSaveStandalone = viewModel::saveAsStandaloneDuplicate,
            onGroupAndSave = viewModel::groupDuplicatesAndSave,
            onDismiss = viewModel::dismissDuplicateWarning,
        )
    }
    state.trashedDuplicate?.let { trashed ->
        AlertDialog(
            onDismissRequest = viewModel::dismissTrashedDuplicate,
            title = { Text("回收站中已有相同作品") },
            text = {
                Text("回收站中存在《${trashed.title}》（来源 ID 相同）。是否从回收站恢复这部作品？")
            },
            confirmButton = {
                FilledTonalButton(onClick = viewModel::restoreTrashedDuplicate) { Text("从回收站恢复") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::dismissTrashedDuplicate) { Text("取消") }
                    TextButton(onClick = {
                        viewModel.dismissTrashedDuplicate()
                        viewModel.saveAsStandaloneDuplicate()
                    }) { Text("仍添加为新作品") }
                }
            },
        )
    }
}

@Composable
private fun BasicEditorCard(
    state: AnimeEditorState,
    viewModel: AnimeEditorViewModel,
    onPickCover: () -> Unit,
    onSearchByImage: () -> Unit,
) {
    val density = LocalEditorDensity.current
    var showCoverViewer by remember(state.coverUrl) { mutableStateOf(false) }
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding((16 * density.spacingScale).dp),
            verticalArrangement = Arrangement.spacedBy((10 * density.spacingScale).dp),
        ) {
            Text("基本信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::updateTitle,
                label = { Text("番剧标题 *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilledTonalButton(onClick = viewModel::searchRemote, enabled = !state.isSearching) {
                        if (state.isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                        }
                        Spacer(Modifier.size(6.dp))
                        Text("搜索并补全")
                    }
                }
                item {
                    OutlinedButton(onClick = onSearchByImage, enabled = !state.isSearching) {
                        Icon(Icons.Outlined.ImageSearch, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("以图搜番")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onPickCover,
                    enabled = !state.isCoverProcessing,
                ) {
                    if (state.isCoverProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                    }
                    Spacer(Modifier.size(6.dp))
                    Text(if (state.coverUrl.isBlank()) "选择本地封面" else "更换并裁剪")
                }
                if (state.coverUrl.isNotBlank()) {
                    TextButton(onClick = { viewModel.updateCoverUrl("") }) { Text("移除") }
                }
            }
            OutlinedTextField(
                value = state.coverUrl,
                onValueChange = viewModel::updateCoverUrl,
                label = { Text("封面地址") },
                placeholder = { Text("支持网络 URL 或导入的本地封面") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = state.coverUrl,
                    contentDescription = "封面预览",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((150 * density.itemScale).dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { showCoverViewer = true },
                    contentScale = ContentScale.Crop,
                )
            }
            ChoiceField(
                label = "作品类型",
                value = state.subjectType,
                options = listOf("anime", "book"),
                onSelected = viewModel::updateSubjectType,
                optionLabel = ::subjectTypeDisplayName,
                modifier = Modifier.fillMaxWidth(),
            )
            AnimeRatingEditor(
                mode = state.ratingMode,
                score = state.rating,
                grade = state.ratingGrade,
                onModeChanged = viewModel::updateRatingMode,
                onScoreChanged = viewModel::updateRating,
                onGradeChanged = viewModel::updateRatingGrade,
            )
            OutlinedTextField(
                value = state.funRatingTier,
                onValueChange = viewModel::updateFunRatingTier,
                label = { Text("趣味评级") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (showCoverViewer && state.coverUrl.isNotBlank()) {
        ZoomableImageViewer(
            imageUrl = state.coverUrl,
            title = state.title.ifBlank { "封面预览" },
            onDismiss = { showCoverViewer = false },
        )
    }
}

@Composable
private fun AnimeRatingEditor(
    mode: String,
    score: String,
    grade: String,
    onModeChanged: (String) -> Unit,
    onScoreChanged: (String) -> Unit,
    onGradeChanged: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("我的评分", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            FilterChip(
                selected = mode == "score",
                onClick = { onModeChanged("score") },
                label = { Text("0–10") },
            )
            FilterChip(
                selected = mode == "grade",
                onClick = { onModeChanged("grade") },
                label = { Text("A–D") },
            )
        }
        if (mode == "grade") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ANIME_RATING_GRADES, key = { it }) { option ->
                    FilterChip(
                        selected = grade == option,
                        onClick = { onGradeChanged(option) },
                        label = { Text(option) },
                    )
                }
                if (grade.isNotBlank()) {
                    item { TextButton(onClick = { onGradeChanged("") }) { Text("清除") } }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Slider(
                    value = score.toFloatOrNull()?.coerceIn(0f, 10f) ?: 0f,
                    onValueChange = { value -> onScoreChanged("%.1f".format(java.util.Locale.ROOT, value)) },
                    valueRange = 0f..10f,
                    steps = 99,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = score,
                    onValueChange = onScoreChanged,
                    label = { Text("分数") },
                    placeholder = { Text("8.5") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(86.dp),
                )
            }
            Text(
                if (score.isBlank()) "未评分" else "$score / 10",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (score.isNotBlank()) {
                TextButton(onClick = { onScoreChanged("") }) { Text("清除评分") }
            }
        }
    }
}

@Composable
private fun ProgressEditorCard(
    state: AnimeEditorState,
    viewModel: AnimeEditorViewModel,
) {
    val density = LocalEditorDensity.current
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding((16 * density.spacingScale).dp),
            verticalArrangement = Arrangement.spacedBy((10 * density.spacingScale).dp),
        ) {
            Text("观看进度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    value = state.watchedEpisodes,
                    label = "已看集数",
                    onValueChange = viewModel::updateWatchedEpisodes,
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    value = state.totalEpisodes,
                    label = "总集数",
                    onValueChange = viewModel::updateTotalEpisodes,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (state.statuses.map { it.name } + state.status).distinct().ifEmpty {
                    listOf("未看", "在看", "看完", "想看", "搁置", "弃坑")
                }.forEach { label ->
                    FilterChip(
                        selected = state.status == label,
                        onClick = { viewModel.updateStatus(label) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TagEditorCard(
    state: AnimeEditorState,
    viewModel: AnimeEditorViewModel,
    onCreateTag: () -> Unit,
) {
    val density = LocalEditorDensity.current
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding((16 * density.spacingScale).dp),
            verticalArrangement = Arrangement.spacedBy((10 * density.spacingScale).dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("标签", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onCreateTag) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("新建")
                }
            }
            if (state.tags.isEmpty()) {
                Text("还没有标签，可直接在这里创建。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.tags, key = { it.id }) { tag ->
                        FilterChip(
                            selected = tag.id in state.selectedTagIds,
                            onClick = { viewModel.toggleTag(tag.id) },
                            label = { Text(tag.name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsEditorCard(
    state: AnimeEditorState,
    viewModel: AnimeEditorViewModel,
) {
    val density = LocalEditorDensity.current
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding((16 * density.spacingScale).dp),
            verticalArrangement = Arrangement.spacedBy((10 * density.spacingScale).dp),
        ) {
            Text("日期与补充信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = state.studio,
                onValueChange = viewModel::updateStudio,
                label = { Text("制作公司") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.originalTitle.isNotBlank()) {
                OutlinedTextField(
                    value = state.originalTitle,
                    onValueChange = viewModel::updateOriginalTitle,
                    label = { Text("原始标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    value = state.tvEpisodes,
                    label = "TV 集数",
                    onValueChange = viewModel::updateTvEpisodes,
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    value = state.spEpisodes,
                    label = "SP 集数",
                    onValueChange = viewModel::updateSpEpisodes,
                    modifier = Modifier.weight(1f),
                )
            }
            DateInput(state.airDate, "放送日期", viewModel::updateAirDate, Modifier.fillMaxWidth())
            DateInput(state.watchStartDate, "开始观看日期", viewModel::updateWatchStartDate, Modifier.fillMaxWidth())
            DateInput(state.watchFinishDate, "完成观看日期", viewModel::updateWatchFinishDate, Modifier.fillMaxWidth())
            OutlinedTextField(
                value = state.synopsis,
                onValueChange = viewModel::updateSynopsis,
                label = { Text("番剧详情 / 作品简介") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.review,
                onValueChange = viewModel::updateReview,
                label = { Text("我的评价（个人感想）") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ReminderEditorCard(
    state: AnimeEditorState,
    viewModel: AnimeEditorViewModel,
) {
    val density = LocalEditorDensity.current
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding((16 * density.spacingScale).dp),
            verticalArrangement = Arrangement.spacedBy((10 * density.spacingScale).dp),
        ) {
            Text("更新提醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("星期与时间需要同时设置。系统无法使用精确闹钟时会自动改用后台调度。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceField(
                    label = "提醒星期",
                    value = weekdayLabel(state.reminderDay),
                    options = WEEKDAY_OPTIONS.map { it.second },
                    onSelected = { selected ->
                        viewModel.updateReminderDay(WEEKDAY_OPTIONS.firstOrNull { it.second == selected }?.first.orEmpty())
                    },
                    modifier = Modifier.weight(1f),
                )
                TimeInput(
                    value = state.reminderTime,
                    onValueChange = viewModel::updateReminderTime,
                    label = "提醒时间",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RelatedEditorCard(
    state: AnimeEditorState,
    viewModel: AnimeEditorViewModel,
) {
    val density = LocalEditorDensity.current
    var showCreateSeries by remember { mutableStateOf(false) }
    var seriesName by remember { mutableStateOf("") }
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding((16 * density.spacingScale).dp),
            verticalArrangement = Arrangement.spacedBy((10 * density.spacingScale).dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("系列归纳", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    seriesName = ""
                    showCreateSeries = true
                }) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("新建系列")
                }
            }
            ChoiceField(
                label = "系列",
                value = state.seriesId?.let { id -> state.series.firstOrNull { it.id == id }?.name } ?: "无系列",
                options = listOf("无系列") + state.series.map { it.name },
                onSelected = { selected -> viewModel.updateSeries(state.series.firstOrNull { it.name == selected }?.id) },
                modifier = Modifier.fillMaxWidth(),
            )
            viewModel.seriesSuggestion()?.let { suggestion ->
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().motionAnimateContentSize(),
                ) {
                    Column(
                        modifier = Modifier.padding((12 * density.spacingScale).dp),
                        verticalArrangement = Arrangement.spacedBy((6 * density.spacingScale).dp),
                    ) {
                        Text("系列归纳建议", fontWeight = FontWeight.SemiBold)
                        Text(suggestion.label, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = viewModel::acceptSeriesSuggestion) { Text("采用") }
                            OutlinedButton(onClick = viewModel::dismissSeriesSuggestion) { Text("忽略") }
                        }
                    }
                }
            }
        }
    }
    if (showCreateSeries) {
        AlertDialog(
            onDismissRequest = { showCreateSeries = false },
            title = { Text("新建系列") },
            text = {
                OutlinedTextField(
                    value = seriesName,
                    onValueChange = { seriesName = it },
                    label = { Text("系列名称") },
                    placeholder = { Text("如：Fate 系列") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        showCreateSeries = false
                        viewModel.createSeries(seriesName)
                    },
                    enabled = seriesName.isNotBlank(),
                ) { Text("创建并选择") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateSeries = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorCustomizationSheet(
    settings: com.animeow.app.data.preferences.AnimeEditorSettings,
    onPresetSelected: (AnimeEditorPreset) -> Unit,
    onDensitySelected: (AnimeEditorDensity) -> Unit,
    onExitBehaviorSelected: (EditorExitBehavior) -> Unit,
    onModuleToggled: (AnimeEditorModule) -> Unit,
    onModuleMoved: (AnimeEditorModule, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自定义编辑器", style = MaterialTheme.typography.headlineSmall)
                        Text("模块按你的习惯排列，修改会实时保存。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = onDismiss) { Text("完成") }
                }
            }
            item {
                Text("预设", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(AnimeEditorPreset.QUICK, AnimeEditorPreset.COMPLETE)) { preset ->
                        FilterChip(
                            selected = settings.preset == preset,
                            onClick = { onPresetSelected(preset) },
                            label = { Text(preset.displayName) },
                        )
                    }
                }
            }
            item {
                Text("信息密度", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimeEditorDensity.entries.forEach { density ->
                        FilterChip(
                            selected = settings.density == density,
                            onClick = { onDensitySelected(density) },
                            label = { Text(density.displayName) },
                        )
                    }
                }
                Text(
                    settings.density.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Text("返回策略", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            items(EditorExitBehavior.entries, key = EditorExitBehavior::storageKey) { behavior ->
                ListItem(
                    headlineContent = { Text(behavior.displayName) },
                    supportingContent = { Text(behavior.description) },
                    trailingContent = {
                        FilterChip(
                            selected = settings.exitBehavior == behavior,
                            onClick = { onExitBehaviorSelected(behavior) },
                            label = { Text(if (settings.exitBehavior == behavior) "已选" else "选择") },
                        )
                    },
                )
            }
            item { Text("模块与顺序", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            items(settings.moduleOrder, key = AnimeEditorModule::storageKey) { module ->
                val index = settings.moduleOrder.indexOf(module)
                ListItem(
                    headlineContent = { Text(module.displayName) },
                    supportingContent = { Text(module.description) },
                    leadingContent = {
                        FilterChip(
                            selected = module !in settings.hiddenModules,
                            onClick = { onModuleToggled(module) },
                            enabled = module != AnimeEditorModule.BASIC,
                            label = { Text(if (module in settings.hiddenModules) "隐藏" else "显示") },
                        )
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { onModuleMoved(module, -1) }, enabled = index > 0) {
                                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "上移")
                            }
                            IconButton(
                                onClick = { onModuleMoved(module, 1) },
                                enabled = index < settings.moduleOrder.lastIndex,
                            ) {
                                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "下移")
                            }
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateTagDialog(
    onCreate: (String, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(EDITOR_TAG_COLORS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建标签") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("标签名称") },
                    singleLine = true,
                )
                Text("颜色", style = MaterialTheme.typography.labelLarge)
                CustomizableColorSelector(
                    color = color,
                    presets = EDITOR_TAG_COLORS,
                    onColorChanged = { color = it },
                    dialogTitle = "自定义标签颜色",
                )
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = { onCreate(name, color) }, enabled = name.isNotBlank()) {
                Text("创建并选中")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteResultSheet(
    results: List<RemoteAnime>,
    warnings: List<String>,
    onFillBlank: (RemoteAnime) -> Unit,
    onOverwrite: (RemoteAnime) -> Unit,
    onDismiss: () -> Unit,
) {
    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("选择资料来源", style = MaterialTheme.typography.headlineSmall)
                Text("“补空白”会保留已有内容；“覆盖”会采用所选结果。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                warnings.forEach { warning ->
                    Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(results, key = RemoteAnime::uniqueKey) { remote ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.size(width = 58.dp, height = 82.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                            ) {
                                if (!remote.coverUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = remote.coverUrl,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(remote.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                remote.originalTitle?.let {
                                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    listOfNotNull(
                                        remote.source.displayName,
                                        remote.airDate?.take(4),
                                        remote.episodes?.let { "$it 集" },
                                        remote.score?.let { "评分 %.1f".format(it) },
                                    ).joinToString(" · "),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilledTonalButton(onClick = { onFillBlank(remote) }) { Text("补空白") }
                                    TextButton(onClick = { onOverwrite(remote) }) { Text("覆盖") }
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
private fun DuplicateTitleDialog(
    candidates: List<com.animeow.app.data.local.AnimeEntity>,
    onSaveStandalone: () -> Unit,
    onGroupAndSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现重名作品") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("资料库中已有 ${candidates.size} 部同名作品。你可以将它们归入同一系列，也可以保留为独立条目。")
                candidates.take(4).forEach { anime ->
                    Text("• ${anime.title} · ${anime.status}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = onGroupAndSave) { Text("归入系列并保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("返回") }
                TextButton(onClick = onSaveStandalone) { Text("仍保存为独立作品") }
            }
        },
    )
}

private val EDITOR_TAG_COLORS = listOf(
    0xFF3482FF,
    0xFF8A4FD0,
    0xFFE85D75,
    0xFFFF8A34,
    0xFF18A999,
    0xFF3E9B55,
    0xFF6C6CE5,
)

@Composable
private fun NumberField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun ChoiceField(
    label: String,
    value: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    optionLabel: (String) -> String = { it },
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 15.dp),
        ) {
            Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(optionLabel(value), maxLines = 1)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.distinct().forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun subjectTypeDisplayName(value: String): String = when (normalizeSubjectType(value)) {
    "book" -> "书籍"
    else -> "动画"
}

private enum class CoverFocus(
    val label: String,
    val focusX: Float,
    val focusY: Float,
    val alignment: Alignment,
) {
    TOP("上", 0.5f, 0f, Alignment.TopCenter),
    LEFT("左", 0f, 0.5f, Alignment.CenterStart),
    CENTER("中", 0.5f, 0.5f, Alignment.Center),
    RIGHT("右", 1f, 0.5f, Alignment.CenterEnd),
    BOTTOM("下", 0.5f, 1f, Alignment.BottomCenter),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoverCropDialog(
    uri: Uri,
    processing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Float, Float, Float) -> Unit,
) {
    var focusX by remember(uri) { mutableStateOf(0.5f) }
    var focusY by remember(uri) { mutableStateOf(0.5f) }
    var zoom by remember(uri) { mutableStateOf(1f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val transformState = rememberTransformableState { _, zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 3f)
        if (viewport.width > 0 && viewport.height > 0) {
            focusX = (focusX - panChange.x / (viewport.width * zoom)).coerceIn(0f, 1f)
            focusY = (focusY - panChange.y / (viewport.height * zoom)).coerceIn(0f, 1f)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("裁剪封面") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 200.dp, height = 300.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .onSizeChanged { viewport = it }
                        .transformable(transformState),
                ) {
                    AsyncImage(
                        model = uri,
                        contentDescription = "封面裁剪预览",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(scaleX = zoom, scaleY = zoom),
                        contentScale = ContentScale.Crop,
                        alignment = BiasAlignment(focusX * 2f - 1f, focusY * 2f - 1f),
                    )
                }
                Text("拖动画面取景，双指缩放", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CoverFocus.entries.forEach { option ->
                        FilterChip(
                            selected = focusX == option.focusX && focusY == option.focusY,
                            onClick = {
                                focusX = option.focusX
                                focusY = option.focusY
                            },
                            label = { Text(option.label) },
                        )
                    }
                }
                Text("缩放 ${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..3f)
                Text(
                    "将按海报常用的 2:3 比例保存，原图不会修改。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { onConfirm(focusX, focusY, zoom) },
                enabled = !processing,
            ) { Text("使用此裁剪") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !processing) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateInput(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { showPicker = true },
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.ifBlank { "选择日期" })
        }
        if (value.isNotBlank()) {
            TextButton(onClick = { onValueChange("") }) { Text("清除") }
        }
    }
    if (showPicker) {
        val initialMillis = remember(value) {
            runCatching {
                LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }.getOrNull()
        }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            onValueChange(
                                Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString(),
                            )
                        }
                        showPicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeInput(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { showPicker = true },
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.ifBlank { "选择时间" })
        }
    }
    if (showPicker) {
        val initial = remember(value) { runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.of(20, 0)) }
        val pickerState = rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(label) },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onValueChange("%02d:%02d".format(pickerState.hour, pickerState.minute))
                        showPicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                Row {
                    if (value.isNotBlank()) {
                        TextButton(
                            onClick = {
                                onValueChange("")
                                showPicker = false
                            },
                        ) { Text("清除") }
                    }
                    TextButton(onClick = { showPicker = false }) { Text("取消") }
                }
            },
        )
    }
}

private val WEEKDAY_OPTIONS = listOf(
    "" to "不提醒",
    "1" to "星期一",
    "2" to "星期二",
    "3" to "星期三",
    "4" to "星期四",
    "5" to "星期五",
    "6" to "星期六",
    "7" to "星期日",
)

private fun weekdayLabel(value: String): String =
    WEEKDAY_OPTIONS.firstOrNull { it.first == value }?.second ?: "不提醒"
