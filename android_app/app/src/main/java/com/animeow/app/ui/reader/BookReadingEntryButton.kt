package com.animeow.app.ui.reader

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.isBookSubjectType
import com.animeow.app.data.reader.BookReaderRepository
import com.animeow.app.data.reader.BookReadingStateEntity
import com.animeow.app.data.reader.legado.LegadoLauncher
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.icons.AppIcon as Icon
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.util.userSafeMessage
import kotlinx.coroutines.launch

/**
 * 书籍详情页顶部「本地电子书阅读 & 《开源阅读》同步状态」主操作区组件。
 * 严格仅对 `book` 类型作品渲染，不影响任何动画详情布局。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookReadingEntryButton(
    anime: AnimeEntity,
    onOpenLegadoSync: (() -> Unit)? = null,
) {
    if (!anime.subjectType.isBookSubjectType()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context.applicationContext) { BookReaderRepository(context) }
    val readingState by remember(repository, anime.id) {
        repository.observeState(anime.id)
    }.collectAsStateWithLifecycle(initialValue = null)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { repository.bindLocalEbook(anime.id, uri) }
                    .onSuccess {
                        context.startActivity(BookReaderActivity.intent(context, anime.id))
                    }
                    .onFailure { error ->
                        Toast.makeText(context, error.userSafeMessage("绑定电子书失败"), Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.52f),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val state = readingState
            if (state != null && state.hasLocalBook) {
                val chapterLabel = state.chapterTitle?.takeIf(String::isNotBlank)
                    ?: "第 ${state.chapterIndex + 1} 章"
                val percentLabel = String.format("%.1f%%", state.progressPermille / 10f)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(AppIcons.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state.lastReadAt != null || state.progressPermille > 0) "继续阅读 · $chapterLabel" else "开始阅读 · ${state.fileFormat ?: "电子书"}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${state.fileFormat ?: "本地文件"} · 第 ${state.chapterIndex + 1}/${state.totalChapters.coerceAtLeast(1)} 章（$percentLabel）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(onClick = { context.startActivity(BookReaderActivity.intent(context, anime.id)) }) {
                        Text(if (state.progressPermille > 0) "继续阅读" else "开始阅读")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(AppIcons.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state != null && state.totalChapters > 0) {
                                "云端记录：${state.chapterTitle ?: "第 ${state.chapterIndex + 1} 章"}"
                            } else {
                                "小说阅读与进度同步"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (state != null && state.totalChapters > 0) {
                                "重新选择本机上的 TXT / EPUB 文件即可从原位置继续阅读"
                            } else {
                                "支持打开本机 TXT / EPUB（含轻小说插图）或同步《开源阅读》进度"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { picker.launch(arrayOf("application/epub+zip", "text/plain", "*/*")) }) {
                        Text("选择电子书")
                    }
                }
            }

            if (state != null && state.hasLegadoLink) {
                val legadoChapter = state.legadoChapterTitle?.takeIf(String::isNotBlank)
                    ?: state.legadoChapterIndex?.let { "第 ${it + 1} 章" }
                    ?: "尚未开始阅读"
                val totalSuffix = state.legadoTotalChapters?.takeIf { it > 0 }?.let { "（共 $it 章）" }.orEmpty()
                Text(
                    text = "《开源阅读》进度：$legadoChapter$totalSuffix" +
                        if (!state.syncProgressToMain) " · 已开启卷/章隔离保护" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state?.hasLocalBook == true) {
                    AssistChip(
                        onClick = { picker.launch(arrayOf("application/epub+zip", "text/plain", "*/*")) },
                        leadingIcon = { Icon(AppIcons.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("更换电子书文件") },
                    )
                }
                if (state?.hasLegadoLink == true) {
                    AssistChip(
                        onClick = {
                            LegadoLauncher.openLegado(context, state.legadoBookUrl.orEmpty()).feedback?.let { message ->
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        },
                        leadingIcon = { Icon(AppIcons.OpenExternal, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("打开《开源阅读》继续阅读") },
                    )
                }
                if (onOpenLegadoSync != null) {
                    AssistChip(
                        onClick = onOpenLegadoSync,
                        leadingIcon = { Icon(AppIcons.Sync, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text(if (state?.hasLegadoLink == true) "更新《开源阅读》进度" else "从《开源阅读》同步") },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentBookReadingSheet(
    onDismiss: () -> Unit,
    onOpenAnimeDetail: (Long) -> Unit,
) {
    val context = LocalContext.current
    val db = remember(context.applicationContext) { AniMeowDatabase.getInstance(context) }
    val repository = remember(context.applicationContext) { BookReaderRepository(context, db) }
    val recentStates by remember(repository) { repository.observeRecentStates(30) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val animes by remember(db) { db.libraryDao().observeAnimes() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val animeById = remember(animes) { animes.associateBy(AnimeEntity::id) }

    val entries: List<Pair<AnimeEntity, BookReadingStateEntity>> = remember(recentStates, animeById) {
        recentStates.mapNotNull { st -> animeById[st.animeId]?.let { it to st } }
    }

    StableModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                "最近阅读",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            if (entries.isEmpty()) {
                Text(
                    "暂无本地电子书或《开源阅读》阅读记录。可在书籍详情页绑定 TXT / EPUB，或通过「我的 → 数据与迁移 → 《开源阅读》书架同步」导入进度。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(entries, key = { (anime, _) -> anime.id }) { (anime, st) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    if (st.hasLocalBook) {
                                        context.startActivity(BookReaderActivity.intent(context, anime.id))
                                    } else {
                                        onOpenAnimeDetail(anime.id)
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(AppIcons.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(anime.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val sub = when {
                                    st.hasLocalBook -> "本地 ${st.fileFormat ?: "电子书"} · ${st.chapterTitle ?: "第 ${st.chapterIndex + 1} 章"}"
                                    st.hasLegadoLink -> "《开源阅读》 · ${st.legadoChapterTitle ?: "第 ${(st.legadoChapterIndex ?: 0) + 1} 章"}"
                                    else -> "查看书籍详情"
                                }
                                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                text = if (st.hasLocalBook) "续读" else "详情",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}
