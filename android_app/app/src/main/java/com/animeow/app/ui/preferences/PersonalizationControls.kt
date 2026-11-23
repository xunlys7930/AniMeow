@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.animeow.app.ui.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.animeow.app.data.preferences.*
import com.animeow.app.ui.theme.*

@Composable
internal fun AppearanceSuitePicker(settings: AppearanceSettings, onSelect: (AppearanceSuite) -> Unit, enabled: Boolean = true) {
    Text("整套外观预设", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text("先试试聚合与网格：圆润的悬浮导航配合完整动效。也可选择安静的清单方案，之后继续微调。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    AppearanceSuite.entries.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            pair.forEach { suite ->
                val preview = suite.apply(settings)
                OutlinedCard(onClick = { onSelect(suite) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppearanceSuiteMiniature(preview)
                        Text(suite.label, fontWeight = FontWeight.SemiBold)
                        Text(suite.description, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSuiteMiniature(settings: AppearanceSettings) {
    val dark = settings.themeMode == ThemeMode.DARK
    val paper = if (dark) Color(0xFF182334) else Color(0xFFF5F4EF)
    val foreground = if (dark) Color.White else Color(0xFF31453F)
    val palette = listOf(Color(settings.accentColor!!)) + settings.accentGradient.colors.map { Color(it) }
    val brush = if (settings.accentGradient.mode == AccentMode.SOLID) SolidColor(palette.first()) else Brush.linearGradient(palette)
    Box(Modifier.fillMaxWidth().height(78.dp).background(paper, MaterialTheme.shapes.medium).padding(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (settings.homeLayout) {
                    HomeLayout.BENTO -> {
                        Box(Modifier.weight(1.4f).height(34.dp).background(brush, MaterialTheme.shapes.small))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            repeat(2) { Box(Modifier.fillMaxWidth().height(15.dp).background(foreground.copy(alpha = 0.13f), MaterialTheme.shapes.small)) }
                        }
                    }
                    HomeLayout.COMPACT_INDEX -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(3) { Box(Modifier.fillMaxWidth().height(8.dp).background(palette.first().copy(alpha = 0.24f), MaterialTheme.shapes.small)) }
                    }
                    else -> repeat(3) { Box(Modifier.weight(1f).height(34.dp).background(brush, MaterialTheme.shapes.small)) }
                }
            }
            Text(settings.accentGradient.mode.label, style = MaterialTheme.typography.labelSmall, color = foreground)
        }
        Row(Modifier.align(Alignment.BottomEnd).background(foreground.copy(alpha = 0.12f),
            if (settings.activeNavigationBarStyle.isFloating) androidx.compose.foundation.shape.CircleShape else MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) { index -> Box(Modifier.size(if (index == 0) 8.dp else 5.dp)
                .background(if (index == 0) palette.first() else foreground.copy(alpha = 0.35f), androidx.compose.foundation.shape.CircleShape)) }
        }
    }
}

@Composable
internal fun ChangedSettingsPanel(changes: List<AppearanceChange>, category: String?, onReset: (String) -> Unit) {
    var expanded by remember(category) { mutableStateOf(false) }
    var confirmReset by remember(category) { mutableStateOf(false) }
    val selected = changes.filter { category == null || it.category == category }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (selected.isEmpty()) "本分类使用默认设置" else "${selected.size} 项与默认不同", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                if (selected.isNotEmpty()) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "查看调整") }
            }
            if (expanded) selected.forEach { change -> Text("${change.label}：${change.after}（默认 ${change.before}）", style = MaterialTheme.typography.bodySmall) }
            if (category != null && selected.isNotEmpty()) TextButton(onClick = { confirmReset = true }) { Text("仅恢复本分类 · 可撤销") }
        }
    }
    if (confirmReset && category != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复「${customizationSection(category)?.title ?: "当前分类"}」默认？") },
            text = { Column {
                Text("仅恢复这一分类，不清理收藏、账号或其他分类。恢复后仍可撤销上一步，查看变化并应用后保留。")
                Text("涉及：${selected.joinToString("、") { it.label }}", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = { confirmReset = false; onReset(category) }) { Text("恢复本分类") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }
}

@Composable
internal fun PageDensityControls(settings: AppearanceSettings, viewModel: CustomizationViewModel) {
    Text("各页独立密度", style = MaterialTheme.typography.titleSmall)
    DENSITY_PAGES.forEach { (page, label) ->
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            FilterChip(settings.pageDensities[page] == null, { viewModel.setPageDensity(page, null) }, label = { Text("跟随默认") })
            ContentDensity.entries.forEach { density ->
                FilterChip(settings.pageDensities[page] == density, { viewModel.setPageDensity(page, density) }, label = { Text(density.displayName) },
                    modifier = Modifier.semantics { contentDescription = "$label · ${density.displayName}" })
            }
        }
        val density = settings.pageDensities[page] ?: settings.contentDensity
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding((12 * density.scale).dp), verticalArrangement = Arrangement.spacedBy((8 * density.scale).dp)) {
                Text("$label · ${density.displayName}预览", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy((10 * density.scale).dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.size((48 * density.itemScale).dp, (64 * density.itemScale).dp), color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                        Box(contentAlignment = Alignment.Center) { Text(if (page == "characters") "头像" else "封面", style = MaterialTheme.typography.labelSmall) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy((5 * density.scale).dp)) {
                        Text(if (page == "characters") "喜欢的角色" else "我的追番记录", style = MaterialTheme.typography.titleSmall)
                        Text(when (page) { "series" -> "系列作品 · 2 部"; "characters" -> "关联 3 部作品 · 2 条关系"; else -> "看到第 6 集 / 共 12 集" }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    Text("发现、日历、社区和统计也保留各自页面里的密度设置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun CustomizationGroup(
    title: String,
    subtitle: String,
    initiallyExpanded: Boolean = true,
    forceExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded || forceExpanded) }
    LaunchedEffect(forceExpanded) { if (forceExpanded) expanded = true }
    com.animeow.app.ui.screens.SettingsDisclosureSection(title, subtitle, expanded, { expanded = it }) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun SearchableSetting(
    key: String,
    title: String,
    focusedControl: String,
    onHighlightFinished: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val requester = remember { BringIntoViewRequester() }
    val highlighted = key == focusedControl
    LaunchedEffect(highlighted) {
        if (highlighted) {
            // Wait for the existing section/page expansion before moving the target into view.
            delay(350)
            requester.bringIntoView()
            delay(2500)
            onHighlightFinished()
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester).testTag("setting-control-$key"),
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (highlighted) Text("搜索定位 · $title", style = MaterialTheme.typography.labelLarge)
            content()
        }
    }
}

@Composable
internal fun HomeModuleControls(settings: TrackerSettings, onVisibility: (HomeModule, Boolean) -> Unit, onMove: (HomeModule, Int) -> Unit) {
    Text("首页个人编排", style = MaterialTheme.typography.titleSmall)
    Text("模块随页面滚动；搜索或筛选时自动收起。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("最近观看显示动画播放历史，阅读进度仅用于书籍。手动记集与撤销仍在作品卡片和详情中。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    settings.homeModuleOrder.forEachIndexed { index, module ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(module.label, Modifier.weight(1f))
            Switch(settings.shows(module), { onVisibility(module, it) }, modifier = Modifier.semantics { contentDescription = "显示${module.label}" })
            TextButton(onClick = { onMove(module, -1) }, enabled = index > 0, contentPadding = PaddingValues(4.dp), modifier = Modifier.semantics { contentDescription = "上移${module.label}" }) { Text("上移") }
            TextButton(onClick = { onMove(module, 1) }, enabled = index < settings.homeModuleOrder.lastIndex, contentPadding = PaddingValues(4.dp), modifier = Modifier.semantics { contentDescription = "下移${module.label}" }) { Text("下移") }
        }
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("首页编排预览", style = MaterialTheme.typography.labelLarge)
            val visible = settings.homeModuleOrder.filter(settings::shows)
            if (visible.isEmpty()) Text("直接显示收藏列表", style = MaterialTheme.typography.bodySmall)
            visible.forEachIndexed { index, module -> Text("${index + 1}  ${module.label}${if (module == HomeModule.CONTINUE) " · 书籍" else if (module == HomeModule.PLAYBACK) " · 动画" else ""}", style = MaterialTheme.typography.bodySmall) }
            Text("收藏列表", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun ImportAppearanceComparison(current: AppearanceSettings, tracker: TrackerSettings, state: CustomizationUiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val pending = state.pendingProfile ?: return
    var showImported by remember(pending.id) { mutableStateOf(true) }
    AlertDialog(onDismissRequest = { if (!state.busy) onDismiss() }, title = { Text(if (state.pendingProfileIsSaved) "应用个性化配置" else "导入个性化配置") },
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(pending.name, fontWeight = FontWeight.SemiBold)
                Text("切换比较效果。确认后开始实时预览，点击外观页的“应用”保留，也可撤销或放弃。", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!showImported, { showImported = false }, label = { Text("当前外观") }, modifier = Modifier.weight(1f))
                    FilterChip(showImported, { showImported = true }, label = { Text("导入后") }, modifier = Modifier.weight(1f))
                }
                val previewSettings = if (showImported) pending.settings else current
                val previewTracker = if (showImported) pending.configuration.tracker ?: tracker else tracker
                AniMeowTheme(previewSettings) { HomePagePreview(previewSettings, previewTracker) }
                Text("${state.pendingChanges.size} 项个性化配置变化", fontWeight = FontWeight.SemiBold)
                Text("外观、操作、导航与页面偏好按实际差异列出；未包含的配置保留本机设置。", style = MaterialTheme.typography.bodySmall)
                state.pendingChanges.forEach { Text("${it.label}：${it.before} → ${it.after}", style = MaterialTheme.typography.bodySmall) }
                Text("字体与背景文件不会随方案导入。", style = MaterialTheme.typography.bodySmall)
                if (pending.configuration.discovery != null) Text("此文件也包含数据源配置，确认后会一起应用。", color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { TextButton(enabled = !state.busy, onClick = onConfirm) { Text(if (state.pendingProfileIsSaved) "预览此配置" else "导入并预览") } },
        dismissButton = { TextButton(enabled = !state.busy, onClick = onDismiss) { Text("取消") } })
}
