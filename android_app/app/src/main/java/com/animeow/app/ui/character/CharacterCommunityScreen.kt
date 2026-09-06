package com.animeow.app.ui.character

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.CharacterGroupImportPreview
import com.animeow.app.data.remote.CommunityCharacterGroup
import com.animeow.app.data.remote.CommunityCharacterGroupPackage
import com.animeow.app.data.remote.CommunityLaunchRequest
import com.animeow.app.data.remote.normalizeCharacterGroupShareCode
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.CommunityGroupView
import com.animeow.app.ui.theme.ContentDensity
import com.animeow.app.ui.preferences.AppearanceViewModel
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterCommunityScreen(
    settings: AppearanceSettings,
    onBack: () -> Unit,
    showBack: Boolean = true,
    initialRequest: CommunityLaunchRequest? = null,
    onInitialRequestConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: CharacterManagementViewModel = viewModel(),
    appearanceViewModel: AppearanceViewModel = viewModel(),
) {
    val state by viewModel.community.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var showShareCode by rememberSaveable { mutableStateOf(false) }
    var showCustomization by rememberSaveable { mutableStateOf(false) }
    val showingDetail = state.selected != null

    LaunchedEffect(Unit) {
        viewModel.loadCommunity()
        viewModel.events.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(initialRequest?.key) {
        val request = initialRequest ?: return@LaunchedEffect
        request.shareCode?.let(viewModel::previewCommunityShareCode)
            ?: request.communityId?.let(viewModel::openCommunityGroup)
        onInitialRequestConsumed()
    }
    BackHandler(showingDetail && !state.isImporting) { viewModel.closeCommunityGroup() }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (showingDetail) state.selected?.summary?.name ?: "社区详情" else "角色群组社区") },
                navigationIcon = {
                    if (showingDetail || showBack) {
                        IconButton(
                            onClick = if (showingDetail) viewModel::closeCommunityGroup else onBack,
                            enabled = !state.isImporting,
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (!showingDetail) {
                        IconButton(onClick = { showCustomization = true }) {
                            Icon(Icons.Outlined.Tune, contentDescription = "自定义社区列表")
                        }
                        if (state.configured) {
                            IconButton(onClick = { showShareCode = true }) {
                                Icon(Icons.Outlined.Key, contentDescription = "使用分享码")
                            }
                            IconButton(onClick = { viewModel.loadCommunity(query) }) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = showingDetail,
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
            label = "community_group_detail",
        ) { detail ->
            if (detail) {
                state.selected?.let { packageInfo ->
                    CommunityGroupDetail(
                        packageInfo = packageInfo,
                        preview = state.importPreview,
                        importing = state.isImporting,
                        onImport = viewModel::importSelectedCommunityGroup,
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        label = { Text("搜索公开角色组") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { viewModel.loadCommunity(query) }) {
                                Icon(Icons.Outlined.Search, contentDescription = "搜索")
                            }
                        },
                        enabled = state.configured,
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                    )
                    when {
                        !state.configured -> CommunityUnavailable()
                        state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        state.error != null -> CommunityError(state.error.orEmpty()) {
                            viewModel.loadCommunity(query)
                        }
                        state.groups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.Groups, contentDescription = null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(8.dp))
                                Text("暂时没有公开角色组", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        else -> CommunityGroupList(state.groups, settings, viewModel::openCommunityGroup)
                    }
                }
            }
        }
    }

    if (showShareCode) {
        ShareCodeDialog(
            onPreview = {
                viewModel.previewCommunityShareCode(it)
                showShareCode = false
            },
            onDismiss = { showShareCode = false },
        )
    }
    if (showCustomization) {
        CommunityCustomizationSheet(
            settings = settings,
            onViewSelected = appearanceViewModel::setCommunityGroupView,
            onDensitySelected = appearanceViewModel::setCommunityDensity,
            onShowDescriptionChanged = appearanceViewModel::setCommunityShowDescription,
            onShowDownloadsChanged = appearanceViewModel::setCommunityShowDownloads,
            onDismiss = { showCustomization = false },
        )
    }
}

@Composable
private fun CommunityUnavailable() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        ElevatedCard(shape = MaterialTheme.shapes.extraLarge) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.Groups, contentDescription = null, modifier = Modifier.size(44.dp))
                Text("社区服务未配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "本地角色、关系、标签、角色组及收藏包导入导出仍可完整使用。配置 CLOUD_API_BASE 后即可浏览社区和使用分享码。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CommunityGroupList(
    groups: List<CommunityCharacterGroup>,
    settings: AppearanceSettings,
    onOpen: (String) -> Unit,
) {
    if (settings.communityGroupView == CommunityGroupView.GRID) {
        CommunityGroupGrid(groups, settings, onOpen)
        return
    }
    val density = settings.communityDensity.scale
    LazyColumn(
        contentPadding = PaddingValues((16 * density).dp, 4.dp, (16 * density).dp, 40.dp),
        verticalArrangement = Arrangement.spacedBy((12 * density).dp),
    ) {
        items(groups, key = CommunityCharacterGroup::id) { group ->
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().animatedPressClick(
                    onClickLabel = "查看${group.name}角色组详情",
                ) { onOpen(group.id) },
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding((16 * density).dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size((74 * density).dp).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!group.coverUrl.isNullOrBlank()) {
                            AsyncImage(group.coverUrl, group.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else Icon(Icons.Outlined.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        group.description?.takeIf { settings.communityShowDescription }?.let {
                            Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            buildCommunityCountLabel(group, settings.communityShowDownloads),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        group.username?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Person, contentDescription = null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityGroupGrid(
    groups: List<CommunityCharacterGroup>,
    settings: AppearanceSettings,
    onOpen: (String) -> Unit,
) {
    val density = settings.communityDensity.scale
    LazyVerticalGrid(
        columns = GridCells.Adaptive((156 * density).dp),
        contentPadding = PaddingValues((14 * density).dp, 4.dp, (14 * density).dp, 40.dp),
        horizontalArrangement = Arrangement.spacedBy((10 * density).dp),
        verticalArrangement = Arrangement.spacedBy((10 * density).dp),
    ) {
        gridItems(groups, key = CommunityCharacterGroup::id) { group ->
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().animatedPressClick(
                    onClickLabel = "查看${group.name}角色组详情",
                ) { onOpen(group.id) },
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column {
                    Box(
                        Modifier.fillMaxWidth().height((138 * density).dp)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!group.coverUrl.isNullOrBlank()) {
                            AsyncImage(group.coverUrl, group.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(Icons.Outlined.Groups, contentDescription = null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Column(
                        Modifier.padding((12 * density).dp),
                        verticalArrangement = Arrangement.spacedBy((5 * density).dp),
                    ) {
                        Text(
                            group.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        group.description?.takeIf { settings.communityShowDescription }?.let {
                            Text(
                                it,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            buildCommunityCountLabel(group, settings.communityShowDownloads),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunityCustomizationSheet(
    settings: AppearanceSettings,
    onViewSelected: (CommunityGroupView) -> Unit,
    onDensitySelected: (ContentDensity) -> Unit,
    onShowDescriptionChanged: (Boolean) -> Unit,
    onShowDownloadsChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("社区列表定制", style = MaterialTheme.typography.headlineSmall)
            Text("仅改变列表展示，不影响下载、分享码和导入能力。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("排列方式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CommunityGroupView.entries) { view ->
                    FilterChip(
                        selected = settings.communityGroupView == view,
                        onClick = { onViewSelected(view) },
                        label = { Text(view.displayName) },
                    )
                }
            }
            Text("页面密度", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ContentDensity.entries) { density ->
                    FilterChip(
                        selected = settings.communityDensity == density,
                        onClick = { onDensitySelected(density) },
                        label = { Text(density.displayName) },
                    )
                }
            }
            Text(
                settings.communityDensity.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CommunityToggle("显示描述", settings.communityShowDescription, onShowDescriptionChanged)
            CommunityToggle("显示下载次数", settings.communityShowDownloads, onShowDownloadsChanged)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("完成") }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun CommunityToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun buildCommunityCountLabel(group: CommunityCharacterGroup, showDownloads: Boolean): String =
    buildList {
        add("${group.characterCount} 角色")
        add("${group.workCount} 作品")
        if (showDownloads) add("${group.downloadCount} 次下载")
    }.joinToString(" · ")

@Composable
private fun CommunityGroupDetail(
    packageInfo: CommunityCharacterGroupPackage,
    preview: CharacterGroupImportPreview?,
    importing: Boolean,
    onImport: () -> Unit,
) {
    val summary = packageInfo.summary
    val payloadPreview = remember(packageInfo.payloadJson) { parseCommunityPreview(packageInfo.payloadJson) }
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
                    Text(summary.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    summary.description?.let { Text(it) }
                    Text("${summary.characterCount} 个角色 · ${summary.workCount} 部作品", color = MaterialTheme.colorScheme.primary)
                    summary.shareCode?.let { Text("分享码：$it", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        item { PreviewCard("角色成员", payloadPreview.first, "角色") }
        item { PreviewCard("关联作品", payloadPreview.second, "作品") }
        preview?.let { importPreview ->
            item { CommunityImportPreviewCard(importPreview) }
        }
        item {
            FilledTonalButton(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
                enabled = !importing,
            ) {
                if (importing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (importing) "正在安全导入" else "确认导入到本地")
            }
        }
        item {
            Text(
                "导入时会优先按 Bangumi ID 与外部作品 ID 复用本地资料，再按中日文名称匹配。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CommunityImportPreviewCard(preview: CharacterGroupImportPreview) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("本地差异预检", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "角色：新建 ${preview.createdCharacterCount} · 复用 ${preview.reusedCharacterCount}　" +
                    "作品：新建 ${preview.createdWorkCount} · 复用 ${preview.reusedWorkCount}",
            )
            Text(
                if (preview.updatesExistingGroup) "已存在同一社区群组，将更新群组成员关系。"
                else "将新建一个本地角色组。",
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "本地已有作品和角色会优先保留已有字段；全部写入位于同一数据库事务中，失败会整体回滚。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PreviewCard(title: String, names: List<String>, fallback: String) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (names.isEmpty()) Text("没有可预览的$fallback", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else names.take(12).forEach { Text("• $it") }
        }
    }
}

@Composable
private fun CommunityError(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun ShareCodeDialog(onPreview: (String) -> Unit, onDismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val normalized = normalizeCharacterGroupShareCode(code)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("使用角色组分享码") },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = {
                    code = it.uppercase(java.util.Locale.ROOT).take(24)
                },
                label = { Text("8 位分享码或 cg#口令") },
                supportingText = {
                    if (code.isNotBlank() && normalized == null) Text("分享码仅使用易辨识的大写字母和 2–9")
                },
                isError = code.isNotBlank() && normalized == null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { normalized?.let(onPreview) }, enabled = normalized != null) { Text("查看预览") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun parseCommunityPreview(raw: String): Pair<List<String>, List<String>> = runCatching {
    val root = JSONObject(raw)
    val payload = root.optJSONObject("payload") ?: root
    val characters = payload.optJSONArray("characters") ?: payload.optJSONArray("character_snapshots")
    val works = payload.optJSONArray("works") ?: payload.optJSONArray("work_snapshots") ?: payload.optJSONArray("subjects")
    val characterNames = buildList {
        if (characters != null) for (index in 0 until characters.length()) {
            val row = characters.optJSONObject(index) ?: continue
            (row.optString("name_cn").takeIf(String::isNotBlank)
                ?: row.optString("name").takeIf(String::isNotBlank))?.let(::add)
        }
    }
    val workNames = buildList {
        if (works != null) for (index in 0 until works.length()) {
            works.optJSONObject(index)?.optString("title")?.takeIf(String::isNotBlank)?.let(::add)
        }
    }
    characterNames to workNames
}.getOrDefault(emptyList<String>() to emptyList())
