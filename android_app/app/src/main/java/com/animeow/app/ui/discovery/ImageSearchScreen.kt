package com.animeow.app.ui.discovery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.R
import com.animeow.app.data.remote.ImageSearchMatch
import com.animeow.app.ui.icons.AppIcon
import com.animeow.app.ui.icons.AppIcons
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageSearchScreen(onBack: () -> Unit, onAnimeImported: (Long) -> Unit, viewModel: ImageSearchViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::search) }
    LaunchedEffect(viewModel) { viewModel.openAnime.collect(onAnimeImported) }
    LaunchedEffect(state.importError) { state.importError?.let { snackbar.showSnackbar(it); viewModel.dismissImportError() } }
    Scaffold(topBar = {
        TopAppBar(title = { Text("以图搜番") }, navigationIcon = {
            IconButton(onClick = onBack) { AppIcon(AppIcons.Back, "返回") }
        })
    }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        ImageSearchContent(state, onPickImage = { picker.launch(arrayOf("image/*")) }, onRetry = viewModel::retry,
            onCancel = viewModel::cancelSearch, onImport = { viewModel.import(it.anime) }, modifier = Modifier.padding(padding))
    }
    state.duplicate?.let { request ->
        AlertDialog(onDismissRequest = viewModel::dismissDuplicate, title = { Text("资料库中已有这部作品") },
            text = { Text("《${request.existing.title}》已经在你的资料库中，可以直接打开继续记录。") },
            confirmButton = { TextButton(onClick = viewModel::openExistingDuplicate) { Text("打开已有记录") } },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = viewModel::forceImportDuplicate) { Text("仍然添加") }
                    TextButton(onClick = viewModel::dismissDuplicate) { Text("返回结果") }
                }
            })
    }
}

@Composable
internal fun ImageSearchContent(
    state: ImageSearchUiState,
    onPickImage: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onImport: (ImageSearchMatch) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize().testTag("image-search-content"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "source") { ImageSearchSource(state, onPickImage) }
            if (state.imageUri != null && !state.isSearching && !state.hasSearched && state.error == null) item(key = "resume-search") {
                FilledTonalButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("识别这张截图") }
            }
            if (state.isSearching) item(key = "searching") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("正在寻找这一帧…", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("匹配作品、集数与时间点", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = onCancel) { Text("取消") }
                        }
                    }
                }
            }
            state.error?.let { message -> item(key = "error") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("这次没能完成识别", fontWeight = FontWeight.SemiBold)
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onRetry, enabled = !state.isSearching) { Text("重新识别") }
                    }
                }
            } }
            if (state.matches.isNotEmpty()) {
                item(key = "results-heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("匹配结果 · ${state.matches.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("按相似度排序，记得对照一下画面和集数", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                itemsIndexed(state.matches, key = { index, match -> "${match.anime.uniqueKey}:${match.fromSeconds}:$index" }) { index, match ->
                    ImageMatchCard(match, index + 1, importing = state.importingKey == match.anime.uniqueKey,
                        enabled = state.importingKey == null, onImport = { onImport(match) })
                }
            } else if (state.hasSearched && !state.isSearching && state.error == null) item(key = "no-match") {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("还没找到相近的画面", style = MaterialTheme.typography.titleMedium)
                    Text("试试换一张清晰的正片截图，避开大面积字幕、黑边或拼图。", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = onPickImage) { Text("换一张截图试试") }
                }
            }
            if (state.matches.isEmpty() && !state.isSearching && !state.hasSearched) item(key = "tips") {
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("怎样的截图更容易找到？", style = MaterialTheme.typography.titleSmall)
                    ImageSearchTip("01", "用动画正片截图", "保留画面主体，避免海报、同人图和拼图。")
                    ImageSearchTip("02", "选一帧清晰的画面", "尽量避开模糊、过暗和大面积遮挡的场景。")
                    Text("所选截图会发送至 trace.moe 识别，作品资料由 AniList 提供。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ImageSearchSource(state: ImageSearchUiState, onPickImage: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.imageUri == null) {
                Image(painterResource(R.drawable.brand_image_search), null, Modifier.size(176.dp, 112.dp).padding(top = 4.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))
                Text("从这一帧，找到那部番", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("选一张截图，找回作品、集数和那一刻。", textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onPickImage, enabled = !state.isSearching && state.importingKey == null, modifier = Modifier.fillMaxWidth()) {
                    AppIcon(AppIcons.ImageSearch, null, Modifier.size(20.dp))
                    Text("选择动画截图", Modifier.padding(start = 8.dp))
                }
                Text("JPG / PNG / WebP · 最大 20 MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                AsyncImage(state.imageUri, "完整的待识别截图", Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh).testTag("search-source-image"),
                    contentScale = ContentScale.Fit)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("你的截图", style = MaterialTheme.typography.titleSmall)
                        Text(if (state.isSearching) "小喵正在努力辨认" else "换张图片也可以继续找", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onPickImage, enabled = !state.isSearching && state.importingKey == null) { Text("更换图片") }
                }
            }
        }
    }
}

@Composable
private fun ImageSearchTip(number: String, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(number, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 2.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImageMatchCard(match: ImageSearchMatch, rank: Int, importing: Boolean, enabled: Boolean, onImport: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            AsyncImage(match.previewImageUrl ?: match.anime.coverUrl, "《${match.anime.title}》的匹配画面", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Surface(Modifier.align(Alignment.TopStart).padding(10.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), shape = MaterialTheme.shapes.small) {
                Text(if (rank == 1) "最相近的画面" else "候选 $rank", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(match.anime.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            match.anime.originalTitle?.takeIf { it != match.anime.title && it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MatchDetail("相似度 ${String.format(Locale.ROOT, "%.1f", match.similarity.coerceIn(0.0, 1.0) * 100)}%", emphasized = true)
                match.episode?.let { MatchDetail("第 ${formatEpisode(it)} 集") }
                MatchDetail("${formatTimestamp(match.fromSeconds)} – ${formatTimestamp(match.toSeconds)}")
            }
            FilledTonalButton(onClick = onImport, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                if (importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else AppIcon(AppIcons.Add, null, Modifier.size(18.dp))
                Text(if (importing) "正在加入…" else "加入资料库", Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun MatchDetail(text: String, emphasized: Boolean = false) {
    Surface(color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}

internal fun formatEpisode(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)

internal fun formatTimestamp(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60)
}
