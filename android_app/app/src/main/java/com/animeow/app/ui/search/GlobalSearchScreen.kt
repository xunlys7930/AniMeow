package com.animeow.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.CharacterGroupSummary
import com.animeow.app.data.local.CharacterListItem
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.RemoteCatalogSource
import com.animeow.app.ui.components.motionAnimateContentSize
import java.util.Locale

data class GlobalSearchAction(
    val title: String,
    val subtitle: String,
    val keywords: String,
    val icon: ImageVector,
    val onClick: (String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(
    onBack: () -> Unit,
    onAnimeClick: (Long) -> Unit,
    onSeriesClick: (Long) -> Unit,
    onTagClick: (Long) -> Unit,
    onCharacterClick: (Long) -> Unit,
    onGroupClick: (Long) -> Unit,
    actions: List<GlobalSearchAction>,
    viewModel: GlobalSearchViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }
    val normalizedQuery = normalizeSearchText(state.query)
    val matchingActions = if (normalizedQuery.isBlank()) {
        actions.take(6)
    } else {
        actions.filter { action ->
            matches(normalizedQuery, action.title, action.subtitle, action.keywords)
        }
    }
    val hasAnyResult = state.hasLocalResults || matchingActions.isNotEmpty() || state.remoteItems.isNotEmpty()

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is GlobalSearchEvent.Message -> snackbar.showSnackbar(event.value)
                is GlobalSearchEvent.OpenAnime -> onAnimeClick(event.animeId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("全局搜索") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.query.isBlank() && state.recentQueries.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearRecentQueries) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = "清除最近搜索")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = if (state.query.isNotBlank()) {
                        {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Outlined.Close, contentDescription = "清空搜索")
                            }
                        }
                    } else {
                        null
                    },
                    placeholder = { Text("搜索作品、系列、标签、角色、群组或设置") },
                    supportingText = {
                        Text(if (state.query.trim().length < 2) "输入至少 2 个字符后补充在线结果" else "本地结果即时显示，在线结果异步补充")
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )
            }

            if (state.query.isBlank()) {
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("一个入口，分组呈现", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                "本地资料优先；网络同名结果不会与本地条目混在一起。",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
                if (state.recentQueries.isNotEmpty()) {
                    item { SearchSectionHeader("最近搜索", state.recentQueries.size) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.recentQueries, key = { it }) { recent ->
                                AssistChip(
                                    onClick = { viewModel.useRecentQuery(recent) },
                                    label = { Text(recent) },
                                    leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                                )
                            }
                        }
                    }
                }
                item { SearchSectionHeader("快捷入口", matchingActions.size) }
                items(matchingActions, key = GlobalSearchAction::title) { action ->
                    SearchActionRow(action) {
                        viewModel.rememberCurrentQuery()
                        action.onClick(state.query)
                    }
                }
            } else {
                if (state.animes.isNotEmpty()) {
                    item { SearchSectionHeader("本地作品", state.animes.size) }
                    items(state.animes, key = { "anime:${it.id}" }) { anime ->
                        val series = anime.seriesId?.let { state.seriesById[it] }
                        val siblings = anime.seriesId?.let { state.membersBySeriesId[it] }?.filter { it.id != anime.id }.orEmpty()
                        AnimeSearchRow(
                            anime = anime,
                            seriesName = series?.name,
                            siblings = siblings,
                            onSeriesClick = series?.let { s -> { onSeriesClick(s.id) } },
                            onAnimeClick = {
                                viewModel.rememberCurrentQuery()
                                onAnimeClick(anime.id)
                            },
                        )
                    }
                }
                if (state.series.isNotEmpty()) {
                    item { SearchSectionHeader("系列", state.series.size) }
                    items(state.series, key = { "series:${it.id}" }) { series ->
                        SeriesSearchRow(series) {
                            viewModel.rememberCurrentQuery()
                            onSeriesClick(series.id)
                        }
                    }
                }
                if (state.tags.isNotEmpty()) {
                    item { SearchSectionHeader("标签", state.tags.size) }
                    items(state.tags, key = { "tag:${it.id}" }) { tag ->
                        TagSearchRow(tag) {
                            viewModel.rememberCurrentQuery()
                            onTagClick(tag.id)
                        }
                    }
                }
                if (state.characters.isNotEmpty()) {
                    item { SearchSectionHeader("角色", state.characters.size) }
                    items(state.characters, key = { "character:${it.character.id}" }) { character ->
                        CharacterSearchRow(character) {
                            viewModel.rememberCurrentQuery()
                            onCharacterClick(character.character.id)
                        }
                    }
                }
                if (state.groups.isNotEmpty()) {
                    item { SearchSectionHeader("角色组", state.groups.size) }
                    items(state.groups, key = { "group:${it.group.id}" }) { group ->
                        GroupSearchRow(group) {
                            viewModel.rememberCurrentQuery()
                            onGroupClick(group.group.id)
                        }
                    }
                }
                if (matchingActions.isNotEmpty()) {
                    item { SearchSectionHeader("设置与工具", matchingActions.size) }
                    items(matchingActions, key = GlobalSearchAction::title) { action ->
                        SearchActionRow(action) {
                            viewModel.rememberCurrentQuery()
                            action.onClick(state.query)
                        }
                    }
                }
                item { SearchSectionHeader("在线作品", state.remoteItems.size) }
                if (state.remoteLoading) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }
                items(state.remoteItems, key = RemoteAnime::uniqueKey) { remote ->
                    RemoteSearchRow(
                        remote = remote,
                        alreadyLocal = state.isAlreadyLocal(remote),
                        importing = state.importingRemoteKey == remote.uniqueKey,
                        onImport = { viewModel.importRemote(remote) },
                    )
                }
                state.remoteWarnings.forEachIndexed { index, warning ->
                    item("warning:$index") {
                        Text(warning, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.remoteError?.let { error ->
                    item {
                        ElevatedCard(modifier = Modifier.fillMaxWidth().motionAnimateContentSize()) {
                            Text(error, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (!hasAnyResult && !state.remoteLoading && state.remoteError == null) {
                    item {
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("没有找到匹配内容", fontWeight = FontWeight.SemiBold)
                                Text("可尝试原始标题、角色中文名或更短的关键词。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(count.toString(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeSearchRow(
    anime: AnimeEntity,
    seriesName: String? = null,
    siblings: List<AnimeEntity> = emptyList(),
    onSeriesClick: (() -> Unit)? = null,
    onAnimeClick: (Long) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SearchListItem(
                headline = anime.title,
                supporting = listOfNotNull(anime.originalTitle, anime.studio, "${anime.status} · ${anime.watchedEpisodes}/${anime.totalEpisodes.takeIf { it > 0 } ?: "?"}")
                    .joinToString(" · "),
                leading = {
                    SearchCover(anime.coverUrl, anime.title)
                },
                onClick = { onAnimeClick(anime.id) },
            )
            if (!seriesName.isNullOrBlank() && onSeriesClick != null) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "同系列 · $seriesName",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable(onClick = onSeriesClick)
                            .padding(vertical = 2.dp, horizontal = 4.dp),
                    )
                    if (siblings.isNotEmpty()) {
                        Text(":", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        siblings.forEach { sibling ->
                            Text(
                                text = sibling.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable { onAnimeClick(sibling.id) }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesSearchRow(series: SeriesEntity, onClick: () -> Unit) {
    SearchListItem(
        headline = series.name,
        supporting = series.description ?: "打开系列详情与成员作品",
        leading = { Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        onClick = onClick,
    )
}

@Composable
private fun TagSearchRow(tag: TagEntity, onClick: () -> Unit) {
    SearchListItem(
        headline = tag.name,
        supporting = "查看使用此标签的作品",
        leading = {
            Box(
                modifier = Modifier.size(30.dp).clip(CircleShape)
                    .background(tag.color?.let(::Color) ?: MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = null, modifier = Modifier.size(17.dp))
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun CharacterSearchRow(item: CharacterListItem, onClick: () -> Unit) {
    SearchListItem(
        headline = item.character.nameCn?.takeIf(String::isNotBlank) ?: item.character.name,
        supporting = "${item.workCount} 部作品 · ${item.relationCount} 条关系 · ${item.tagCount} 个标签",
        leading = {
            if (item.character.imageUrl.isNullOrBlank()) {
                Icon(Icons.Outlined.PersonSearch, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            } else {
                AsyncImage(
                    model = item.character.imageUrl,
                    contentDescription = item.character.name,
                    modifier = Modifier.size(46.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun GroupSearchRow(item: CharacterGroupSummary, onClick: () -> Unit) {
    SearchListItem(
        headline = item.group.name,
        supporting = "${item.characterCount} 个角色 · ${item.workCount} 部作品${item.group.shareCode?.let { " · 分享码 $it" }.orEmpty()}",
        leading = { Icon(Icons.Outlined.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        onClick = onClick,
    )
}

@Composable
private fun SearchActionRow(action: GlobalSearchAction, onClick: () -> Unit) {
    SearchListItem(
        headline = action.title,
        supporting = action.subtitle,
        leading = { Icon(action.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        onClick = onClick,
    )
}

@Composable
private fun SearchListItem(
    headline: String,
    supporting: String,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(
            role = Role.Button,
            onClickLabel = "打开$headline",
            onClick = onClick,
        ),
    ) {
        ListItem(
            headlineContent = { Text(headline, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(supporting, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = leading,
        )
    }
}

@Composable
private fun RemoteSearchRow(
    remote: RemoteAnime,
    alreadyLocal: Boolean,
    importing: Boolean,
    onImport: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SearchCover(remote.coverUrl, remote.title)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(remote.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(remote.source.displayName)
                        remote.score?.let { score ->
                            val normalized = if (remote.source == RemoteCatalogSource.BANGUMI) score else if (score > 10) score / 10 else score
                            append(" · ").append(String.format(Locale.ROOT, "%.1f", normalized))
                        }
                        remote.airDate?.take(4)?.let { append(" · ").append(it) }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FilledTonalButton(onClick = onImport, enabled = !alreadyLocal && !importing) {
                when {
                    importing -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    alreadyLocal -> {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                        Text("已有", modifier = Modifier.padding(start = 4.dp))
                    }
                    else -> {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                        Text("加入", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchCover(url: String?, title: String) {
    Box(
        modifier = Modifier.width(46.dp).height(62.dp).clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Icon(Icons.Outlined.MovieFilter, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        } else {
            AsyncImage(
                model = url,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
