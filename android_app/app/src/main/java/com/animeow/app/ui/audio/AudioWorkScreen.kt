package com.animeow.app.ui.audio

import com.animeow.app.ui.navigation.PageAction
import com.animeow.app.ui.navigation.PageActionRequests
import com.animeow.app.ui.navigation.HandlePageAction

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.audio.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import androidx.core.net.toUri
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionAnimateItem
import com.animeow.app.ui.components.motionExpandIn
import com.animeow.app.ui.components.motionFadeThroughSpec
import com.animeow.app.ui.components.motionShrinkOut
import com.animeow.app.ui.theme.LocalMotionLevel

enum class AudioSortOption(val label: String) {
    RECENT("最近更新"),
    RATING("我的评分"),
    RELEASE_DATE("发行日期"),
    LISTENED("已听时长"),
    TITLE("作品名称"),
}

class AudioWorkViewModel(application: Application) : AndroidViewModel(application) {
    internal val seriesRepository = com.animeow.app.data.MediaSeriesRepository((application as AniMeowApplication).database, com.animeow.app.data.MediaSeriesKind.AUDIO)
    private val repository = AudioWorkRepository((application as AniMeowApplication).database)
    val allSeries: StateFlow<List<com.animeow.app.data.local.SeriesEntity>> =
        (application as AniMeowApplication).libraryRepository.observeSeries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val selected = MutableStateFlow<AudioWork?>(null)
    val progressUndo = MutableStateFlow<AudioProgressChange?>(null)
    val works: StateFlow<List<AudioWork>?> = repository.works.catch {
        message.value = "音声资料暂时无法读取，请重新进入后重试"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(work: AudioWork) = runAction {
        repository.save(work); progressUndo.value = null; selected.value = null; message.value = "已保存音声记录"
    }
    fun delete(work: AudioWork) = runAction {
        repository.delete(work); progressUndo.value = null; selected.value = null; message.value = "已移出音声库，删除状态随 AniMeow 云同步保存"
    }
    fun recordProgress(work: AudioWork, finish: Boolean) = runAction {
        val change = repository.recordProgress(work, finish)
        progressUndo.value = change
        message.value = if (finish) "已标记听完：${work.title}" else "已记至 ${audioTime(change.after.listenedSeconds)}：${work.title}"
    }
    fun undoProgress(change: AudioProgressChange) = runAction {
        repository.undoProgress(change); progressUndo.value = null; message.value = "已撤销这次收听记录"
    }
    private fun runAction(action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: IllegalArgumentException) { message.value = error.message ?: "请检查填写的资料" }
            catch (_: Exception) { message.value = "操作未完成，请重新打开记录后重试" }
            finally { busy.value = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AudioWorkScreen(modifier: Modifier = Modifier, pageActions: PageActionRequests? = null,
    onSeriesOpen: (Long) -> Unit = {},
    viewModel: AudioWorkViewModel = viewModel(), initialWorkId: Long = 0L) {
    val works by viewModel.works.collectAsStateWithLifecycle()
    val allSeries by viewModel.allSeries.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("全部") }
    var sort by rememberSaveable { mutableStateOf(AudioSortOption.RECENT) }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var viewingId by rememberSaveable(initialWorkId) { mutableStateOf(initialWorkId.takeIf { it > 0L }) }
    var showSortMenu by remember { mutableStateOf(false) }
    var series by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val snackbar = remember { SnackbarHostState() }
    val floatingInset = com.animeow.app.ui.navigation.LocalFloatingNavigationInset.current
    val allTags = remember(works) {
        works.orEmpty().flatMap { it.tags.split(',', '，').map(String::trim).filter(String::isNotEmpty) }.distinct().sorted()
    }
    HandlePageAction(pageActions, PageAction.ADD_AUDIO, !busy) { viewModel.selected.value = AudioWork() }
    HandlePageAction(pageActions, PageAction.SEARCH_AUDIO, !busy) { runCatching { searchFocus.requestFocus() } }
    LaunchedEffect(message) { message?.let { text ->
        val change = viewModel.progressUndo.value
        val result = snackbar.showSnackbar(text, actionLabel = change?.let { "撤销" }, withDismissAction = true)
        if (viewModel.message.value == text) viewModel.message.value = null
        if (result == SnackbarResult.ActionPerformed && change != null) viewModel.undoProgress(change)
        else if (viewModel.progressUndo.value == change) viewModel.progressUndo.value = null
    } }
    val results = remember(works, query, status, tagFilter, sort) {
        val filtered = works.orEmpty().filter { work ->
            val workTags = work.tags.split(',', '，').map(String::trim).filter(String::isNotEmpty)
            (status == "全部" || work.status == status) &&
                (tagFilter == null || tagFilter in workTags) &&
                listOf(work.title, work.originalTitle, work.workCode, work.voiceActors, work.circle, work.tags, work.category)
                    .any { it.contains(query.trim(), ignoreCase = true) }
        }
        when (sort) {
            AudioSortOption.RECENT -> filtered.sortedWith(compareByDescending<AudioWork> { it.updatedAt }.thenByDescending { it.id })
            AudioSortOption.RATING -> filtered.sortedWith(compareByDescending<AudioWork> { it.rating }.thenByDescending { it.updatedAt })
            AudioSortOption.RELEASE_DATE -> filtered.sortedWith(compareByDescending<AudioWork> { it.releaseDate }.thenByDescending { it.updatedAt })
            AudioSortOption.LISTENED -> filtered.sortedWith(compareByDescending<AudioWork> { it.listenedSeconds }.thenByDescending { it.updatedAt })
            AudioSortOption.TITLE -> filtered.sortedWith(compareBy<AudioWork> { it.title }.thenByDescending { it.id })
        }
    }
    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            com.animeow.app.ui.components.BrowseSearchField(
                query = query,
                onQueryChanged = { query = it },
                placeholder = "搜索名称、声优、社团或编号",
                enabled = !busy,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .then(androidx.compose.ui.Modifier.focusRequester(searchFocus)),
                controls = {
                    FilledTonalButton(
                        onClick = { series = true },
                        enabled = !busy && works != null,
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        com.animeow.app.ui.icons.AppIcon(com.animeow.app.ui.icons.AppIcons.Collection, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("系列", maxLines = 1)
                    }
                },
            )
            com.animeow.app.ui.components.CollectionFilterPlans("audio",
                com.animeow.app.data.preferences.CollectionFilterConditions(query = query, status = status),
                emptyList(), listOf("全部") + audioStatuses, !busy) { saved ->
                query = saved.query; status = saved.status; null
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("全部") + audioStatuses).forEach { value -> FilterChip(status == value, { status = value }, label = { Text(value) }) }
            }
            AnimatedVisibility(
                visible = !works.isNullOrEmpty(),
                enter = motionExpandIn(),
                exit = motionShrinkOut(),
            ) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Box {
                        AssistChip(
                            onClick = { showSortMenu = true },
                            label = { Text("排序：${sort.label}") },
                        )
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            AudioSortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = { sort = option; showSortMenu = false },
                                )
                            }
                        }
                    }
                    if (tagFilter != null) {
                        FilterChip(
                            selected = true,
                            onClick = { tagFilter = null },
                            label = { Text("标签：$tagFilter ×") },
                        )
                    }
                    allTags.filter { it != tagFilter }.take(12).forEach { tag ->
                        FilterChip(
                            selected = false,
                            onClick = { tagFilter = tag },
                            label = { Text(tag) },
                        )
                    }
                }
            }
            val audioDisplayMode = when {
                works == null -> "loading"
                results.isEmpty() -> if (works!!.isEmpty()) "empty-library" else "empty-filter"
                else -> "list"
            }
            val motionLevel = LocalMotionLevel.current
            AnimatedContent(
                targetState = audioDisplayMode,
                transitionSpec = { motionFadeThroughSpec(motionLevel, 180, 130) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "audio_work_content",
            ) { mode ->
                when (mode) {
                    "loading" -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    "empty-library", "empty-filter" -> Box(
                        Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = floatingInset),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            com.animeow.app.ui.icons.AppIcon(
                                com.animeow.app.ui.icons.AppIcons.Collection,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            )
                            Text(
                                if (mode == "empty-library") "还没有音声记录" else "没有符合条件的音声",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                if (mode == "empty-library") "可手动添加作品资料，或通过编号补全信息。" else "试试其他关键词或切换收听状态。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            if (mode == "empty-library") {
                                Button(onClick = { viewModel.selected.value = AudioWork() }, enabled = !busy) {
                                    com.animeow.app.ui.icons.AppIcon(com.animeow.app.ui.icons.AppIcons.Add, null, Modifier.padding(end = 6.dp).size(18.dp))
                                    Text("添加音声")
                                }
                            }
                        }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
                        bottom = maxOf(100.dp, floatingInset + 24.dp)),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(results, key = { it.id }) { work ->
                            ElevatedCard(
                                Modifier
                                    .fillMaxWidth()
                                    .then(motionAnimateItem())
                                    .motionAnimateContentSize()
                                    .clip(CardDefaults.elevatedShape)
                                    .animatedPressClick(enabled = !busy) { viewingId = work.id },
                            ) {
                                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AudioCover(work.coverUrl, Modifier.size(80.dp, 64.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(work.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (work.originalTitle.isNotBlank() && work.originalTitle != work.title) Text(work.originalTitle,
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("${work.category} · ${work.status}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        if (work.voiceActors.isNotBlank() || work.circle.isNotBlank()) Text(listOf(work.voiceActors, work.circle).filter(String::isNotBlank).joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                        Text("已听 ${audioTime(work.listenedSeconds)}" + if (work.durationSeconds > 0) " / ${audioTime(work.durationSeconds)}" else " · 总时长未填写",
                                            style = MaterialTheme.typography.bodySmall)
                                        if (work.rating > 0) Text("我的评分 ${work.rating}/10", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                AudioQuickProgressActions(work, busy, { viewModel.recordProgress(work, false) }, { viewModel.recordProgress(work, true) })
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = floatingInset),
        )
    }
    val viewingWork = remember(works, viewingId) { works.orEmpty().firstOrNull { it.id == viewingId } }
    if (viewingWork != null && selected == null) {
        AudioDetailSheet(
            work = viewingWork,
            busy = busy,
            linkedSeries = com.animeow.app.data.local.seriesLinkedToWork(allSeries, "AUDIO", viewingWork.syncId),
            onAddProgress = { viewModel.recordProgress(viewingWork, false) },
            onFinishProgress = { viewModel.recordProgress(viewingWork, true) },
            onEdit = {
                viewingId = null
                viewModel.selected.value = viewingWork
            },
            onTagClick = { clickedTag ->
                tagFilter = clickedTag
                viewingId = null
            },
            onSeriesOpen = { seriesId ->
                viewingId = null
                onSeriesOpen(seriesId)
            },
            onDismiss = { viewingId = null },
        )
    }
    selected?.let { work -> AudioWorkEditor(work, busy, onDismiss = { viewModel.selected.value = null }, onSave = viewModel::save, onDelete = viewModel::delete) }
    if (series) com.animeow.app.ui.series.MediaSeriesDialog(com.animeow.app.data.MediaSeriesKind.AUDIO, viewModel.seriesRepository,
        works.orEmpty().map { com.animeow.app.data.MediaSeriesWork(it.syncId, it.title, it.updatedAt,
            source = com.animeow.app.data.audio.audioLookupCode(it.workCode.ifBlank { it.sourceUrl })?.let { "dlsite" },
            externalId = com.animeow.app.data.audio.audioLookupCode(it.workCode.ifBlank { it.sourceUrl })) }, { series = false })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AudioDetailSheet(
    work: AudioWork,
    busy: Boolean,
    linkedSeries: List<com.animeow.app.data.local.SeriesEntity> = emptyList(),
    onAddProgress: () -> Unit,
    onFinishProgress: () -> Unit,
    onEdit: () -> Unit,
    onTagClick: (String) -> Unit = {},
    onSeriesOpen: (Long) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    val tags = remember(work.tags) { work.tags.split(',', '，').map(String::trim).filter(String::isNotEmpty) }
    com.animeow.app.ui.components.StableModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AudioCover(work.coverUrl, Modifier.fillMaxWidth().height(180.dp))
            Text(work.title, style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            if (work.originalTitle.isNotBlank() && work.originalTitle != work.title) {
                Text(work.originalTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = {}, enabled = false, label = { Text(work.category) })
                AssistChip(onClick = {}, enabled = false, label = { Text(work.status) })
                if (work.rating > 0) AssistChip(onClick = {}, enabled = false, label = { Text("我的评分 ${work.rating}/10") })
            }
            if (linkedSeries.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("所属系列", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        linkedSeries.forEach { item ->
                            AssistChip(
                                onClick = { onSeriesOpen(item.id) },
                                leadingIcon = { com.animeow.app.ui.icons.AppIcon(com.animeow.app.ui.icons.AppIcons.Collection, null, Modifier.size(16.dp)) },
                                label = { Text(item.name) },
                            )
                        }
                    }
                }
            }
            listOf(
                "作品编号" to work.workCode,
                "声优" to work.voiceActors,
                "社团／制作方" to work.circle,
                "发行日期" to work.releaseDate,
            ).filter { it.second.isNotBlank() }.forEach { (label, value) ->
                Text("$label：$value", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (work.workCode.isNotBlank()) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                androidx.compose.ui.platform.ClipEntry(
                                    android.content.ClipData.newPlainText("作品编号", work.workCode),
                                ),
                            )
                        }
                    }) { Text("复制编号") }
                }
                if (work.sourceUrl.isNotBlank() && validAudioLink(work.sourceUrl)) {
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, work.sourceUrl.trim().toUri())) }
                    }) { Text("打开原站") }
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "收听进度：已听 ${audioTime(work.listenedSeconds)}" +
                            if (work.durationSeconds > 0) " / ${audioTime(work.durationSeconds)}" else " · 总时长未填写",
                        modifier = Modifier.padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    AudioQuickProgressActions(work, busy, onAddProgress, onFinishProgress)
                }
            }
            if (tags.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("标签", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { tag ->
                            AssistChip(onClick = { onTagClick(tag) }, label = { Text(tag) })
                        }
                    }
                }
            }
            if (work.review.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("收听感想", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(work.review, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = onEdit, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("编辑资料与收听记录")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AudioQuickProgressActions(work: AudioWork, busy: Boolean, onAdd: () -> Unit, onFinish: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        if (work.durationSeconds > 0) LinearProgressIndicator(
            progress = { (work.listenedSeconds.toDouble() / work.durationSeconds).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAdd, enabled = !busy && work.listenedSeconds < (work.durationSeconds.takeIf { it > 0 } ?: 31_536_000L),
                modifier = Modifier.heightIn(min = 48.dp)) { Text("已听 +5 分钟") }
            TextButton(onClick = onFinish, enabled = !busy && (work.status != "已听" || work.durationSeconds > 0 && work.listenedSeconds < work.durationSeconds),
                modifier = Modifier.heightIn(min = 48.dp)) { Text("标记听完") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioWorkEditor(work: AudioWork, busy: Boolean, onDismiss: () -> Unit, onSave: (AudioWork) -> Unit, onDelete: (AudioWork) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog = remember { com.animeow.app.data.remote.MediaCatalogService() }
    var lookupBusy by remember { mutableStateOf(false) }
    var showLookup by remember { mutableStateOf(false) }
    var lookupInput by rememberSaveable(work.id) { mutableStateOf("") }
    var lookupResults by remember { mutableStateOf<List<com.animeow.app.data.remote.AudioSearchCandidate>?>(null) }
    var lookupJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var metadata by remember { mutableStateOf<com.animeow.app.data.remote.AudioMetadata?>(null) }
    var replaceMetadata by remember { mutableStateOf(false) }
    var title by rememberSaveable(work.id) { mutableStateOf(work.title) }
    var originalTitle by rememberSaveable(work.id) { mutableStateOf(work.originalTitle) }
    var cover by rememberSaveable(work.id) { mutableStateOf(work.coverUrl) }
    var category by rememberSaveable(work.id) { mutableStateOf(work.category) }
    var actors by rememberSaveable(work.id) { mutableStateOf(work.voiceActors) }
    var circle by rememberSaveable(work.id) { mutableStateOf(work.circle) }
    var release by rememberSaveable(work.id) { mutableStateOf(work.releaseDate) }
    var duration by rememberSaveable(work.id) { mutableStateOf(if (work.durationSeconds == 0L) "" else audioTime(work.durationSeconds)) }
    var code by rememberSaveable(work.id) { mutableStateOf(work.workCode) }
    var url by rememberSaveable(work.id) { mutableStateOf(work.sourceUrl) }
    var status by rememberSaveable(work.id) { mutableStateOf(work.status) }
    var listened by rememberSaveable(work.id) { mutableStateOf(audioTime(work.listenedSeconds)) }
    var rating by rememberSaveable(work.id) { mutableStateOf(work.rating.toString()) }
    var review by rememberSaveable(work.id) { mutableStateOf(work.review) }
    var tags by rememberSaveable(work.id) { mutableStateOf(work.tags) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    fun draft() = work.copy(title = title, originalTitle = originalTitle, coverUrl = cover, category = category, voiceActors = actors, circle = circle,
        releaseDate = release, durationSeconds = parseAudioTime(duration) ?: -1, workCode = code, sourceUrl = url,
        status = status, listenedSeconds = parseAudioTime(listened) ?: -1, rating = rating.toIntOrNull() ?: -1, review = review, tags = tags)
    fun dismiss() { if (!busy) { if (draft() != work) confirmDiscard = true else onDismiss() } }
    fun lookup(selectedCode: String? = null) {
        val input = selectedCode ?: com.animeow.app.data.audio.audioLookupCode(lookupInput)
        lookupJob?.cancel(); lookupBusy = true; error = null
        lookupJob = scope.launch {
            try {
                if (input != null) { metadata = catalog.audio(input); replaceMetadata = false; showLookup = false }
                else lookupResults = catalog.searchAudio(lookupInput.trim())
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message?.takeIf { it.length <= 120 } ?: "资料暂时无法读取，请检查编号后重试" }
            finally { lookupBusy = false }
        }
    }
    // Keep one editor window active; auxiliary dialogs retain the draft in this parent composition.
    if (metadata == null && !confirmDelete && !confirmDiscard) androidx.compose.ui.window.Dialog(onDismissRequest = {
        if (showLookup) { lookupJob?.cancel(); lookupBusy = false; showLookup = false } else dismiss()
    },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        if (showLookup) AudioLookupForm(lookupInput, { lookupInput = it; lookupResults = null; error = null }, busy || lookupBusy,
            { lookup() }, { lookupJob?.cancel(); lookupBusy = false; showLookup = false }, lookupResults, error, { lookup(it) })
        else Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().offset(x = (-12).dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = ::dismiss, enabled = !busy) {
                    com.animeow.app.ui.icons.AppIcon(com.animeow.app.ui.icons.AppIcons.Back, contentDescription = "返回")
                }
                Text(if (work.id == 0L) "添加音声" else "音声资料与收听记录", style = MaterialTheme.typography.titleLarge)
            }
            Text("先从 DLsite 补全资料，或直接填写下面的表单。补全会先预览，收听记录和感想由你保存。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                lookupInput = code.takeIf { com.animeow.app.data.audio.audioLookupCode(it) != null } ?: url
                lookupResults = null; error = null
                showLookup = true
            }, enabled = !busy && !lookupBusy, modifier = Modifier.fillMaxWidth()) {
                Text(if (lookupBusy) "正在读取资料…" else "从 DLsite 补全资料")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(title, { title = it }, label = { Text("作品名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(originalTitle, { originalTitle = it.take(500) }, label = { Text("作品原名") },
                supportingText = { Text("可保留自定义名称，原名填写来源提供的作品名称。") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                audioCategories.forEach { value -> FilterChip(category == value, { category = value }, label = { Text(value) }) }
            }
            OutlinedTextField(actors, { actors = it }, label = { Text("声优") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(circle, { circle = it }, label = { Text("社团／制作方") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { code = it }, label = { Text("作品编号") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(url, { url = it }, label = { Text("原站链接（可留空）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (url.isNotBlank() && validAudioLink(url.trim())) TextButton(onClick = {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.trim().toUri())) }
                    .onFailure { error = "无法打开链接，请检查地址或复制到浏览器重试" }
            }, enabled = !busy) { Text("打开原站页面") }
            OutlinedTextField(cover, { cover = it }, label = { Text("封面图片链接（可留空）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            AudioCover(cover, Modifier.fillMaxWidth().height(160.dp))
            OutlinedTextField(release, { release = it }, label = { Text("发行日期（YYYY-MM-DD，可留空）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(duration, { duration = it }, label = { Text("总时长（分钟或分:秒，可留空）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                audioStatuses.forEach { value -> FilterChip(status == value, { status = value }, label = { Text(value) }) }
            }
            OutlinedTextField(listened, { listened = it }, label = { Text("已听时长（分钟或分:秒）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(rating, { rating = it }, label = { Text("我的评分（0–10，0 为未评分）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(tags, { tags = it }, label = { Text("标签（用逗号分隔）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(review, { review = it }, label = { Text("收听感想") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Button(onClick = {
                try { val clean = normalizeAudioWork(draft()); error = null; onSave(clean) }
                catch (failure: IllegalArgumentException) { error = failure.message }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在保存…" else "保存音声记录") }
            if (work.id > 0) TextButton(onClick = { confirmDelete = true }, enabled = !busy) { Text("移出音声库", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
        }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("移出音声库？") },
        text = { Text("本地记录会移除，并通过 AniMeow 云同步保留删除状态。原站内容不受影响。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(work) }) { Text("移除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("放弃未保存的修改？") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } })
    metadata?.let { incoming ->
        AlertDialog(onDismissRequest = { metadata = null }, title = { Text("确认补全作品资料") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(incoming.title)
                AudioCover(incoming.coverUrl, Modifier.fillMaxWidth().height(150.dp))
                if (incoming.originalTitle.isNotBlank() && incoming.originalTitle != incoming.title) Text("原名：${incoming.originalTitle}")
                Text(listOf(incoming.workCode, incoming.voiceActors, incoming.circle, incoming.releaseDate, incoming.tags).filter(String::isNotBlank).joinToString("\n"))
                Text("来源：DLsite。默认只填写空白资料；不会修改收听记录、评分或感想。")
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(replaceMetadata, { replaceMetadata = it })
                    Text("替换已填写的作品资料", Modifier.clickable { replaceMetadata = !replaceMetadata })
                }
            } }, confirmButton = { TextButton(onClick = {
                val filled = applyAudioMetadata(draft(), incoming, replaceMetadata)
                title = filled.title; originalTitle = filled.originalTitle; cover = filled.coverUrl; actors = filled.voiceActors; circle = filled.circle
                release = filled.releaseDate; code = filled.workCode; url = filled.sourceUrl; tags = filled.tags
                metadata = null
            }, enabled = !busy) { Text("填入资料") } },
            dismissButton = { TextButton(onClick = { metadata = null }) { Text("取消") } })
    }
}

@Composable
private fun AudioLookupForm(input: String, onInput: (String) -> Unit, busy: Boolean, onRead: () -> Unit, onBack: () -> Unit,
    results: List<com.animeow.app.data.remote.AudioSearchCandidate>?, error: String?, onSelect: (String) -> Unit) {
    val exact = com.animeow.app.data.audio.audioLookupCode(input) != null
    val valid = exact || input.trim().length in 2..80 && !input.trim().startsWith("http", true)
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().offset(x = (-12).dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onBack) {
                com.animeow.app.ui.icons.AppIcon(com.animeow.app.ui.icons.AppIcons.Back, contentDescription = "返回编辑")
            }
            Text("从 DLsite 补全资料", style = MaterialTheme.typography.titleLarge)
        }
        Text("输入编号直接读取，或用作品名、社团、声优关键词查找候选；选择后预览确认。")
        OutlinedTextField(input, onInput, label = { Text("编号、链接或关键词") }, enabled = !busy,
            placeholder = { Text("例如 RJ01570739") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            isError = input.isNotBlank() && !valid,
            supportingText = { Text(if (input.isBlank()) "支持纯数字编号，例如 01570739" else if (!valid) "请输入有效编号、DLsite 链接或 2–80 字关键词" else if (exact) "读取后可预览并确认填入" else "按 DLsite 关键词匹配查询首批候选") })
        Button(onClick = onRead, enabled = !busy && valid, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在查询…" else if (exact || input.isBlank()) "读取资料" else "搜索候选") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        results?.let { values ->
            Text(if (values.isEmpty()) "没有找到相关音声，换个关键词或使用编号读取。" else "首批候选 ${values.size} 项（最多 24 项），请核对后选择。")
            values.forEach { item -> OutlinedCard(onClick = { onSelect(item.workCode) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AudioCover(item.coverUrl, Modifier.size(72.dp, 56.dp))
                    Column(Modifier.weight(1f)) { Text(item.title, maxLines = 3, overflow = TextOverflow.Ellipsis); Text(item.workCode + " · " + item.circle, style = MaterialTheme.typography.bodySmall) }
                }
            } }
        }
        TextButton(onClick = onBack) { Text("取消") }
    }
}
