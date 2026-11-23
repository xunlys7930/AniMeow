package com.animeow.app.ui.legacy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.foundation.layout.WindowInsets
import com.animeow.app.ui.theme.NavigationLabelMode
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.animeow.app.ui.icons.IconGlyph
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.navigation.AppDestination

@Composable
fun LegacyNavigationBar(
    destinations: List<AppDestination>,
    current: AppDestination?,
    onSelected: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
    labelMode: NavigationLabelMode = NavigationLabelMode.ALWAYS,
    applySystemInsets: Boolean = true,
) {
    val accentColors = com.animeow.app.ui.theme.LocalAccentColorScheme.current
    val independentTheme = com.animeow.app.ui.theme.LocalIndependentThemeColor.current != null
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
        tonalElevation = 0.dp,
        windowInsets = if (applySystemInsets) NavigationBarDefaults.windowInsets else WindowInsets(0, 0, 0, 0),
    ) {
        destinations.forEach { destination ->
            val selected = current == destination
            NavigationBarItem(
                selected = selected,
                onClick = { onSelected(destination) },
                icon = {
                    Icon(
                        icon = if (selected) destination.selectedIcon else destination.icon,
                        contentDescription = destination.label,
                    )
                },
                label = if (labelMode == NavigationLabelMode.ICONS_ONLY) null else { { Text(destination.label, fontWeight = FontWeight.Bold, maxLines = 1) } },
                alwaysShowLabel = labelMode == NavigationLabelMode.ALWAYS,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = accentColors.onPrimaryContainer,
                    selectedTextColor = if (independentTheme) accentColors.primary else MaterialTheme.colorScheme.onSurface,
                    indicatorColor = accentColors.primaryContainer.copy(alpha = 0.68f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}

@Composable
fun LegacyPageHeader(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), content = actions)
    }
}

@Composable
fun LegacySectionTitle(
    modifier: Modifier = Modifier,
    icon: IconGlyph,
    title: String,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f),
        ) {
            Icon(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.padding(10.dp).size(24.dp),
            )
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun LegacyActionCard(
    modifier: Modifier = Modifier,
    icon: IconGlyph,
    title: String,
    subtitle: String,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f),
        ),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(tint.copy(alpha = 0.12f), MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun LegacyDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(9.dp).background(color, CircleShape))
}
