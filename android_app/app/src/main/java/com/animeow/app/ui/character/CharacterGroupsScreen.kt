package com.animeow.app.ui.character

import android.content.ClipData

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.CharacterEntity
import com.animeow.app.data.local.CharacterGroupEntity
import com.animeow.app.data.local.CharacterGroupMember
import com.animeow.app.data.local.CharacterGroupSummary
import com.animeow.app.data.local.CharacterGroupWork
import com.animeow.app.data.local.CharacterListItem
import com.animeow.app.data.local.subjectTypeDisplayName
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.CharacterImageAlignment
import kotlinx.coroutines.launch

private fun CharacterImageAlignment.toAlignment(): Alignment = when (this) {
    CharacterImageAlignment.TOP -> Alignment.TopCenter
    CharacterImageAlignment.CENTER -> Alignment.Center
    CharacterImageAlignment.BOTTOM -> Alignment.BottomCenter
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterGroupsScreen(
    settings: AppearanceSettings,
    onBack: () -> Unit,
    onCommunityRequested: () -> Unit,
    modifier: Modifier = Modifier,
    initialGroupId: Long? = null,
    viewModel: CharacterManagementViewModel = viewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val characters by viewModel.allCharacterItems.collectAsStateWithLifecycle()
    val animes by viewModel.allAnimes.collectAsStateWithLifecycle()
    val selectedGroup by viewModel.selectedGroup.collectAsStateWithLifecycle()
    val members by viewModel.selectedGroupMembers.collectAsStateWithLifecycle()
    val works by viewModel.selectedGroupWorks.collectAsStateWithLifecycle()
    val publishing by viewModel.groupPublishing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showDetail by rememberSaveable { mutableStateOf(false) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingExisting by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showPublish by rememberSaveable { mutableStateOf(false) }
    var publishPublic by rememberSaveable { mutableStateOf(true) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importGroup)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val groupId = selectedGroup?.id
        if (uri != null && groupId != null) viewModel.exportGroup(uri, groupId)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshCloudSession()
        viewModel.events.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(initialGroupId) {
        initialGroupId?.let { groupId ->
            viewModel.selectGroup(groupId)
            showDetail = true
        }
    }
    BackHandler(showDetail) {
        showDetail = false
        viewModel.selectGroup(null)
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (showDetail) selectedGroup?.name ?: "角色组详情" else "角色组",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = if (showDetail) {
                        {
                            showDetail = false
                            viewModel.selectGroup(null)
                        }
                    } else onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (showDetail && selectedGroup != null) {
                        IconButton(onClick = {
                            publishPublic = selectedGroup!!.communityId.isNullOrBlank() || selectedGroup!!.isPublic
                            showPublish = true
                            viewModel.refreshCloudSession()
                        }) {
                            Icon(
                                if (selectedGroup!!.communityId.isNullOrBlank()) Icons.Outlined.CloudUpload else Icons.Outlined.CloudDone,
                                contentDescription = "发布与分享角色组",
                            )
                        }
                        IconButton(onClick = {
                            exportLauncher.launch(
                                "${selectedGroup!!.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.animeow-group.json",
                            )
                        }) { Icon(Icons.Outlined.FileDownload, contentDescription = "导出角色组") }
                        IconButton(onClick = {
                            editingExisting = true
                            showEditor = true
                        }) { Icon(Icons.Outlined.Edit, contentDescription = "编辑角色组") }
                        IconButton(onClick = { showDelete = true }) {
                            Icon(Icons.Outlined.DeleteForever, contentDescription = "删除角色组")
                        }
                    } else if (!showDetail) {
                        IconButton(onClick = onCommunityRequested) {
                            Icon(Icons.Outlined.Public, contentDescription = "角色组社区")
                        }
                        IconButton(onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
                        }) { Icon(Icons.Outlined.FileUpload, contentDescription = "导入角色组") }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!showDetail) {
                FloatingActionButton(onClick = {
                    editingExisting = false
                    showEditor = true
                }) { Icon(Icons.Outlined.Add, contentDescription = "新建角色组") }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = showDetail,
            modifier = Modifier.fillMaxSize().padding(padding),
            transitionSpec = {
                val duration = (240 * settings.motionLevel.durationScale).toInt()
                if (targetState) {
                    (fadeIn(tween(duration)) + slideInHorizontally(tween(duration)) { it / 8 })
                        .togetherWith(fadeOut(tween(duration)))
                } else {
                    fadeIn(tween(duration)).togetherWith(
                        fadeOut(tween(duration)) + slideOutHorizontally(tween(duration)) { it / 8 },
                    )
                }
            },
            label = "character_group_detail",
        ) { detail ->
            if (detail) {
                CharacterGroupDetail(
                    group = selectedGroup,
                    members = members,
                    works = works,
                    imageAlignment = settings.characterImageAlignment,
                )
            } else {
                CharacterGroupList(
                    groups = groups,
                    onOpen = { groupId ->
                        viewModel.selectGroup(groupId)
                        showDetail = true
                    },
                )
            }
        }
    }

    if (showEditor) {
        CharacterGroupEditorDialog(
            existing = selectedGroup.takeIf { editingExisting },
            allCharacters = characters,
            allAnimes = animes,
            selectedCharacterIds = if (editingExisting) members.map { it.character.id }.toSet() else emptySet(),
            selectedAnimeIds = if (editingExisting) works.map { it.anime.id }.toSet() else emptySet(),
            onSave = { existing, name, description, characterIds, animeIds ->
                viewModel.saveGroup(existing, name, description, characterIds, animeIds)
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }
    if (showDelete && selectedGroup != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除角色组？") },
            text = {
                Text(
                    "只会删除「${selectedGroup!!.name}」的本机分组，不会删除角色或作品资料。" +
                        if (selectedGroup!!.communityId.isNullOrBlank()) "" else " 云端版本会继续保留，可先在发布管理中删除。",
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteGroup(selectedGroup!!.id)
                    showDelete = false
                    showDetail = false
                    viewModel.selectGroup(null)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } },
        )
    }
    if (showPublish && selectedGroup != null) {
        CharacterGroupPublishDialog(
            group = selectedGroup!!,
            characterCount = members.size,
            workCount = works.size,
            loggedIn = publishing.session != null,
            busy = publishing.busyGroupId == selectedGroup!!.id,
            ownsRemote = selectedGroup!!.communityId?.let(publishing.ownedCommunityIds::contains) == true,
            ownershipLoading = publishing.ownershipLoading,
            isPublic = publishPublic,
            onPublicChange = { publishPublic = it },
            onCopyShareCode = {
                selectedGroup!!.shareCode?.let { shareCode ->
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("角色组分享码", shareCode)),
                        )
                    }
                }
            },
            onPublish = {
                viewModel.publishGroup(selectedGroup!!.id, publishPublic)
                showPublish = false
            },
            onDeleteRemote = {
                viewModel.deletePublishedGroup(selectedGroup!!.id)
                showPublish = false
            },
            onDismiss = { showPublish = false },
        )
    }
}

