package com.animeow.app.ui.tier

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.ui.preferences.AppearanceViewModel
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.theme.DEFAULT_TIER_BOARD_TITLE
import com.animeow.app.ui.theme.DEFAULT_TIER_DISPLAY_LABELS
import com.animeow.app.util.runCatchingCancellable
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private data class TierTarget(val code: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TierListScreen(
    onBack: () -> Unit,
    viewModel: TierListViewModel = viewModel(),
    appearanceViewModel: AppearanceViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val settings by appearanceViewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var showClearConfirmation by rememberSaveable { mutableStateOf(false) }
    var selectedAnime by remember { mutableStateOf<AnimeEntity?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var exportingImage by remember { mutableStateOf(false) }

    LaunchedEffect(operation.message) {
        operation.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val labelsByCode = remember(settings.tierDisplayLabels) {
        TIER_CODES.zip(settings.tierDisplayLabels).toMap()
    }
    val activeTierCodes = remember(settings.tierListCount) {
        TIER_CODES.take(settings.tierListCount.coerceIn(3, TIER_CODES.size))
    }
    val displayState = remember(state, activeTierCodes) { collapseTierGroups(state, activeTierCodes) }
    val targets = remember(labelsByCode, activeTierCodes) {
        listOf(TierTarget(UNRATED_TIER_CODE, "待评级")) +
            activeTierCodes.map { code -> TierTarget(code, labelsByCode.getValue(code)) }
    }
    val groupsByCode = displayState.groups.associateBy(TierGroup::code)
    val unrated = groupsByCode[UNRATED_TIER_CODE]?.animes.orEmpty()
    val visibleUnrated = remember(unrated, query) {
        val normalized = query.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) unrated else unrated.filter { anime ->
            anime.title.lowercase(Locale.ROOT).contains(normalized) ||
                anime.originalTitle.orEmpty().lowercase(Locale.ROOT).contains(normalized)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        settings.tierBoardTitle,
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
                    IconButton(onClick = { showSettingsSheet = true }, enabled = !operation.busy) {
                        Icon(Icons.Outlined.Tune, contentDescription = "自定义榜单")
                    }
                    IconButton(
                        onClick = { showClearConfirmation = true },
                        enabled = state.ratedCount > 0 && !operation.busy,
                    ) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "清空榜单")
                    }
                    IconButton(
                        modifier = Modifier.semantics {
                            contentDescription = if (exportingImage) {
                                "正在生成 Tier List 图片"
                            } else {
                                "分享 Tier List 图片"
                            }
                        },
                        onClick = {
                            exportingImage = true
                            scope.launch {
                                runCatchingCancellable {
                                    TierListImageExporter.export(
                                        context = context,
                                        state = displayState,
                                        style = settings.tierListStyle,
                                        boardTitle = settings.tierBoardTitle,
                                        displayLabels = settings.tierDisplayLabels,
                                    )
                                }.onSuccess { uri ->
                                    shareTierListImage(
                                        context = context,
                                        uri = uri,
                                        state = displayState,
                                        boardTitle = settings.tierBoardTitle,
                                        displayLabels = settings.tierDisplayLabels,
                                    )
                                }.onFailure { error ->
                                    snackbar.showSnackbar(error.message ?: "Tier List 图片生成失败")
                                }
                                exportingImage = false
                            }
                        },
                        enabled = state.ratedCount > 0 && !exportingImage && !operation.busy,
                    ) {
                        if (exportingImage) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Share, contentDescription = null)
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                TierBoardSummary(
                    title = settings.tierBoardTitle,
                    ratedCount = state.ratedCount,
                    animeCount = state.animeCount,
                )
            }
            if (state.animeCount == 0) {
                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("还没有可评级的动画", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "添加动画作品后即可制作自己的梯队榜；书籍不会混入这里。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        label = { Text("搜索待评级作品") },
                        supportingText = {
                            Text("${visibleUnrated.size} / ${state.unratedCount} 部待评级")
                        },
                    )
                }
                item(key = UNRATED_TIER_CODE) {
                    TierRow(
                        code = UNRATED_TIER_CODE,
                        displayLabel = "待评级",
                        animes = visibleUnrated,
                        rowIndex = 0,
                        targets = targets,
                        emptyText = if (query.isBlank()) "所有动画都已评级" else "没有匹配的待评级作品",
                        hapticEnabled = settings.hapticFeedback,
                        enabled = !operation.busy,
                        onMove = viewModel::moveAnime,
                        onChoose = { selectedAnime = it },
                    )
                }
                items(activeTierCodes, key = { it }) { code ->
                    TierRow(
                        code = code,
                        displayLabel = labelsByCode.getValue(code),
                        animes = groupsByCode[code]?.animes.orEmpty(),
                        rowIndex = targets.indexOfFirst { it.code == code },
                        targets = targets,
                        emptyText = "把作品拖到这里，或点击海报选择档位",
                        hapticEnabled = settings.hapticFeedback,
                        enabled = !operation.busy,
                        onMove = viewModel::moveAnime,
                        onChoose = { selectedAnime = it },
                    )
                }
            }
        }
    }

    if (showSettingsSheet) {
        TierBoardSettingsSheet(
            title = settings.tierBoardTitle,
            labels = settings.tierDisplayLabels,
            tierCount = settings.tierListCount,
            onSave = { title, labels, tierCount ->
                appearanceViewModel.setTierListCustomization(title, labels)
                appearanceViewModel.setTierListCount(tierCount)
                showSettingsSheet = false
            },
            onDismiss = { showSettingsSheet = false },
        )
    }

    selectedAnime?.let { anime ->
        ModalBottomSheet(onDismissRequest = { if (!operation.busy) selectedAnime = null }) {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp),
            ) {
                Text(
                    anime.title,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                targets.forEach { target ->
                    ListItem(
                        headlineContent = { Text(target.label) },
                        supportingContent = if (target.code in TIER_CODES && target.label != target.code) {
                            { Text("${target.code} 档") }
                        } else null,
                        leadingContent = {
                            Box(
                                modifier = Modifier.size(38.dp).clip(MaterialTheme.shapes.small)
                                    .background(tierColor(target.code)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    target.code.takeIf(TIER_CODES::contains) ?: "—",
                                    color = Color.White,
                                    fontWeight = FontWeight.Black,
                                )
                            }
                        },
                        modifier = Modifier.clickable(enabled = !operation.busy) {
                            viewModel.moveAnime(anime.id, target.code) { selectedAnime = null }
                        },
                    )
                }
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!operation.busy) showClearConfirmation = false },
            title = { Text("清空趣味评级？") },
            text = { Text("${state.ratedCount} 部作品会回到待评级，不会删除作品或影响数字评分。") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearBoard { showClearConfirmation = false }
                    },
                    enabled = !operation.busy,
                ) { Text(if (operation.busy) "清空中…" else "清空榜单") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }, enabled = !operation.busy) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TierBoardSummary(title: String, ratedCount: Int, animeCount: Int) {
    val progress = if (animeCount == 0) 0f else ratedCount.toFloat() / animeCount
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text(
                "已评级 $ratedCount / $animeCount 部 · 长按上下拖动，点击可直接选档",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TierBoardSettingsSheet(
    title: String,
    labels: List<String>,
    tierCount: Int,
    onSave: (String, List<String>, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var draftTitle by remember(title) { mutableStateOf(title) }
    var draftLabels by remember(labels) { mutableStateOf(labels.toList()) }
    var draftTierCount by remember(tierCount) { mutableIntStateOf(tierCount.coerceIn(3, TIER_CODES.size)) }
    val error = tierCustomizationError(draftTitle, draftLabels, draftTierCount)

    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("自定义趣味榜单", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            item {
                OutlinedTextField(
                    value = draftTitle,
                    onValueChange = { if (it.length <= 32) draftTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("榜单与分享图标题") },
                )
            }
            item {
                Text("榜单档位数量", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (3..TIER_CODES.size).forEach { count ->
                        androidx.compose.material3.FilterChip(
                            selected = draftTierCount == count,
                            onClick = { draftTierCount = count },
                            label = { Text("$count 档") },
                        )
                    }
                }
                Text(
                    "减少档位只会折叠显示原评级，不会删除数据；视觉固定为极简排行。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(activeTierIndices(draftTierCount), key = { TIER_CODES[it] }) { index ->
                val code = TIER_CODES[index]
                OutlinedTextField(
                    value = draftLabels.getOrElse(index) { code },
                    onValueChange = { value ->
                        if (value.length <= 16) {
                            draftLabels = TIER_CODES.indices.map { itemIndex ->
                                if (itemIndex == index) value else draftLabels.getOrElse(itemIndex) { TIER_CODES[itemIndex] }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("$code 档显示名称") },
                )
            }
            if (error != null) {
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        onClick = {
                            draftTitle = DEFAULT_TIER_BOARD_TITLE
                            draftLabels = DEFAULT_TIER_DISPLAY_LABELS
                            draftTierCount = TIER_CODES.size
                        },
                    ) { Text("恢复默认") }
                    Button(
                        onClick = { onSave(draftTitle.trim(), draftLabels.map(String::trim), draftTierCount) },
                        enabled = error == null,
                    ) { Text("保存榜单设置") }
                }
            }
        }
    }
}

private fun tierCustomizationError(title: String, labels: List<String>, tierCount: Int): String? {
    val normalizedTitle = title.trim()
    if (normalizedTitle.isEmpty()) return "榜单标题不能为空"
    if (normalizedTitle.length > 32) return "榜单标题最多 32 个字符"
    if (labels.size != TIER_CODES.size) return "榜单档位数据不完整"
    val normalized = labels.take(tierCount.coerceIn(3, TIER_CODES.size)).map(String::trim)
    if (normalized.any(String::isEmpty)) return "档位名称不能为空"
    if (normalized.any { it.length > 16 }) return "每个档位名称最多 16 个字符"
    if (normalized.any { '\n' in it || '\r' in it }) return "档位名称不能换行"
    if (normalized.map { it.lowercase(Locale.ROOT) }.distinct().size != normalized.size) return "显示中的档位名称不能重复"
    return null
}

private fun activeTierIndices(tierCount: Int): List<Int> =
    (0 until tierCount.coerceIn(3, TIER_CODES.size)).toList()

private fun collapseTierGroups(state: TierListUiState, activeTierCodes: List<String>): TierListUiState {
    val groupsByCode = state.groups.associateBy(TierGroup::code)
    val activeGroups = activeTierCodes.mapIndexed { index, code ->
        val sourceCodes = if (index == activeTierCodes.lastIndex) TIER_CODES.drop(index) else listOf(code)
        TierGroup(code, sourceCodes.flatMap { source -> groupsByCode[source]?.animes.orEmpty() })
    }
    return TierListUiState(
        activeGroups + TierGroup(
            UNRATED_TIER_CODE,
            groupsByCode[UNRATED_TIER_CODE]?.animes.orEmpty(),
        ),
    )
}

@Composable
private fun TierRow(
    code: String,
    displayLabel: String,
    animes: List<AnimeEntity>,
    rowIndex: Int,
    targets: List<TierTarget>,
    emptyText: String,
    hapticEnabled: Boolean,
    enabled: Boolean,
    onMove: (Long, String) -> Unit,
    onChoose: (AnimeEntity) -> Unit,
) {
    val color = tierColor(code)
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 148.dp)) {
            Column(
                modifier = Modifier.width(74.dp).heightIn(min = 148.dp).background(color).padding(horizontal = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (code in TIER_CODES) {
                    Text(code, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                }
                Text(
                    displayLabel,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (animes.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                    Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(animes, key = AnimeEntity::id) { anime ->
                        DraggableTierPoster(
                            anime = anime,
                            tierIndex = rowIndex,
                            targets = targets,
                            hapticEnabled = hapticEnabled,
                            enabled = enabled,
                            onMove = onMove,
                            onChoose = onChoose,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DraggableTierPoster(
    anime: AnimeEntity,
    tierIndex: Int,
    targets: List<TierTarget>,
    hapticEnabled: Boolean,
    enabled: Boolean,
    onMove: (Long, String) -> Unit,
    onChoose: (AnimeEntity) -> Unit,
) {
    var dragY by remember(anime.id) { mutableFloatStateOf(0f) }
    var targetIndex by remember(anime.id, tierIndex) { mutableIntStateOf(tierIndex) }
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .width(72.dp)
            .heightIn(min = 132.dp)
            .graphicsLayer(
                translationY = dragY,
                scaleX = if (dragY == 0f) 1f else 1.08f,
                scaleY = if (dragY == 0f) 1f else 1.08f,
                shadowElevation = if (dragY == 0f) 0f else 14f,
            )
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = "选择《${anime.title}》的评级",
                onClick = { onChoose(anime) },
            )
            .pointerInput(anime.id, tierIndex, targets, enabled) {
                if (!enabled) return@pointerInput
                val rowHeight = 158.dp.toPx()
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragEnd = {
                        if (targetIndex != tierIndex) onMove(anime.id, targets[targetIndex].code)
                        dragY = 0f
                    },
                    onDragCancel = { dragY = 0f },
                ) { change, amount ->
                    change.consume()
                    dragY += amount.y
                    targetIndex = (tierIndex + (dragY / rowHeight).roundToInt()).coerceIn(targets.indices)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(100.dp).clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            if (!anime.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = anime.coverUrl,
                    contentDescription = anime.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            if (dragY != 0f) {
                Icon(
                    Icons.Outlined.DragIndicator,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center),
                    tint = Color.White,
                )
            }
        }
        Text(anime.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        if (dragY != 0f) {
            Text(
                "→ ${targets[targetIndex].label}",
                color = tierColor(targets[targetIndex].code),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun shareTierListImage(
    context: Context,
    uri: Uri,
    state: TierListUiState,
    boardTitle: String,
    displayLabels: List<String>,
) {
    val labels = TIER_CODES.zip(displayLabels).toMap()
    val text = buildString {
        appendLine(boardTitle)
        state.groups.filter { it.code in TIER_CODES }.forEach { group ->
            if (group.animes.isNotEmpty()) {
                appendLine("${labels[group.code]}: ${group.animes.joinToString { it.title }}")
            }
        }
    }
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, boardTitle, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "分享 Tier List 图片",
        ),
    )
}

internal fun tierColor(code: String): Color = when (code.uppercase(Locale.ROOT)) {
    "S" -> Color(0xFFE55454)
    "A" -> Color(0xFFF08A24)
    "B" -> Color(0xFFE6B93E)
    "C" -> Color(0xFF4AA96C)
    "D" -> Color(0xFF4B84D7)
    else -> Color(0xFF737884)
}
