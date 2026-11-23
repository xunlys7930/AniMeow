package com.animeow.app.ui.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.animeow.app.data.preferences.AppearanceChange
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.icons.AppIcon as Icon
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.icons.IconGlyph
import com.animeow.app.ui.theme.APP_FONT_SCALE_MAX
import com.animeow.app.ui.theme.APP_FONT_SCALE_MIN
import com.animeow.app.ui.theme.DetailLayout
import com.animeow.app.ui.theme.HomeLayout
import com.animeow.app.ui.theme.NavigationBarStyle
import com.animeow.app.ui.theme.ThemeMode
import kotlin.math.roundToInt

/**
 * 外观定制二级页的「常用」卡片：只放多数人会调整的几项，
 * 同一设置在三级页中仍然完整可调，两处读写同一份草稿。
 */
@Composable
internal fun CustomizationQuickSettings(
    settings: AppearanceSettings,
    viewModel: CustomizationViewModel,
) {
    var fontDraft by remember(settings.fontScale) { mutableFloatStateOf(settings.fontScale) }
    SettingSection("常用", "先调阅读与颜色；页面布局和细节按对象查找") {
        QuickLabel("外观模式")
        ChoiceFlow(ThemeMode.entries, settings.themeMode, { it.displayName }, viewModel::setThemeMode)
        HorizontalDivider()
        AccentPalettePresets(settings, viewModel::applyAccentPreset)
        HorizontalDivider()
        QuickLabel("文字大小 ${(fontDraft * 100).roundToInt()}%")
        Slider(
            value = fontDraft,
            onValueChange = { fontDraft = it },
            onValueChangeFinished = { viewModel.setFontScale(fontDraft) },
            valueRange = APP_FONT_SCALE_MIN..APP_FONT_SCALE_MAX,
            modifier = Modifier.semantics { contentDescription = "文字大小" },
        )
    }
}

@Composable
private fun ColumnScope.QuickLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

/** 进阶定制入口：每个分区一行，点按进入三级页查看全部控件。 */
@Composable
internal fun AdvancedCustomizationEntries(
    changes: List<AppearanceChange>,
    sections: List<CustomizationSection> = appearanceOverviewSections,
    onOpen: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
            Icon(AppIcons.SettingsDepth, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("按对象细调", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(
            "视觉与操作分开；调整可预览和撤销。文件导入的即时保存范围会单独说明。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                sections.forEachIndexed { index, section ->
                    val changed = changes.count { it.category in customizationCategories(section.key) }
                    AdvancedSectionRow(
                        icon = section.icon(),
                        title = section.title,
                        summary = section.summary,
                        changed = changed,
                        onClick = { onOpen(section.key) },
                    )
                    if (index != sections.lastIndex) {
                        HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvancedSectionRow(
    icon: IconGlyph,
    title: String,
    summary: String,
    changed: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .animatedPressClick(onClickLabel = "打开$title", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (changed > 0) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(
                    "已调 $changed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.padding(start = 4.dp))
    }
}

private fun CustomizationSection.icon(): IconGlyph = when (key) {
    "visual" -> AppIcons.Palette
    "icons" -> AppIcons.Sparkles
    "pages", "layout" -> AppIcons.Grid
    "size" -> AppIcons.EditNote
    "card" -> AppIcons.List
    "cover" -> AppIcons.Image
    "motion" -> AppIcons.Touch
    "nav" -> AppIcons.Hub
    "brand" -> AppIcons.Branding
    else -> AppIcons.Save
}

/** 可按需展开真实组件预览的分区。 */
internal val PREVIEW_SECTIONS = setOf("visual", "layout", "size", "card", "cover", "nav")
