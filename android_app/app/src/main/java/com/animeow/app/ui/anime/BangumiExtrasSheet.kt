package com.animeow.app.ui.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.animeow.app.data.remote.*
import com.animeow.app.ui.components.StableModalBottomSheet
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class BangumiExtrasUiState(
    val loading: Boolean = false, val error: String? = null,
    val rating: RemoteAnime? = null, val staff: BangumiStaffPage? = null,
    val discussions: Map<String, BangumiDiscussionPage> = emptyMap(),
)

private val CORE_STAFF_JOB_PRIORITY = listOf(
    "原作", "总导演", "总监督", "导演", "监督", "系列构成", "脚本",
    "人物原案", "人物设定", "角色设计", "总作画监督", "音乐", "动画制作",
)

internal data class MergedBangumiPerson(
    val id: Long,
    val name: String,
    val job: String,
    val episodes: String,
    val corePriority: Int,
) {
    val isCoreStaff: Boolean get() = corePriority < CORE_STAFF_JOB_PRIORITY.size
}

internal fun mergeAndSortBangumiStaff(people: List<BangumiPerson>): List<MergedBangumiPerson> {
    if (people.isEmpty()) return emptyList()
    data class Acc(
        val id: Long,
        val name: String,
        val jobs: LinkedHashSet<String> = linkedSetOf(),
        val episodes: LinkedHashSet<String> = linkedSetOf(),
        val firstIndex: Int,
    )
    val grouped = linkedMapOf<String, Acc>()
    people.forEachIndexed { index, person ->
        val groupKey = if (person.id > 0L) "id:${person.id}" else "idx:$index:${person.name}"
        val acc = grouped.getOrPut(groupKey) { Acc(person.id, person.name, firstIndex = index) }
        person.job.split("／", "/").map(String::trim).filter(String::isNotBlank).forEach(acc.jobs::add)
        person.episodes.trim().takeIf(String::isNotBlank)?.let(acc.episodes::add)
    }
    return grouped.values.map { acc ->
        val mergedJob = acc.jobs.joinToString(" ／ ")
        val priority = CORE_STAFF_JOB_PRIORITY.indexOfFirst { core ->
            acc.jobs.any { it == core || it.contains(core) }
        }.let { if (it >= 0) it else CORE_STAFF_JOB_PRIORITY.size }
        MergedBangumiPerson(
            id = acc.id,
            name = acc.name,
            job = mergedJob,
            episodes = acc.episodes.joinToString("、"),
            corePriority = priority,
        ) to acc.firstIndex
    }.sortedWith(compareBy({ it.first.corePriority }, { it.second })).map { it.first }
}

internal fun formatBangumiCacheTimeLocal(raw: String): String {
    val parsed = runCatching { Instant.parse(raw.trim()) }.getOrNull()
    if (parsed != null) {
        return parsed.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }
    return raw.take(19).replace('T', ' ')
}

