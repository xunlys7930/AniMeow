package com.animeow.app.ui.discovery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.media.copyUriToCacheFile
import com.animeow.app.data.remote.ImageSearchMatch
import com.animeow.app.util.runCatchingCancellable
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageSearchScreen(
    onBack: () -> Unit,
    onAnimeImported: (Long) -> Unit,
    viewModel: ImageSearchViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val cached = runCatchingCancellable { copyUriToCacheFile(context, uri, "search_img") }.getOrNull() ?: uri
                viewModel.search(cached)
            }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.openAnime.collect(onAnimeImported)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("以图搜番") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.imageUri != null) {
                            AsyncImage(
                                model = state.imageUri,
                                contentDescription = "待识别截图",
                                modifier = Modifier.fillMaxWidth().height(190.dp).clip(MaterialTheme.shapes.large),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(Icons.Outlined.ImageSearch, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("选择动画截图", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("trace.moe 会匹配作品、集数与大致时间点", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        FilledTonalButton(
                            onClick = { picker.launch(arrayOf("image/*")) },
                            enabled = !state.isSearching,
                        ) { Text(if (state.imageUri == null) "选择图片" else "更换图片") }
                    }
                }
            }
            if (state.isSearching) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("正在识别截图并补全 AniList 资料…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.error?.let { message ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::retry) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                            Text("重试")
                        }
                    }
                }
            }
            if (state.matches.isNotEmpty()) {
                item {
                    Text("候选结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                items(state.matches, key = { "${it.anime.uniqueKey}:${it.fromSeconds}" }) { match ->
                    ImageMatchCard(
                        match = match,
                        importing = state.importingKey == match.anime.uniqueKey,
                        onImport = { viewModel.import(match.anime) },
                    )
                }
            }
        }
    }

    state.duplicate?.let { request ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDuplicate,
            title = { Text("资料库中已有匹配作品") },
            text = { Text("已存在《${request.existing.title}》，请选择打开已有记录或仍然添加。") },
            confirmButton = {
                FilledTonalButton(onClick = viewModel::openExistingDuplicate) { Text("打开已有记录") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::forceImportDuplicate) { Text("仍然添加") }
                    TextButton(onClick = viewModel::dismissDuplicate) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun ImageMatchCard(
    match: ImageSearchMatch,
    importing: Boolean,
    onImport: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(width = 112.dp, height = 76.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = match.previewImageUrl ?: match.anime.coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(match.anime.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append("相似度 ${(match.similarity * 100).toInt()}%")
                        match.episode?.let { append(" · 第 ${formatEpisode(it)} 集") }
                    },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    "${formatTimestamp(match.fromSeconds)} – ${formatTimestamp(match.toSeconds)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = onImport, enabled = !importing) {
                if (importing) CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Add, contentDescription = "加入资料库")
            }
        }
    }
}

private fun formatEpisode(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

private fun formatTimestamp(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
