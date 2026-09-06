package com.animeow.app.ui.trash

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    viewModel: TrashViewModel = viewModel(),
) {
    val deletedAnimes by viewModel.deletedAnimes.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    var permanentDelete by remember { mutableStateOf<AnimeEntity?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val contentEnter = motionFadeIn(220)
    val contentExit = motionFadeOut(160)
    val busy = operation != null

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbar.showSnackbar(message) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("回收站 (${deletedAnimes.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { confirmEmpty = true },
                        enabled = deletedAnimes.isNotEmpty() && !busy,
                    ) { Text("清空") }
                },
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = deletedAnimes.isEmpty(),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            transitionSpec = { contentEnter togetherWith contentExit },
            label = "trash_content",
        ) { isEmpty ->
            if (isEmpty) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.RestoreFromTrash,
                            contentDescription = null,
                            modifier = Modifier.size(52.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text("回收站是空的", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "删除的作品会保留在这里，直到你永久清理",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(deletedAnimes, key = { it.id }) { anime ->
                        TrashItem(
                            anime = anime,
                            enabled = !busy,
                            busy = operation?.animeId == anime.id,
                            onRestore = { viewModel.restore(anime.id) },
                            onDelete = { permanentDelete = anime },
                        )
                    }
                }
            }
        }
    }

    permanentDelete?.let { anime ->
        AlertDialog(
            onDismissRequest = { if (!busy) permanentDelete = null },
            title = { Text("永久删除《${anime.title}》？") },
            text = { Text("观看记录、标签和角色关联会一并清理，此操作无法撤销。") },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        viewModel.permanentlyDelete(anime.id) { permanentDelete = null }
                    },
                    enabled = !busy,
                ) { Text("永久删除") }
            },
            dismissButton = {
                TextButton(onClick = { permanentDelete = null }, enabled = !busy) { Text("取消") }
            },
        )
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmEmpty = false },
            title = { Text("清空回收站？") },
            text = { Text("将永久删除 ${deletedAnimes.size} 部作品及其关联数据，无法撤销。") },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        viewModel.emptyTrash { confirmEmpty = false }
                    },
                    enabled = !busy,
                ) { Text("全部永久删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmpty = false }, enabled = !busy) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TrashItem(
    anime: AnimeEntity,
    enabled: Boolean,
    busy: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 58.dp, height = 80.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            ) {
                if (!anime.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = anime.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    anime.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "删除于 ${anime.deletedAt.orEmpty().replace('T', ' ').take(16)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text("正在处理", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        TextButton(onClick = onRestore, enabled = enabled) { Text("恢复") }
                        TextButton(onClick = onDelete, enabled = enabled) {
                            Icon(Icons.Outlined.DeleteForever, contentDescription = null)
                            Text("永久删除")
                        }
                    }
                }
            }
        }
    }
}