@Composable
private fun CharacterGroupList(
    groups: List<CharacterGroupSummary>,
    onOpen: (Long) -> Unit,
) {
    if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Groups, contentDescription = null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text("还没有角色组", style = MaterialTheme.typography.titleLarge)
                Text("把角色和作品整理成收藏主题。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(groups, key = { it.group.id }) { summary ->
            ElevatedCard(
                Modifier.fillMaxWidth().animatedPressClick(
                    onClickLabel = "查看${summary.group.name}角色组详情",
                ) { onOpen(summary.group.id) },
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(72.dp).clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!summary.group.coverUrl.isNullOrBlank()) {
                            AsyncImage(
                                summary.group.coverUrl,
                                summary.group.name,
                                Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else Icon(Icons.Outlined.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                summary.group.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (!summary.group.communityId.isNullOrBlank()) {
                                Icon(
                                    Icons.Outlined.CloudDone,
                                    contentDescription = if (summary.group.isPublic) "已公开" else "仅分享码可见",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        summary.group.description?.let {
                            Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "${summary.characterCount} 个角色 · ${summary.workCount} 部作品",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterGroupDetail(
    group: CharacterGroupEntity?,
    members: List<CharacterGroupMember>,
    works: List<CharacterGroupWork>,
    imageAlignment: CharacterImageAlignment = CharacterImageAlignment.TOP,
) {
    if (group == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("正在加载角色组…") }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(18.dp, 14.dp, 18.dp, 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ElevatedCard(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(group.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    group.description?.let { Text(it) }
                    Text("${members.size} 个角色 · ${works.size} 部作品", color = MaterialTheme.colorScheme.primary)
                    if (!group.communityId.isNullOrBlank()) {
                        Text(
                            if (group.isPublic) "已发布到社区" else "云端私密分享",
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        group.shareCode?.let { Text("分享码：$it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        item { SectionTitle(Icons.Outlined.People, "角色成员") }
        if (members.isEmpty()) item { Text("还没有角色成员", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(members, key = { it.character.id }) { member -> GroupCharacterCard(member.character, imageAlignment = imageAlignment) }
            }
        }
        item { SectionTitle(Icons.Outlined.Movie, "关联作品") }
        if (works.isEmpty()) item { Text("还没有关联作品", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else items(works, key = { it.anime.id }) { work -> GroupWorkCard(work.anime) }
    }
}

@Composable
private fun SectionTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GroupCharacterCard(character: CharacterEntity, imageAlignment: CharacterImageAlignment = CharacterImageAlignment.TOP) {
    ElevatedCard(Modifier.width(130.dp), shape = MaterialTheme.shapes.large) {
        Column {
            Box(Modifier.fillMaxWidth().height(150.dp).background(MaterialTheme.colorScheme.secondaryContainer)) {
                character.imageUrl?.let {
                    AsyncImage(it, character.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = imageAlignment.toAlignment())
                }
            }
            Text(
                character.nameCn?.takeIf(String::isNotBlank) ?: character.name,
                Modifier.padding(10.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun GroupWorkCard(anime: AnimeEntity) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(width = 54.dp, height = 76.dp).clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            ) {
                anime.coverUrl?.let { AsyncImage(it, anime.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.weight(1f)) {
                Text(anime.title, fontWeight = FontWeight.SemiBold)
                Text(subjectTypeDisplayName(anime.subjectType), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CharacterGroupPublishDialog(
    group: CharacterGroupEntity,
    characterCount: Int,
    workCount: Int,
    loggedIn: Boolean,
    busy: Boolean,
    ownsRemote: Boolean,
    ownershipLoading: Boolean,
    isPublic: Boolean,
    onPublicChange: (Boolean) -> Unit,
    onCopyShareCode: () -> Unit,
    onPublish: () -> Unit,
    onDeleteRemote: () -> Unit,
    onDismiss: () -> Unit,
) {
    val published = !group.communityId.isNullOrBlank()
    val canManageRemote = published && ownsRemote
    val validPackage = characterCount >= 2 && workCount >= 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    canManageRemote -> "管理云端角色组"
                    published -> "发布为我的副本"
                    else -> "发布角色组"
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${group.name} · $characterCount 个角色 · $workCount 部作品")
                if (!loggedIn) {
                    Text("请先在“云账号与设备备份”中登录，登录后即可发布、更新或删除自己的角色组。", color = MaterialTheme.colorScheme.error)
                } else if (published && ownershipLoading) {
                    Text("正在确认当前账号的发布权限…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (published && !ownsRemote) {
                    Text(
                        "这是从社区导入的收藏，原作者的云端版本不会被修改；发布时会创建属于你的独立副本。",
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (!validPackage) {
                    Text("社区角色组至少需要 2 个角色和 1 部作品。", color = MaterialTheme.colorScheme.error)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("公开展示", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (isPublic) "可在社区搜索与浏览" else "仅持有分享码的人可以导入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = isPublic, onCheckedChange = onPublicChange, enabled = loggedIn && !busy)
                }
                group.shareCode?.takeIf(String::isNotBlank)?.let { code ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("分享码", style = MaterialTheme.typography.labelLarge)
                            Text(code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = onCopyShareCode) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "复制分享码")
                        }
                    }
                }
                if (canManageRemote) {
                    OutlinedButton(
                        onClick = onDeleteRemote,
                        enabled = loggedIn && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("删除云端版本，保留本机") }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onPublish,
                enabled = loggedIn && validPackage && !busy && (!published || !ownershipLoading),
            ) {
                Text(
                    when {
                        canManageRemote -> "更新发布"
                        published -> "发布副本"
                        else -> "发布"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CharacterGroupEditorDialog(
    existing: CharacterGroupEntity?,
    allCharacters: List<CharacterListItem>,
    allAnimes: List<AnimeEntity>,
    selectedCharacterIds: Set<Long>,
    selectedAnimeIds: Set<Long>,
    onSave: (CharacterGroupEntity?, String, String?, List<Long>, List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var description by remember(existing?.id) { mutableStateOf(existing?.description.orEmpty()) }
    var characterIds by remember(selectedCharacterIds) { mutableStateOf(selectedCharacterIds) }
    var animeIds by remember(selectedAnimeIds) { mutableStateOf(selectedAnimeIds) }
    var panel by rememberSaveable { mutableStateOf("characters") }
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新建角色组" else "编辑角色组") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    OutlinedTextField(name, { name = it }, label = { Text("名称 *") }, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(description, { description = it }, label = { Text("说明") }, modifier = Modifier.fillMaxWidth())
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(panel == "characters", { panel = "characters" }, label = { Text("角色 ${characterIds.size}") })
                        }
                        item {
                            FilterChip(panel == "works", { panel = "works" }, label = { Text("作品 ${animeIds.size}") })
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        query,
                        { query = it },
                        label = { Text(if (panel == "characters") "搜索角色" else "搜索作品") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = "清除")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (panel == "characters") {
                    items(
                        allCharacters.filter {
                            query.isBlank() || (it.character.nameCn ?: it.character.name).contains(query, true)
                        },
                        key = { "character_${it.character.id}" },
                    ) { item ->
                        FilterChip(
                            selected = item.character.id in characterIds,
                            onClick = {
                                characterIds = if (item.character.id in characterIds) {
                                    characterIds - item.character.id
                                } else characterIds + item.character.id
                            },
                            label = { Text(item.character.nameCn?.takeIf(String::isNotBlank) ?: item.character.name) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    items(
                        allAnimes.filter { query.isBlank() || it.title.contains(query, true) },
                        key = { "anime_${it.id}" },
                    ) { anime ->
                        FilterChip(
                            selected = anime.id in animeIds,
                            onClick = {
                                animeIds = if (anime.id in animeIds) animeIds - anime.id else animeIds + anime.id
                            },
                            label = { Text(anime.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(existing, name, description, characterIds.toList(), animeIds.toList()) },
                enabled = name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