@Composable
internal fun BangumiExtrasEntry(subjectId: Long?, title: String, onRating: suspend (RemoteAnime) -> Unit = {}) {
    if (subjectId == null) return
    var open by rememberSaveable(subjectId) { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("Bangumi 评分、制作人员与评论") }
    if (open) BangumiExtrasSheet(subjectId, title, onRating) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BangumiExtrasSheet(subjectId: Long, title: String, onRating: suspend (RemoteAnime) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val service = remember(context) { BangumiService(context = context) }
    val discussion = remember(context) { BangumiDiscussionService(context) }
    var tab by rememberSaveable(subjectId) { mutableIntStateOf(0) }
    var commentsPage by rememberSaveable(subjectId) { mutableIntStateOf(1) }
    var reviewsPage by rememberSaveable(subjectId) { mutableIntStateOf(1) }
    var retry by remember { mutableIntStateOf(0) }
    var state by remember(subjectId) { mutableStateOf(BangumiExtrasUiState()) }
    val loadedRetryByKey = remember(subjectId) { mutableStateMapOf<String, Int>() }
    val saveRating by rememberUpdatedState(onRating)
    val activePage = if (tab == 2) commentsPage else if (tab == 3) reviewsPage else 1
    LaunchedEffect(subjectId, tab, activePage, retry) {
        val cacheKey = when (tab) {
            0 -> "rating"
            1 -> "staff"
            2 -> "comments:$activePage"
            else -> "reviews:$activePage"
        }
        if (loadedRetryByKey[cacheKey] == retry) {
            if (tab >= 2) {
                val kind = if (tab == 2) "comments" else "reviews"
                state.discussions[cacheKey]?.let { cached ->
                    state = state.copy(loading = false, error = null, discussions = state.discussions + (kind to cached))
                }
            } else {
                state = state.copy(loading = false, error = null)
            }
            return@LaunchedEffect
        }
        state = state.copy(loading = true, error = null)
        try {
            state = when (tab) {
                0 -> {
                    val rating = requireNotNull(service.subject(subjectId))
                    saveRating(rating)
                    loadedRetryByKey[cacheKey] = retry
                    state.copy(rating = rating)
                }
                1 -> {
                    val staffPage = service.subjectPersons(subjectId)
                    loadedRetryByKey[cacheKey] = retry
                    state.copy(staff = staffPage)
                }
                else -> {
                    val kind = if (tab == 2) "comments" else "reviews"
                    val fetched = discussion.page(subjectId, kind, activePage)
                    loadedRetryByKey[cacheKey] = retry
                    state.copy(discussions = state.discussions + (cacheKey to fetched) + (kind to fetched))
                }
            }.copy(loading = false)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            state = state.copy(loading = false, error = "暂时无法读取 Bangumi 资料，请检查网络或稍后重试。已有内容会保留。")
        }
    }
    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        BangumiExtrasContent(
            subjectId = subjectId,
            title = title,
            tab = tab,
            state = state,
            onTab = { tab = it },
            onPage = { nextPage ->
                if (tab == 2) commentsPage = nextPage else if (tab == 3) reviewsPage = nextPage
            },
            onRetry = { retry++ },
            requestedPage = activePage,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BangumiExtrasContent(subjectId: Long, title: String, tab: Int, state: BangumiExtrasUiState,
    onTab: (Int) -> Unit, onPage: (Int) -> Unit, onRetry: () -> Unit, requestedPage: Int? = null) {
    val uri = LocalUriHandler.current
    var peopleQuery by rememberSaveable { mutableStateOf("") }
    val kind = if (tab == 2) "comments" else "reviews"
    val discussion = (requestedPage?.let { state.discussions["$kind:$it"] }) ?: state.discussions[kind]
    val config = LocalConfiguration.current
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = (config.screenHeightDp * 0.85f).dp).navigationBarsPadding(),
        contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Bangumi 公开资料 · 评分人数与个人评分分别展示", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "评分", 1 to "制作人员", 2 to "短评", 3 to "长评").forEach { (index, label) ->
                    FilterChip(tab == index, { onTab(index) }, label = { Text(label) })
                }
            }
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取…") }
        state.error?.let { message -> item {
            Text(message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry, enabled = !state.loading) { Text("重试") }
        } }
        when (tab) {
            0 -> item {
                val rating = state.rating
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    rating?.score?.let { "Bangumi 评分 ${"%.1f".format(java.util.Locale.ROOT, it)} / 10" } ?: "暂无来源评分",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(rating?.ratingCountLabel() ?: "评分人数暂无", style = MaterialTheme.typography.bodyMedium)
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    rating?.sourceRank?.let { "Bangumi 综合排名 #$it" } ?: "暂无有效综合排名",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                        val distribution = rating?.ratingDistribution.orEmpty()
                        val maxVotes = distribution.values.maxOrNull()?.takeIf { it > 0 }
                        if (maxVotes != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Text("各分段投票分布", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                for (score in 10 downTo 1) {
                                    val count = distribution[score] ?: 0
                                    val fraction = (count.toFloat() / maxVotes.toFloat()).coerceIn(0f, 1f)
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("${score}分", Modifier.width(34.dp), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
                                        Box(
                                            Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                        ) {
                                            if (fraction > 0f) {
                                                Box(
                                                    Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                                                        .background(MaterialTheme.colorScheme.primary),
                                                )
                                            }
                                        }
                                        Text("$count", Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                                    }
                                }
                            }
                        }
                        rating?.cacheUpdatedAt?.let {
                            Text("资料更新时间：${formatBangumiCacheTimeLocal(it)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (rating?.cacheStale == true) Text("暂时无法更新，显示服务器保留的资料", color = MaterialTheme.colorScheme.error)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("来源可能存在整理延迟；少量评分不等于可靠综合排名。", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = onRetry, enabled = !state.loading) { Text("重新读取评分") }
                        }
                    }
                }
            }
            1 -> {
                val mergedAll = mergeAndSortBangumiStaff(state.staff?.people.orEmpty())
                item {
                    OutlinedTextField(peopleQuery, { peopleQuery = it }, Modifier.fillMaxWidth(), label = { Text("查找人员或职位") }, singleLine = true)
                    if (state.staff?.stale == true) Text("显示服务器保留的制作人员资料", color = MaterialTheme.colorScheme.error)
                    state.staff?.let {
                        Text("共 ${mergedAll.size} 位制作人员（原始 ${it.people.size} 条职位记录）",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val people = mergedAll.filter {
                    peopleQuery.isBlank() || it.name.contains(peopleQuery.trim(), true) || it.job.contains(peopleQuery.trim(), true)
                }
                if (!state.loading && state.staff != null && people.isEmpty()) item { Text(if (peopleQuery.isBlank()) "来源未提供制作人员" else "没有匹配的人员或职位") }
                items(people, key = { "${it.id}:${it.name}:${it.job}" }) { person ->
                    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(person.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (person.isCoreStaff) {
                                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("核心主创", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                        Text(person.job.ifBlank { "职位未提供" }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        person.episodes.takeIf(String::isNotBlank)?.let { Text("参与集数：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (person.id > 0L) {
                            TextButton(onClick = { runCatching { uri.openUri("https://bgm.tv/person/${person.id}") } }, contentPadding = PaddingValues(0.dp)) {
                                Text("查看 Bangumi 人物资料")
                            }
                        }
                    } }
                }
            }
            else -> {
                item {
                    Text(if (tab == 2) "公开短评，可能含剧透。用户评分与条目综合分数不同。" else "公开长评的标题与摘要，完整文章在 Bangumi 原文阅读。可能含剧透。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    discussion?.let { Text("第 ${it.page} 页 · 来源共 ${it.total} 条${if (it.stale) " · 显示保留内容" else ""}") }
                }
                if (!state.loading && discussion != null && discussion.comments.isEmpty()) item { Text("本页没有公开${if (tab == 2) "短评" else "长评"}") }
                items(discussion?.comments.orEmpty(), key = BangumiComment::id) { comment ->
                    var expanded by rememberSaveable(comment.id) { mutableStateOf(false) }
                    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(comment.author, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            comment.rate?.let { score ->
                                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text("用户评分 $score / 10", Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                        }
                        comment.date?.let { seconds -> runCatching { Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrNull() }
                            ?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (comment.title.isNotBlank()) Text(comment.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(comment.text.ifBlank { "此条目没有文字内容" }, maxLines = if (expanded) Int.MAX_VALUE else 8)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            if (comment.text.length > 240 || comment.text.count { it == '\n' } > 7) {
                                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起" else "展开文字") }
                            } else {
                                Spacer(Modifier.width(1.dp))
                            }
                            TextButton(onClick = { runCatching { uri.openUri(comment.url) } }, contentPadding = PaddingValues(0.dp)) {
                                Text(if (tab == 3) "阅读 Bangumi 原文" else "打开 Bangumi 短评页")
                            }
                        }
                    } }
                }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { onPage((discussion?.page ?: 1) - 1) }, enabled = !state.loading && (discussion?.page ?: 1) > 1) { Text("上一页") }
                    TextButton(onClick = { onPage((discussion?.page ?: 1) + 1) }, enabled = !state.loading && discussion?.hasMore == true && discussion.page < 501) { Text("下一页") }
                } }
                if (discussion != null && discussion.page >= 501 && discussion.hasMore) item { Text("当前最多浏览前 10020 条，更多内容请打开 Bangumi 查看。", style = MaterialTheme.typography.bodySmall) }
            }
        }
        item { TextButton(onClick = { runCatching { uri.openUri("https://bgm.tv/subject/$subjectId") } }) { Text("打开 Bangumi 作品页") } }
    }
}
