package com.animeow.app.ui.anime

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.bangumi.EpisodeNumbering
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.EPISODE_DURATION_MAX_MINUTES
import com.animeow.app.data.local.EPISODE_ROW_LIMIT
import com.animeow.app.data.local.EPISODE_TEXT_MAX_LENGTH
import com.animeow.app.data.local.EPISODE_TITLE_MAX_LENGTH
import com.animeow.app.data.local.EpisodeDetailEntity
import com.animeow.app.data.local.episodeDetailSummary
import com.animeow.app.data.local.episodeListIsTruncated
import com.animeow.app.data.local.episodeRowNumbers
import com.animeow.app.data.local.episodeUnitLabel
import com.animeow.app.data.local.formatAnimeRating
import com.animeow.app.data.local.parseAnimeRatingInput
import com.animeow.app.data.local.parseEpisodeDurationMinutes
import com.animeow.app.data.local.sanitizeAnimeRatingInput
import com.animeow.app.ui.components.motionFadeThroughSpec
import com.animeow.app.ui.components.motionSlideContentSpec
import com.animeow.app.ui.icons.AppIcon
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.theme.LocalMotionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 分集信息：逐集记录标题、时长、简介、评价与评分。
 *
 * 集号按本季集数排列，与观看进度、观看记录同口径；连续集号只在列表里附带显示，
 * 保存的始终是本季集号。资料只保存在本机，随完整备份迁移，不上报 Bangumi。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeInfoScreen(
    onBack: () -> Unit,
    viewModel: EpisodeInfoViewModel = viewModel(),
    initialEpisode: Int? = null,
) {
    val anime by viewModel.anime.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val numbering by viewModel.episodeNumbering.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var viewing by rememberSaveable(initialEpisode) { mutableStateOf(initialEpisode) }
    var saving by remember { mutableStateOf(false) }

    val current = anime
    val editingNumber = editing
    val viewingNumber = viewing
    val screenMode = when {
        current == null -> "loading"
        editingNumber != null -> "edit:$editingNumber"
        viewingNumber != null -> "view:$viewingNumber"
        else -> "list"
    }
    val motionLevel = LocalMotionLevel.current
    AnimatedContent(
        targetState = screenMode,
        transitionSpec = {
            when {
                initialState == "loading" || targetState == "loading" -> motionFadeThroughSpec(motionLevel)
                targetState.startsWith("edit:") -> motionSlideContentSpec(motionLevel, forward = true)
                initialState.startsWith("edit:") && targetState.startsWith("view:") -> motionSlideContentSpec(motionLevel, forward = false)
                targetState == "list" -> motionSlideContentSpec(motionLevel, forward = false)
                else -> motionSlideContentSpec(motionLevel, forward = true)
            }
        },
        label = "episode_info_mode",
    ) { mode ->
        when {
            current == null || mode == "loading" -> Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("分集信息") },
                        navigationIcon = {
                            IconButton(onClick = onBack) { AppIcon(AppIcons.Back, contentDescription = "返回") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                val unit = episodeUnitLabel(current.subjectType)
                val details = remember(episodes) { episodes.associateBy(EpisodeDetailEntity::episode) }
                val numbers = remember(current.totalEpisodes, episodes) {
                    episodeRowNumbers(current.totalEpisodes, episodes.map(EpisodeDetailEntity::episode))
                }
                val numberingForWork = numbering?.takeIf { it.totalEpisodes == current.totalEpisodes }
                when {
                    mode.startsWith("edit:") -> {
                        val targetEpisode = mode.removePrefix("edit:").toIntOrNull() ?: 1
                        EpisodeEditorScreen(
                            anime = current,
                            episode = targetEpisode,
                            unit = unit,
                            initial = details[targetEpisode],
                            continuousNumber = numberingForWork?.numberForWatchedCount(targetEpisode),
                            saving = saving,
                            onCancel = { editing = null },
                            onSave = { title, duration, synopsis, review, rating ->
                                if (!saving) {
                                    saving = true
                                    scope.launch {
                                        try {
                                            val kept = viewModel.save(targetEpisode, title, duration, synopsis, review, rating)
                                            saving = false
                                            editing = null
                                            viewing = targetEpisode
                                            snackbarHostState.showSnackbar(
                                                if (kept) "第 $targetEpisode ${unit}的资料已保存" else "已清空第 $targetEpisode ${unit}的资料",
                                            )
                                        } catch (error: CancellationException) {
                                            saving = false
                                            throw error
                                        } catch (error: Throwable) {
                                            saving = false
                                            snackbarHostState.showSnackbar(error.message ?: "保存失败，请重试")
                                        }
                                    }
                                }
                            },
                        )
                    }
                    mode.startsWith("view:") -> {
                        val targetEpisode = mode.removePrefix("view:").toIntOrNull() ?: 1
                        EpisodeReadingScreen(
                            current,
                            targetEpisode,
                            unit,
                            details[targetEpisode],
                            numberingForWork?.numberForWatchedCount(targetEpisode),
                            onBack = { if (initialEpisode == targetEpisode) onBack() else viewing = null },
                            onEdit = { editing = targetEpisode },
                            snackbarHostState = snackbarHostState,
                        )
                    }
                    else -> Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text("分集信息") },
                                navigationIcon = {
                                    IconButton(onClick = onBack) { AppIcon(AppIcons.Back, contentDescription = "返回") }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                            )
                        },
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                    ) { padding ->
                        EpisodeInfoList(
                            anime = current,
                            unit = unit,
                            numbers = numbers,
                            details = details,
                            numbering = numberingForWork,
                            onEdit = { editing = it },
                            onView = { viewing = it },
                            modifier = Modifier.fillMaxSize().padding(padding),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeInfoList(
    anime: AnimeEntity,
    unit: String,
    numbers: List<Int>,
    details: Map<Int, EpisodeDetailEntity>,
    numbering: EpisodeNumbering?,
    onEdit: (Int) -> Unit,
    onView: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.testTag("episode-info-list"),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "header") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    anime.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (numbers.isEmpty()) "还没有可列出的${unit}" else "共 ${numbers.size} ${unit} · 已记录 ${details.size} ${unit}资料",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "点选一集查看完整资料，点击铅笔编辑。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "记录每一${unit}的标题、时长、简介与个人感想。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (episodeListIsTruncated(anime.totalEpisodes)) {
                    Text(
                        "总集数超过 $EPISODE_ROW_LIMIT，这里只列出已记录的${unit}；如需整季显示，" +
                            "请先在「编辑完整资料」中修正总集数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "这些资料只保存在本机，随完整备份迁移，不会上报 Bangumi。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (numbers.isEmpty()) {
            item(key = "empty") {
                Text(
                    "这部作品的总集数是 0，暂时无法按${unit}列出。请在「编辑完整资料」中填写总集数后再回来记录。",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(numbers, key = { it }) { number ->
            val detail = details[number]
            val continuous = numbering?.numberForWatchedCount(number)
            ListItem(
                headlineContent = {
                    Text(
                        detail?.title?.takeIf(String::isNotBlank) ?: "第 $number ${unit}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (detail != null) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
                supportingContent = {
                    val summary = episodeDetailSummary(detail)
                    Text(
                        listOfNotNull(
                            continuous?.let { "连续集号 第 $it 集" },
                            summary.ifEmpty { "还没有补充资料" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingContent = {
                    Column(
                        modifier = Modifier.width(56.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("$number", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (number <= anime.watchedEpisodes) {
                            Text(
                                "已看",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                trailingContent = {
                    IconButton(onClick = { onEdit(number) }) {
                        AppIcon(AppIcons.Edit, "编辑第 $number $unit", Modifier.size(20.dp))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("episode-row-$number")
                    .clickable { onView(number) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeReadingScreen(
    anime: AnimeEntity,
    episode: Int,
    unit: String,
    detail: EpisodeDetailEntity?,
    continuousNumber: Int?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(title = { Text("第 $episode $unit") },
            navigationIcon = { IconButton(onClick = onBack) { AppIcon(AppIcons.Back, "返回分集列表") } },
            actions = { TextButton(onClick = onEdit) { Text("编辑") } })
    }, snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(20.dp).navigationBarsPadding().testTag("episode-reading"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(anime.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(detail?.title?.takeIf(String::isNotBlank) ?: "第 $episode $unit", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            Text(listOfNotNull(if (episode <= anime.watchedEpisodes) "已看" else "未看",
                continuousNumber?.let { "连续集号 第 $it 集" },
                detail?.durationMinutes?.let { "$it 分钟" },
                detail?.rating?.let { "评分 ${formatAnimeRating(it)}" }).joinToString(" · "))
            if (detail == null) {
                Text("这一${unit}还没有补充资料。点击右上角「编辑」记录标题、时长、简介、评价和评分。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("单集简介", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(detail.synopsis?.takeIf(String::isNotBlank) ?: "还没有填写简介")
                Text("我的评价", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(detail.review?.takeIf(String::isNotBlank) ?: "还没有填写评价")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeEditorScreen(
    anime: AnimeEntity,
    episode: Int,
    unit: String,
    initial: EpisodeDetailEntity?,
    continuousNumber: Int?,
    saving: Boolean,
    onCancel: () -> Unit,
    onSave: (title: String, durationMinutes: Int?, synopsis: String, review: String, rating: Int?) -> Unit,
) {
    var title by rememberSaveable(episode) { mutableStateOf(initial?.title.orEmpty()) }
    var duration by rememberSaveable(episode) { mutableStateOf(initial?.durationMinutes?.toString().orEmpty()) }
    var synopsis by rememberSaveable(episode) { mutableStateOf(initial?.synopsis.orEmpty()) }
    var review by rememberSaveable(episode) { mutableStateOf(initial?.review.orEmpty()) }
    var rating by rememberSaveable(episode) { mutableStateOf(initial?.rating?.let(::formatAnimeRating).orEmpty()) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val initialTitle = initial?.title.orEmpty()
    val initialDuration = initial?.durationMinutes?.toString().orEmpty()
    val initialSynopsis = initial?.synopsis.orEmpty()
    val initialReview = initial?.review.orEmpty()
    val initialRating = initial?.rating?.let(::formatAnimeRating).orEmpty()
    val isDirty = title != initialTitle || duration != initialDuration || synopsis != initialSynopsis ||
        review != initialReview || rating != initialRating
    val parsedDuration = parseEpisodeDurationMinutes(duration)
    val parsedRating = parseAnimeRatingInput(rating)
    val durationError = when {
        duration.isBlank() -> null
        parsedDuration == null -> "请输入 1～$EPISODE_DURATION_MAX_MINUTES 之间的分钟数"
        else -> null
    }
    val ratingError = when {
        rating.isBlank() -> null
        parsedRating == null -> "请输入 0.1～10 之间的分数，或留空表示未评分"
        else -> null
    }
    val canSave = !saving && durationError == null && ratingError == null

    fun requestClose() {
        if (isDirty) showDiscardDialog = true else onCancel()
    }
    BackHandler(enabled = true) { requestClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("第 $episode ${unit} · 编辑资料") },
                navigationIcon = {
                    IconButton(onClick = { requestClose() }) { AppIcon(AppIcons.Back, contentDescription = "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .testTag("episode-editor"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                anime.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append("第 $episode ${unit}")
                    if (continuousNumber != null) append("（连续集号 第 $continuousNumber 集）")
                    append(" · 保存后按本季集号记录")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.replace('\n', ' ').take(EPISODE_TITLE_MAX_LENGTH) },
                label = { Text("单集标题") },
                placeholder = { Text("例如：初次见面") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = duration,
                onValueChange = { duration = it.filter(Char::isDigit).take(4) },
                label = { Text("时长（分钟）") },
                placeholder = { Text("例如：24") },
                isError = durationError != null,
                supportingText = { Text(durationError ?: "只填整数分钟，留空表示未记录") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = synopsis,
                onValueChange = { synopsis = it.take(EPISODE_TEXT_MAX_LENGTH) },
                label = { Text("这一${unit}的简介") },
                supportingText = { Text("本集剧情或内容提要，留空表示未记录") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = review,
                onValueChange = { review = it.take(EPISODE_TEXT_MAX_LENGTH) },
                label = { Text("这一${unit}的评价") },
                supportingText = { Text("自己的感想，留空表示未记录") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("这一${unit}的评分", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Slider(
                        value = rating.toDoubleOrNull()?.coerceIn(0.0, 10.0)?.toFloat() ?: 0f,
                        onValueChange = { value -> rating = "%.1f".format(java.util.Locale.ROOT, value) },
                        valueRange = 0f..10f,
                        steps = 99,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = rating,
                        onValueChange = { rating = sanitizeAnimeRatingInput(it) },
                        label = { Text("分数") },
                        placeholder = { Text("8.5") },
                        isError = ratingError != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(96.dp),
                    )
                }
                Text(
                    ratingError ?: if (parsedRating == null) "未评分" else "${formatAnimeRating(parsedRating)} / 10",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ratingError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = { onSave(title, parsedDuration, synopsis, review, parsedRating) },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().testTag("episode-editor-save"),
            ) { Text("保存") }
            if (initial != null) {
                TextButton(
                    onClick = { showDeleteDialog = true },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("清空这一${unit}的资料") }
            }
            Text(
                "这些资料只保存在本机，随完整备份迁移，不会上报 Bangumi。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("第 $episode ${unit}的改动还没有保存，返回后会丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onCancel()
                }) { Text("放弃修改") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            },
        )
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("清空第 $episode ${unit}的资料？") },
            text = { Text("标题、时长、简介、评价与评分都会被清除，观看进度与观看记录不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onSave("", null, "", "", null)
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}
