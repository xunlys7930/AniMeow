package com.animeow.app.ui.search

import com.animeow.app.ui.icons.AppIcons

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import com.animeow.app.ui.icons.IconGlyph
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
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
import com.animeow.app.data.local.isBookSubjectType
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.RemoteCatalogSource
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.ConfirmationDialog
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut
import com.animeow.app.ui.manual.ManualEntry
import com.animeow.app.ui.manual.manualSections
import com.animeow.app.ui.manual.version218GuideEntries
import java.util.Locale

data class GlobalSearchAction(
    val title: String,
    val subtitle: String,
    val keywords: String,
    val icon: IconGlyph,
    val onClick: (String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GlobalSearchScreen(
    onBack: () -> Unit,
    onAnimeClick: (Long) -> Unit,
    onSeriesClick: (Long) -> Unit,
    onTagClick: (Long) -> Unit,
    onCharacterClick: (Long) -> Unit,
    onGroupClick: (Long) -> Unit,
    onStatusClick: (String) -> Unit,
    onGameClick: (Long) -> Unit,
    onAudioClick: (Long) -> Unit,
    onEpisodeClick: (Long, Int) -> Unit,
    onManualTopicClick: (String) -> Unit,
    onGameDiscoveryClick: (String) -> Unit,
    actions: List<GlobalSearchAction>,
    indexedActions: List<GlobalSearchAction>,
    viewModel: GlobalSearchViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val inputQuery by viewModel.inputQuery.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var confirmClearHistory by rememberSaveable { mutableStateOf(false) }
    val searchTerms = remember(state.query) { SearchTerms(state.query) }
    val allActions = remember(actions, indexedActions) { (actions + indexedActions).distinctBy(GlobalSearchAction::title) }
    val manualTopics = remember { (manualSections().flatMap { it.entries } + version218GuideEntries()).distinctBy(ManualEntry::title) }
    val matchingActions = remember(state.scope, searchTerms, allActions) {
        when {
            !state.scope.includesSettings -> emptyList()
            searchTerms.isEmpty -> if (state.scope == GlobalSearchScope.SETTINGS) actions else actions.take(6)
            else -> allActions.rankedSearch { searchTerms.rank(it.title, it.subtitle, it.keywords) }
        }
    }
    val matchingTopics = remember(state.scope, searchTerms) {
        if (!state.scope.includesSettings || searchTerms.isEmpty) emptyList()
        else manualTopics.rankedSearch { searchTerms.rank(it.title, it.keywords, it.location) }
    }
    val hasAnyResult = state.hasLocalResults || matchingActions.isNotEmpty() || matchingTopics.isNotEmpty() || state.remoteItems.isNotEmpty()
    val seriesSuggestions = remember(state.animes, state.membersBySeriesId) {
        searchSeriesSuggestions(state.animes, state.membersBySeriesId)
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is GlobalSearchEvent.Message -> snackbar.showSnackbar(event.value)
                is GlobalSearchEvent.OpenAnime -> onAnimeClick(event.animeId)
            }
        }
    }

    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(inputQuery, TextRange(inputQuery.length)))
    }
    LaunchedEffect(inputQuery) {
        val current = viewModel.inputQuery.value
        if (current != textFieldValue.text) {
            textFieldValue = TextFieldValue(current, TextRange(current.length))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("全局搜索") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.query.isBlank() && state.recentQueries.isNotEmpty()) {
                        IconButton(onClick = { confirmClearHistory = true }) {
                            Icon(AppIcons.DeleteSweep, contentDescription = "清除最近搜索")
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
            item(key = "search_input") {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        viewModel.setQuery(newValue.text)
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                    trailingIcon = {
                        AnimatedVisibility(
                            visible = textFieldValue.text.isNotBlank(),
                            enter = motionFadeIn(140),
                            exit = motionFadeOut(120),
                        ) {
                            IconButton(onClick = {
                                textFieldValue = TextFieldValue("")
                                viewModel.setQuery("")
                            }) {
                                Icon(AppIcons.Close, contentDescription = "清空搜索")
                            }
                        }
                    },
                    placeholder = { Text(when (state.scope) {
                        GlobalSearchScope.SETTINGS -> "搜索设置、工具或手册"
                        GlobalSearchScope.LOCAL -> "搜索本地作品、分集、标签等"
                        GlobalSearchScope.ONLINE -> "搜索在线动画、书籍与游戏"
                        GlobalSearchScope.ALL -> "搜索作品、角色、设置与手册"
                    }) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        viewModel.rememberCurrentQuery()
                        keyboard?.hide()
                    }),
                    shape = MaterialTheme.shapes.large,
                )
            }
            item(key = "search_scopes") {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlobalSearchScope.entries.forEach { scope ->
                        FilterChip(
                            selected = state.scope == scope,
                            onClick = { viewModel.setScope(scope) },
                            label = { Text(scope.label) },
                        )
                    }
                }
            }

            if (state.query.isBlank()) {
                item {
                    Text(
                        when (state.scope) {
                            GlobalSearchScope.SETTINGS -> "找到设置后，点击即可前往对应位置。"
                            GlobalSearchScope.LOCAL -> "可查找动画、书籍、游戏、音声、分集标题、角色、系列、标签和状态。"
                            GlobalSearchScope.ONLINE -> "输入至少 2 个字符查找在线动画和书籍，也可以前往在线游戏搜索。"
                            GlobalSearchScope.ALL -> "查找资料库、在线作品和应用功能。"
                        },
                        modifier = Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.recentQueries.isNotEmpty()) {
                    item { SearchSectionHeader("最近搜索", state.recentQueries.size) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.recentQueries, key = { it }) { recent ->
                                AssistChip(
                                    onClick = { viewModel.useRecentQuery(recent) },
                                    label = { Text(recent) },
                                    leadingIcon = { Icon(AppIcons.History, contentDescription = null) },
                                )
                            }
                        }
                    }
                }
                if (matchingActions.isNotEmpty()) item { SearchSectionHeader("快捷入口", matchingActions.size) }
                items(matchingActions, key = GlobalSearchAction::title) { action ->
                    SearchActionRow(action) {
                        viewModel.rememberCurrentQuery()
                        action.onClick(state.query)
                    }
                }
            } else {
                if (state.statuses.isNotEmpty()) {
                    item { SearchSectionHeader("收藏状态", state.statuses.size) }
                    items(state.statuses, key = { "status:${it.id}" }) { status ->
                        SearchListItem(status.name, "查看此状态的收藏", { Icon(AppIcons.Tracker, null) }) {
                            viewModel.rememberCurrentQuery()
                            onStatusClick(status.name)
                        }
                    }
                }
                if (state.animes.isNotEmpty()) {
                    item { SearchSectionHeader("本地作品", state.animes.size) }
                    items(state.animes, key = { "anime:${it.id}" }) { anime ->
                        val series = anime.seriesId?.let { state.seriesById[it] }?.takeIf { anime.id in seriesSuggestions }
                        val siblings = seriesSuggestions[anime.id].orEmpty()
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
                if (state.games.isNotEmpty()) {
                    item { SearchSectionHeader("本地游戏", state.games.size) }
                    items(state.games, key = { "game:${it.id}" }) { game ->
                        val detail = listOf(game.status, game.developer, game.originalTitle).filter(String::isNotBlank).joinToString(" · ")
                        SearchListItem(game.title, detail, { Icon(AppIcons.Tracker, null) }) {
                            viewModel.rememberCurrentQuery()
                            onGameClick(game.id)
                        }
                    }
                }
                if (state.audioWorks.isNotEmpty()) {
                    item { SearchSectionHeader("本地音声", state.audioWorks.size) }
                    items(state.audioWorks, key = { "audio:${it.id}" }) { audio ->
                        val detail = listOf(audio.status, audio.workCode, audio.circle, audio.voiceActors).filter(String::isNotBlank).joinToString(" · ")
                        SearchListItem(audio.title, detail, { Icon(AppIcons.Book, null) }) {
                            viewModel.rememberCurrentQuery()
                            onAudioClick(audio.id)
                        }
                    }
                }
                if (state.episodes.isNotEmpty()) {
                    item { SearchSectionHeader("分集标题", state.episodes.size) }
                    items(state.episodes, key = { "episode:${it.episode.animeId}:${it.episode.episode}" }) { result ->
                        SearchListItem(result.episode.title, "${result.workTitle} · 第 ${result.episode.episode} ${result.unit}", { Icon(AppIcons.Book, null) }) {
                            viewModel.rememberCurrentQuery()
                            onEpisodeClick(result.episode.animeId, result.episode.episode)
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
                if (matchingTopics.isNotEmpty()) {
                    item { SearchSectionHeader("使用手册", matchingTopics.size) }
                    items(matchingTopics, key = { "manual:${it.title}" }) { topic ->
                        SearchListItem(topic.title, topic.location, { Icon(AppIcons.Book, null) }) {
                            viewModel.rememberCurrentQuery()
                            onManualTopicClick(topic.title)
                        }
                    }
                }
                if (state.scope.includesOnline) {
                    if (shouldSearchOnline(state.scope, state.query)) {
                        item(key = "search-online-games") {
                            SearchListItem("在线查找游戏", "在发现中搜索「${state.query.trim()}」", { Icon(AppIcons.Discover, null) }) {
                                viewModel.rememberCurrentQuery()
                                onGameDiscoveryClick(state.query)
                            }
                        }
                    } else {
                        item { Text("输入至少 2 个字符以搜索在线作品", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (state.remoteLoading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                    val (books, animation) = state.remoteItems.partition { it.subjectType.isBookSubjectType() }
                    listOf("anime" to animation, "book" to books).forEach { (type, results) ->
                        val title = if (type == "anime") "在线动画" else "在线书籍"
                        if (results.isNotEmpty()) item { SearchSectionHeader(title, results.size) }
                        items(results, key = RemoteAnime::globalSearchKey) { remote ->
                            RemoteSearchRow(
                                remote = remote,
                                alreadyLocal = state.isAlreadyLocal(remote),
                                importing = state.importingRemoteKey == remote.globalSearchKey,
                                onImport = { viewModel.importRemote(remote) },
                            )
                        }
                        if (type in state.remoteHasMore) item(key = "more:$type") {
                            TextButton(onClick = { viewModel.loadMoreOnline(type) }, enabled = type !in state.remoteLoadingTypes) {
                                Text(if (type in state.remoteLoadingTypes) "加载中…" else "加载更多$title")
                            }
                        }
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
                                TextButton(onClick = viewModel::retryOnlineSearch, modifier = Modifier.padding(horizontal = 6.dp)) { Text("重试在线搜索") }
                            }
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
                                Icon(AppIcons.Help, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("没有找到匹配内容", fontWeight = FontWeight.SemiBold)
                                Text("可尝试原始标题、角色中文名或更短的关键词。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClearHistory) {
        ConfirmationDialog(
            title = "清除最近搜索？",
            message = "将删除这台设备上的搜索记录，资料库内容会保留。",
            confirmLabel = "清除记录",
            onConfirm = { confirmClearHistory = false; viewModel.clearRecentQueries() },
            onDismiss = { confirmClearHistory = false },
        )
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
        leading = { Icon(AppIcons.Collection, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
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
                Icon(AppIcons.Tag, contentDescription = null, modifier = Modifier.size(17.dp))
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
                Icon(AppIcons.PersonSearch, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
        leading = { Icon(AppIcons.Group, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
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
        modifier = Modifier.fillMaxWidth().animatedPressClick(
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
                        Icon(AppIcons.CheckCircle, contentDescription = null)
                        Text("已有", modifier = Modifier.padding(start = 4.dp))
                    }
                    else -> {
                        Icon(AppIcons.CloudDownload, contentDescription = null)
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
            Icon(AppIcons.Tracker, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
