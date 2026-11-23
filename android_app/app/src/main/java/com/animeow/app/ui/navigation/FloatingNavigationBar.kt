package com.animeow.app.ui.navigation

import com.animeow.app.ui.theme.accentBackground

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.animeow.app.ui.components.GlassBackdrop
import com.animeow.app.ui.components.glassBackdropEffect
import com.animeow.app.ui.components.motionAnimationSpec
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.NavigationBarStyle
import com.animeow.app.ui.theme.NavigationLabelMode

/** Scrollable pages reserve this space at their end while drawing behind the floating bar. */
val LocalFloatingNavigationInset = compositionLocalOf { 0.dp }

/** Live navigation and customization previews use exactly the same component. */
@Composable
fun FloatingNavigationBar(
    settings: AppearanceSettings,
    destinations: List<AppDestination>,
    current: AppDestination?,
    onSelected: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
    applySystemInsets: Boolean = true,
    backdrop: GlassBackdrop? = null,
) {
    if (destinations.isEmpty()) return
    val glass = settings.activeNavigationBarStyle == NavigationBarStyle.LIQUID_GLASS
    val accentColors = com.animeow.app.ui.theme.LocalAccentColorScheme.current
    val minimal = settings.activeNavigationBarStyle == NavigationBarStyle.MINIMAL
    val opacity = settings.floatingBarOpacity.coerceIn(0f, 1f)
    val shape = RoundedCornerShape(settings.floatingBarCornerRadius.coerceIn(0, 48).dp)
    Box(
        modifier = modifier.fillMaxWidth().then(
            if (applySystemInsets) Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)) else Modifier,
        ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        BoxWithConstraints(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            val maxMargin = ((maxWidth.value - destinations.size * 48 - 8) / 2).coerceAtLeast(0f)
            val margin = settings.floatingBarHorizontalMargin.toFloat().coerceIn(0f, maxMargin).dp
            Box(Modifier.fillMaxWidth().padding(start = margin, end = margin, bottom = settings.floatingBarBottomMargin.coerceIn(0, 40).dp)) {
                BoxWithConstraints(
                    Modifier.fillMaxWidth()
                        .shadow(if (minimal) 0.dp else (settings.floatingBarShadowElevation * opacity).dp, shape)
                        .clip(shape)
                        .then(if (glass) Modifier.glassBackdropEffect(backdrop, opacity, settings.floatingBarBlur, MaterialTheme.colorScheme.surfaceContainer)
                            else Modifier.background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = opacity)))
                        .then(if (minimal || opacity == 0f) Modifier else Modifier.border(
                            0.7.dp,
                            if (glass) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.6f * opacity), MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f * opacity)))
                            else Brush.linearGradient(listOf(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f * opacity), MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f * opacity))), shape,
                        )),
                ) {
                    val cellWidth = (maxWidth - 8.dp) / destinations.size
                    val selectedIndex = destinations.indexOf(current)
                    val indicatorX by animateDpAsState(4.dp + cellWidth * selectedIndex.coerceAtLeast(0), motionAnimationSpec(280), label = "navigation_indicator_x")
                    if (selectedIndex >= 0) {
                        Box(Modifier.matchParentSize()) {
                            if (minimal) {
                                Box(Modifier.align(Alignment.BottomStart).offset { IntOffset((indicatorX + (cellWidth - 20.dp) / 2).roundToPx(), 0) }
                                    .padding(bottom = 3.dp).width(20.dp).height(3.dp).clip(CircleShape).accentBackground(com.animeow.app.ui.theme.LocalAccentPaint.current))
                            } else {
                                Box(Modifier.offset { IntOffset((indicatorX + 3.dp).roundToPx(), 0) }.fillMaxHeight().padding(vertical = 6.dp)
                                    .width(cellWidth - 6.dp).clip(CircleShape)
                                    .background(accentColors.primaryContainer.copy(alpha = if (glass) 0.62f else 1f))
                                    .then(if (settings.accentGradient.mode != com.animeow.app.ui.theme.AccentMode.SOLID)
                                        Modifier.accentBackground(com.animeow.app.ui.theme.LocalAccentPaint.current, 0.18f) else Modifier)
                                    .then(if (glass) Modifier.border(0.5.dp, Color.White.copy(alpha = 0.35f), CircleShape) else Modifier))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        destinations.forEach { destination ->
                            val selected = current == destination
                            val foreground by animateColorAsState(if (selected) accentColors.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                motionAnimationSpec(180), label = "navigation_color")
                            val scale by animateFloatAsState(if (selected) 1.06f else 1f, motionAnimationSpec(240), label = "navigation_scale")
                            val showLabel = settings.navigationLabelMode == NavigationLabelMode.ALWAYS ||
                                (settings.navigationLabelMode == NavigationLabelMode.SELECTED && selected)
                            Box(
                                Modifier.weight(1f).heightIn(min = settings.floatingBarHeight.coerceIn(48, 80).dp)
                                    .clip(shape).selectable(selected, role = Role.Tab, onClick = { onSelected(destination) })
                                    .semantics { contentDescription = destination.label },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(Modifier.padding(horizontal = 2.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Icon(if (selected) destination.selectedIcon else destination.icon, null,
                                        Modifier.size(22.dp).graphicsLayer { scaleX = scale; scaleY = scale }, tint = foreground)
                                    if (showLabel) Text(destination.label, style = MaterialTheme.typography.labelSmall,
                                        color = foreground, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
