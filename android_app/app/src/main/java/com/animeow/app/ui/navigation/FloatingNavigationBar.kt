package com.animeow.app.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.NavigationLabelMode

/** The preview and live navigation share this layout, including spacing and touch targets. */
@Composable
fun FloatingNavigationBar(
    settings: AppearanceSettings,
    destinations: List<AppDestination>,
    current: AppDestination?,
    onSelected: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
    applySystemInsets: Boolean = true,
) {
    Box(
        modifier = modifier.fillMaxWidth().then(
            if (applySystemInsets) Modifier.windowInsetsPadding(
                WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            ) else Modifier,
        ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        BoxWithConstraints(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            val inlineLabel = settings.navigationLabelMode == NavigationLabelMode.SELECTED &&
                maxWidth >= ((destinations.size + 0.6f) * 48 + 8).dp
            val totalWeight = destinations.size + if (inlineLabel && current in destinations) 0.6f else 0f
            val maxMargin = ((maxWidth.value - totalWeight * 48 - 8) / 2).coerceAtLeast(0f)
            val margin = settings.floatingBarHorizontalMargin.toFloat().coerceIn(0f, maxMargin)
            Surface(
                modifier = Modifier.fillMaxWidth().padding(
                    start = margin.dp, end = margin.dp,
                    bottom = settings.floatingBarBottomMargin.coerceIn(0, 40).dp,
                ),
                shape = RoundedCornerShape(settings.floatingBarCornerRadius.coerceIn(0, 48).dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                shadowElevation = settings.floatingBarShadowElevation.coerceIn(0, 24).dp,
            ) {
                Row(
                    Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    destinations.forEach { destination ->
                        val selected = current == destination
                        val foreground = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        Box(
                            modifier = Modifier.weight(if (selected && inlineLabel) 1.6f else 1f)
                                .heightIn(min = settings.floatingBarHeight.coerceIn(48, 80).dp)
                                .clip(RoundedCornerShape(settings.floatingBarCornerRadius.dp))
                                .selectable(selected, role = Role.Tab, onClick = { onSelected(destination) })
                                .semantics { contentDescription = destination.label },
                            contentAlignment = Alignment.Center,
                        ) {
                            val icon: @Composable () -> Unit = {
                                Icon(
                                    if (selected) destination.selectedIcon else destination.icon,
                                    contentDescription = null,
                                    tint = foreground,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            val label: @Composable () -> Unit = {
                                Text(
                                    destination.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = foreground,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (settings.navigationLabelMode == NavigationLabelMode.ALWAYS) {
                                Column(
                                    Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Box(
                                        Modifier.clip(CircleShape)
                                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                            .padding(horizontal = 14.dp, vertical = 3.dp),
                                    ) { icon() }
                                    label()
                                }
                            } else {
                                Row(
                                    Modifier.padding(vertical = 4.dp)
                                        .clip(CircleShape)
                                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    icon()
                                    if (selected && inlineLabel) label()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
