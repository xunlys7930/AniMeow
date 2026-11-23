package com.animeow.app.ui.preferences

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.animeow.app.ui.components.StableModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.icons.AppIcon
import com.animeow.app.ui.icons.AppIconId
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.icons.IconCategory
import com.animeow.app.ui.icons.IconGlyph
import com.animeow.app.ui.icons.IconOverride
import com.animeow.app.ui.icons.IconSettings
import com.animeow.app.ui.icons.IconStyle
import com.animeow.app.ui.icons.IconVariant
import com.animeow.app.ui.icons.LocalIconSystemAnimationsEnabled
import com.animeow.app.ui.theme.LocalMotionLevel
import com.animeow.app.ui.theme.MotionLevel

private val navigationSamples = listOf(
    AppIcons.Tracker, AppIcons.Discover, AppIcons.Calendar,
    AppIcons.Community, AppIcons.Statistics, AppIcons.Profile,
)

@Composable
internal fun IconCustomizationControls(
    settings: IconSettings,
    onStyle: (IconStyle) -> Unit,
    onVariant: (IconVariant) -> Unit,
    onAnimated: (Boolean) -> Unit,
    onOverride: (AppIconId, IconOverride?) -> Unit,
    onResetOverrides: () -> Unit,
    onColor: (com.animeow.app.ui.icons.IconColorSettings) -> Unit = {},
) {
    var browsing by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("整套风格", style = MaterialTheme.typography.labelLarge)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IconStyle.entries.forEach { style ->
                val selected = style == settings.style
                Surface(
                    modifier = Modifier.fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .selectable(selected, role = Role.RadioButton, onClick = { onStyle(style) }),
                    shape = MaterialTheme.shapes.large,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(if (selected) 1.5.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(style.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(style.description, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RadioButton(selected = selected, onClick = null)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            navigationSamples.forEach { icon ->
                                AppIcon(icon, null, Modifier.size(24.dp), style = style, variant = settings.variant,
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        Text("整套默认变体", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconVariant.entries.forEach { variant ->
                VariantChoice(
                    icon = AppIcons.Search, style = settings.style, variant = variant,
                    selected = settings.variant == variant, onClick = { onVariant(variant) },
                    modifier = Modifier.weight(1f), description = "整套" + variant.label,
                )
            }
        }
        Text("A 与 B 的功能含义相同，轮廓和细节略有不同。整套切换会保留你的单独设置。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconColorControls(settings.color, onChange = { onColor(it ?: com.animeow.app.ui.icons.IconColorSettings()) })

        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(settings.animated, role = Role.Switch, onValueChange = onAnimated),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("图标交互动效", style = MaterialTheme.typography.titleSmall)
                Text("选中轻弹、提醒轻摇，刷新时旋转", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = settings.animated, onCheckedChange = null)
        }
        if (settings.animated && (LocalMotionLevel.current != MotionLevel.FULL || !LocalIconSystemAnimationsEnabled.current)) {
            Text("当前遵循精简动效或系统动画设置，以静态图标显示。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconMotionSamples(settings.animated)
        FilledTonalButton(onClick = { browsing = true }, modifier = Modifier.fillMaxWidth()) {
            AppIcon(AppIcons.Widgets, null, Modifier.size(20.dp))
            Text("逐个定制图标", Modifier.padding(start = 8.dp))
        }
        Text(
            if (settings.overrides.isEmpty()) "全部 " + AppIconId.entries.size + " 个图标跟随整套设置"
            else "已单独设置 " + settings.overrides.size + " 个图标，其余跟随整套",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (settings.overrides.isNotEmpty()) {
            TextButton(onClick = onResetOverrides) { Text("让所有图标重新跟随整套") }
        }
    }
    if (browsing) IconBrowserSheet(settings, onOverride, onDismiss = { browsing = false })
}

@Composable
private fun IconMotionSamples(enabled: Boolean) {
    var trigger by remember { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(AppIcons.Refresh to "刷新", AppIcons.Notification to "提醒", AppIcons.HeartFilled to "喜欢")
                .forEach { (icon, label) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AppIcon(icon, null, Modifier.size(26.dp), animationTrigger = trigger,
                            tint = MaterialTheme.colorScheme.primary)
                        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                    }
                }
        }
        TextButton(onClick = { trigger++ }, enabled = enabled, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("播放图标动效")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IconBrowserSheet(
    settings: IconSettings,
    onOverride: (AppIconId, IconOverride?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var categoryKey by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyCustomized by rememberSaveable { mutableStateOf(false) }
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = AppIconId.fromStorage(editingKey)
    val visible = remember(query, categoryKey, onlyCustomized, settings.overrides) {
        AppIconId.entries.filter { id ->
            (categoryKey == null || id.category.name == categoryKey) &&
                (!onlyCustomized || id in settings.overrides) &&
                (query.isBlank() || (id.label + " " + id.storageKey + " " + id.keywords).contains(query.trim(), ignoreCase = true))
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 20.dp)) {
            if (editing != null) {
                IconEditor(editing, settings, onOverride, onBack = { editingKey = null })
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("逐个定制", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
                Text("点击一个图标，为它选择喜欢的风格和变体。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    placeholder = { Text("搜索功能图标") },
                    leadingIcon = { AppIcon(AppIcons.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                            AppIcon(AppIcons.Close, "清除图标搜索")
                        }
                    },
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(selected = categoryKey == null, onClick = { categoryKey = null }, label = { Text("全部") })
                    }
                    items(IconCategory.entries, key = { it.name }) { category ->
                        FilterChip(selected = categoryKey == category.name, onClick = { categoryKey = category.name },
                            label = { Text(category.label) })
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(visible.size.toString() + " 个图标", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilterChip(selected = onlyCustomized, onClick = { onlyCustomized = !onlyCustomized },
                        label = { Text("已定制 " + settings.overrides.size) })
                }
                if (visible.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        AppIcon(AppIcons.Search, null, Modifier.size(32.dp))
                        Text(if (onlyCustomized) "没有符合条件的单独设置" else "没有找到相关图标",
                            modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { query = ""; categoryKey = null; onlyCustomized = false }) { Text("查看全部图标") }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(94.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f).testTag("icon-browser-grid"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible, key = { it.storageKey }) { id ->
                            val customized = id in settings.overrides
                            Surface(onClick = { editingKey = id.storageKey }, shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = if (customized) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            ) {
                                Column(Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AppIcon(IconGlyph(id), null, Modifier.size(26.dp),
                                        tint = if (customized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    Text(id.label, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (customized) "单独设置" else "跟随整套", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun IconEditor(
    id: AppIconId,
    settings: IconSettings,
    onOverride: (AppIconId, IconOverride?) -> Unit,
    onBack: () -> Unit,
) {
    val override = settings.overrides[id]
    val resolved = settings.resolve(id)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { AppIcon(AppIcons.Back, "返回图标列表") }
            Text(id.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            AppIcon(IconGlyph(id), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text("当前：" + resolved.style.label + " · " + resolved.variant.label,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                .selectable(override == null, role = Role.RadioButton, onClick = { onOverride(id, null) }),
            shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = override == null, onClick = null)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("完全跟随整套", style = MaterialTheme.typography.titleSmall)
                    Text(settings.style.label + " · " + settings.variant.label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("仅改变设计变体，风格跟随整套", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconVariant.entries.forEach { variant ->
                VariantChoice(IconGlyph(id), settings.style, variant,
                    selected = override != null && override.style == null && override.variant == variant,
                    onClick = { onOverride(id, IconOverride(variant = variant, color = override?.color)) }, modifier = Modifier.weight(1f),
                    description = id.label + "，跟随整套风格，" + variant.label)
            }
        }
        Text("或固定为独立风格", style = MaterialTheme.typography.labelLarge)
        IconStyle.entries.forEach { style ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(style.label, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconVariant.entries.forEach { variant ->
                        VariantChoice(IconGlyph(id), style, variant,
                            selected = override?.style == style && override.variant == variant,
                            onClick = { onOverride(id, IconOverride(style, variant, override?.color)) }, modifier = Modifier.weight(1f),
                            description = id.label + "，" + style.label + "，" + variant.label)
                    }
                }
            }
        }
        IconColorControls(override?.color, allowInherit = true, onChange = { color ->
            onOverride(id, (override ?: IconOverride()).copy(color = color).takeUnless { it.followsGlobal })
        })
        Text("同一功能在各页面统一生效；独立风格与颜色会在切换整套时保留。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) { Text("完成选择") }
    }
}

@Composable
private fun VariantChoice(
    icon: IconGlyph,
    style: IconStyle,
    variant: IconVariant,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String,
) {
    Surface(
        modifier = modifier.heightIn(min = 72.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppIcon(icon, null, Modifier.size(26.dp), style = style, variant = variant,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            Text(variant.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (selected) AppIcon(AppIcons.Check, null, Modifier.size(16.dp))
        }
    }
}
