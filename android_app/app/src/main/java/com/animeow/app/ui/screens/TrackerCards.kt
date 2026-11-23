package com.animeow.app.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.animeow.app.data.preferences.CardProgressPlacement
import androidx.compose.foundation.layout.FlowRow

import com.animeow.app.ui.icons.AppIcons

import com.animeow.app.ui.theme.densityFor

import com.animeow.app.ui.navigation.LocalFloatingNavigationInset

import android.icu.text.Transliterator
import android.os.Build
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import android.view.SoundEffectConstants

import androidx.annotation.RequiresApi
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.formatAnimeRating
import com.animeow.app.data.local.normalizeAnimeRatingGrade
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.RemoteMetadataFields
import com.animeow.app.data.preferences.HomeBottomScope
import com.animeow.app.data.preferences.BentoCollectionLayout
import com.animeow.app.data.preferences.CoverFitMode
import com.animeow.app.data.preferences.TRACKER_START_ALL_STATUS
import com.animeow.app.data.preferences.TRACKER_START_LAST_STATUS
import com.animeow.app.data.preferences.TrackerSettings
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.components.WatchStatusBadge
import com.animeow.app.ui.components.StatusBadgeControls
import com.animeow.app.data.preferences.StatusBadgeBackground
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.tracker.libraryItemClick
import com.animeow.app.ui.components.NewTagDialog
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.CardInfoField
import com.animeow.app.ui.theme.APP_FONT_SCALE_MAX
import com.animeow.app.ui.theme.APP_FONT_SCALE_MIN
import com.animeow.app.ui.theme.ContentDensity
import com.animeow.app.ui.theme.CoverAspectRatio
import com.animeow.app.ui.theme.HomeLayout
import com.animeow.app.ui.theme.DetailLayout
import com.animeow.app.ui.theme.LocalMotionLevel
import com.animeow.app.ui.theme.MotionLevel
import com.animeow.app.ui.theme.SwipeAction
import com.animeow.app.ui.theme.CoverBadgeStyle
import com.animeow.app.ui.theme.CoverTitlePosition
import com.animeow.app.ui.theme.RatingBadgeColorStyle
import com.animeow.app.ui.preferences.ColorPickerDialog
import com.animeow.app.ui.tracker.LibrarySort
import com.animeow.app.ui.tracker.LibraryDisplayItem
import com.animeow.app.ui.tracker.BatchMetadataOptions
import com.animeow.app.ui.tracker.BatchMetadataState
import com.animeow.app.ui.tracker.AdvancedLibraryFilters
import com.animeow.app.ui.tracker.TrackerViewModel
import com.animeow.app.ui.tracker.TrackerHomeSections
import java.nio.charset.Charset
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.max

// 追番首页的作品 / 系列卡片、滑动操作容器、封面与元信息组件。
@Composable
internal fun LibraryListItemCard(
    item: LibraryDisplayItem,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckIn: (AnimeEntity) -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (AnimeEntity, SwipeAction) -> Unit,
) {
    when (item) {
        is LibraryDisplayItem.AnimeItem -> AnimeListCard(
            anime = item.anime,
            settings = settings,
            trackerSettings = trackerSettings,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
            onCheckIn = { onCheckIn(item.anime) },
            swipeEnabled = swipeEnabled,
            onSwipe = onSwipe,
        )
        is LibraryDisplayItem.SeriesItem -> SeriesListCard(
            item = item,
            settings = settings,
            trackerSettings = trackerSettings,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}

@Composable
internal fun SeriesStackedCover(
    item: LibraryDisplayItem.SeriesItem,
    trackerSettings: TrackerSettings,
    modifier: Modifier = Modifier,
    showCountPill: Boolean = true,
    pillPadding: Dp = 8.dp,
) {
    val cornerShape = RoundedCornerShape(trackerSettings.coverCornerDp.dp)
    val backgroundContainer = MaterialTheme.colorScheme.primaryContainer

    val memberCovers = item.members
        .mapNotNull { it.coverUrl }
        .filter { it.isNotBlank() }
        .distinct()
    val mainCover = item.coverUrl

    val covers = remember(item.members, mainCover) {
        buildList {
            if (!mainCover.isNullOrBlank()) add(mainCover)
            memberCovers.forEach { url ->
                if (url != mainCover && size < 3) add(url)
            }
        }
    }

    Box(modifier = modifier) {
        if (covers.size >= 3) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = 12f
                        translationY = -6f
                        scaleX = 0.91f
                        scaleY = 0.91f
                        alpha = 0.55f
                    }
                    .clip(cornerShape)
                    .background(backgroundContainer),
            ) {
                TrackerCoverImage(covers[2], item.title, Modifier.fillMaxSize(), trackerSettings)
            }
        }
        if (covers.size >= 2) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = 6f
                        translationY = -3f
                        scaleX = 0.95f
                        scaleY = 0.95f
                        alpha = 0.78f
                    }
                    .clip(cornerShape)
                    .background(backgroundContainer),
            ) {
                TrackerCoverImage(covers[1], item.title, Modifier.fillMaxSize(), trackerSettings)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(cornerShape)
                .background(backgroundContainer),
        ) {
            if (!mainCover.isNullOrBlank()) {
                TrackerCoverImage(mainCover, item.title, Modifier.fillMaxSize(), trackerSettings)
            } else {
                Text(
                    item.title.take(2),
                    modifier = Modifier.align(Alignment.Center),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (showCountPill && trackerSettings.showCoverSeriesCount) {
            val hasQuickEdit = LocalSeriesQuickEdit.current != null
            val safePillPadding = maxOf(
                pillPadding,
                (trackerSettings.coverCornerDp.coerceAtLeast(0f) * 0.42f).coerceAtMost(14f).dp,
            )
            InfoPill(
                text = seriesCountLabel(item, trackerSettings),
                settings = trackerSettings,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = safePillPadding,
                        top = safePillPadding,
                        end = if (hasQuickEdit) 50.dp else safePillPadding,
                    ),
            )
        }
        if (showCountPill) {
            val editInset = maxOf(2f, (trackerSettings.coverCornerDp.coerceAtLeast(0f) * 0.38f) - 6f).dp
            SeriesQuickEditButton(item, Modifier.align(Alignment.TopEnd).padding(top = editInset, end = editInset))
        }

    }
}

@Composable
internal fun SeriesListCard(
    item: LibraryDisplayItem.SeriesItem,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val densityScale = settings.densityFor("tracker").scale
    val itemScale = settings.densityFor("tracker").itemScale
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
            .semantics { this.selected = selected }
            .libraryItemClick(item, onLongClick, onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Box {
        Row(
            modifier = Modifier.padding((12 * densityScale).dp),
            horizontalArrangement = Arrangement.spacedBy((14 * densityScale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SeriesStackedCover(
                item = item,
                trackerSettings = trackerSettings,
                modifier = Modifier.size((68 * itemScale).dp, (92 * itemScale).dp),
                showCountPill = false,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy((5 * densityScale).dp)) {
                if (settings.showTitle) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                SeriesMetadataLine(item, trackerSettings)
                item.series.description?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            InfoPill(seriesCountLabel(item, trackerSettings), trackerSettings)
            SeriesQuickEditButton(item)
        }
        SelectionOverlay(selected, Alignment.CenterEnd)
        }
    }
}

@Composable
internal fun LargeSeriesCard(
    item: LibraryDisplayItem.SeriesItem,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val densityScale = settings.densityFor("tracker").scale
    val itemScale = settings.densityFor("tracker").itemScale
    val metadata = seriesMetadataLabels(item, trackerSettings.showSubjectType, trackerSettings.showUpdateWeekday)
    val description = item.series.description?.takeIf(String::isNotBlank)
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
            .semantics { this.selected = selected }
            .libraryItemClick(item, onLongClick, onClick),
        shape = RoundedCornerShape(trackerSettings.coverCornerDp.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height((210 * itemScale).dp),
            ) {
                SeriesStackedCover(
                    item = item,
                    trackerSettings = trackerSettings,
                    modifier = Modifier.fillMaxSize(),
                    showCountPill = true,
                    pillPadding = (10 * densityScale).dp,
                )
                SelectionOverlay(selected)
            }
            if (settings.showTitle || metadata.isNotEmpty() || description != null) Column(
                Modifier.padding((18 * densityScale).dp),
                verticalArrangement = Arrangement.spacedBy((8 * densityScale).dp),
            ) {
                if (settings.showTitle) {
                    Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                SeriesMetadataLine(item, trackerSettings, labels = metadata)
                description?.let {
                    Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun PosterSeriesCard(
    item: LibraryDisplayItem.SeriesItem,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val densityScale = settings.densityFor("tracker").scale
    val belowMetadata = seriesMetadataLabels(item, trackerSettings.showCoverSubjectType, trackerSettings.showCoverUpdateWeekday)
    ElevatedCard(
        modifier = Modifier.semantics { this.selected = selected }
            .libraryItemClick(item, onLongClick, onClick),
        shape = RoundedCornerShape(trackerSettings.coverCornerDp.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(settings.coverAspectRatio.ratio),
            ) {
                SeriesStackedCover(
                    item = item,
                    trackerSettings = trackerSettings,
                    modifier = Modifier.fillMaxSize(),
                    showCountPill = true,
                    pillPadding = (7 * densityScale).dp,
                )
                if (settings.showTitle && settings.coverTitlePosition == CoverTitlePosition.OVERLAY) {
                    Text(
                        item.title,
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.3f to Color.Black.copy(alpha = trackerSettings.infoDarkBackgroundOpacity * 0.4f),
                                    1f to Color.Black.copy(alpha = trackerSettings.infoDarkBackgroundOpacity),
                                ),
                            )
                            .padding(
                                horizontal = (9 * densityScale).dp,
                                vertical = (7 * densityScale).dp,
                            ),
                        color = Color.White.copy(alpha = trackerSettings.infoTextOpacity),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                SelectionOverlay(selected)
            }
            val hasBelowContent = (settings.showTitle && settings.coverTitlePosition == CoverTitlePosition.BELOW) ||
                belowMetadata.isNotEmpty()
            if (hasBelowContent) {
                Column(
                    Modifier
                        .padding(
                            horizontal = (8 * densityScale).dp,
                            vertical = (6 * densityScale).dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy((2 * densityScale).dp),
                ) {
                    if (settings.showTitle && settings.coverTitlePosition == CoverTitlePosition.BELOW) {
                        Text(
                            item.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    SeriesMetadataLine(
                        item = item,
                        settings = trackerSettings,
                        showSubjectType = trackerSettings.showCoverSubjectType,
                        showUpdateWeekday = trackerSettings.showCoverUpdateWeekday,
                        labels = belowMetadata,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CompactSeriesRow(
    item: LibraryDisplayItem.SeriesItem,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val densityScale = settings.densityFor("tracker").scale
    val itemScale = settings.densityFor("tracker").itemScale
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
            .semantics { this.selected = selected }
            .libraryItemClick(item, onLongClick, onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Box {
        Row(
            Modifier.padding(
                horizontal = (10 * densityScale).dp,
                vertical = (7 * densityScale).dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((10 * densityScale).dp),
        ) {
            SeriesStackedCover(
                item = item,
                trackerSettings = trackerSettings,
                modifier = Modifier.size((38 * itemScale).dp, (52 * itemScale).dp),
                showCountPill = false,
            )
            if (settings.showTitle) {
                Text(item.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            } else {
                Spacer(Modifier.weight(1f))
            }
            InfoPill(seriesCountLabel(item, trackerSettings), trackerSettings)
            SeriesQuickEditButton(item)
        }
        SelectionOverlay(selected, Alignment.CenterEnd)
        }
    }
}

@Composable
internal fun AnimeListCard(
    anime: AnimeEntity,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckIn: () -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (AnimeEntity, SwipeAction) -> Unit,
) {
    if (settings.frontendMode == com.animeow.app.ui.theme.FrontendMode.LEGACY) {
        ClassicAnimeListCard(anime, settings, trackerSettings, selected, onClick, onLongClick, onCheckIn, swipeEnabled, onSwipe)
        return
    }
    val densityScale = settings.densityFor("tracker").scale
    val itemScale = settings.densityFor("tracker").itemScale
    SwipeActionContainer(
        anime = anime,
        startAction = settings.swipeStartAction,
        endAction = settings.swipeEndAction,
        enabled = swipeEnabled,
        hapticEnabled = settings.hapticFeedback,
        soundEnabled = settings.soundFeedback,
        onAction = onSwipe,
    ) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .libraryItemClick(LibraryDisplayItem.AnimeItem(anime), onLongClick, onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Box {
        Row(
            modifier = Modifier.padding((12 * densityScale).dp),
            horizontalArrangement = Arrangement.spacedBy((14 * densityScale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimeCover(
                anime,
                width = (68 * itemScale).dp,
                height = (92 * itemScale).dp,
                trackerSettings = trackerSettings,
                showProgress = settings.showProgress,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy((2 * densityScale).dp)) {
                settings.cardInfoOrder.forEach { field ->
                    when (field) {
                        CardInfoField.TITLE -> if (settings.showTitle) {
                            Text(
                                text = anime.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        CardInfoField.METADATA -> AnimeMetadataLine(anime, trackerSettings)
                        CardInfoField.STATUS -> if (settings.showStatus) {
                            val sColor = trackerStatusColor(anime.status)
                            WatchStatusBadge(anime.status, sColor, trackerSettings, filled = settings.statusChipFilled, style = MaterialTheme.typography.labelMedium)
                        }
                        CardInfoField.PROGRESS -> if ((settings.showProgress && trackerSettings.progressPlacement == CardProgressPlacement.DEFAULT)) {
                            Column {
                                Text(
                                    progressLabel(anime),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (anime.totalEpisodes > 0) {
                                    LinearProgressIndicator(
                                        progress = { (anime.watchedEpisodes.toFloat() / anime.totalEpisodes).coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .padding(top = 4.dp, bottom = 2.dp)
                                            .fillMaxWidth(0.85f)
                                            .height(3.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        drawStopIndicator = {},
                                    )
                                }
                            }
                        }
                        CardInfoField.RATING -> if (settings.showRating && (anime.rating != null || !anime.ratingGrade.isNullOrBlank())) {
                            com.animeow.app.ui.icons.RatingText(
                                text = ratingLabel(anime), ratingIcon = settings.ratingIconStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            val finished = anime.totalEpisodes > 0 && anime.watchedEpisodes >= anime.totalEpisodes
            FilledTonalButton(
                onClick = onCheckIn,
                enabled = !finished,
                modifier = Modifier.semantics {
                    contentDescription = if (finished) "${anime.title} 已看完" else "${anime.title} 进度加一"
                },
                contentPadding = PaddingValues(
                    horizontal = (14 * densityScale.coerceAtLeast(0.82f)).dp,
                    vertical = (10 * densityScale.coerceAtLeast(0.82f)).dp,
                ),
            ) { Text(if (finished) "✓" else "+1") }
        }
        SelectionOverlay(selected, Alignment.CenterEnd)
        }
    }
    }
}

@Composable
internal fun ClassicAnimeListCard(
    anime: AnimeEntity, settings: AppearanceSettings, trackerSettings: TrackerSettings, selected: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onCheckIn: () -> Unit,
    swipeEnabled: Boolean, onSwipe: (AnimeEntity, SwipeAction) -> Unit,
) {
    val scale = settings.densityFor("tracker").itemScale
    SwipeActionContainer(anime, settings.swipeStartAction, settings.swipeEndAction, swipeEnabled,
        settings.hapticFeedback, settings.soundFeedback, onSwipe) {
        Surface(modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }
            .libraryItemClick(LibraryDisplayItem.AnimeItem(anime), onLongClick, onClick),
            shape = MaterialTheme.shapes.large, color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
            Box {
                Column {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AnimeCover(anime, width = (72 * scale).dp, height = (108 * scale).dp, trackerSettings = trackerSettings, showProgress = settings.showProgress)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (settings.showTitle) Text(anime.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            AnimeMetadataLine(anime, trackerSettings)
                            if (settings.showStatus) WatchStatusBadge(anime.status, trackerStatusColor(anime.status), trackerSettings, filled = settings.statusChipFilled, style = MaterialTheme.typography.labelMedium)
                            if (settings.showRating && (anime.rating != null || !anime.ratingGrade.isNullOrBlank())) com.animeow.app.ui.icons.RatingText(ratingLabel(anime), ratingIcon = settings.ratingIconStyle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if ((settings.showProgress && trackerSettings.progressPlacement == CardProgressPlacement.DEFAULT)) Text(progressLabel(anime), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else Spacer(Modifier.weight(1f))
                                FilledTonalIconButton(onClick = onCheckIn, enabled = anime.totalEpisodes <= 0 || anime.watchedEpisodes < anime.totalEpisodes) {
                                    Icon(AppIcons.Add, "${anime.title} 进度加一")
                                }
                            }
                        }
                    }
                    if ((settings.showProgress && trackerSettings.progressPlacement == CardProgressPlacement.DEFAULT) && anime.totalEpisodes > 0) LinearProgressIndicator(
                        progress = { (anime.watchedEpisodes.toFloat() / anime.totalEpisodes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp), color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                }
                SelectionOverlay(selected)
            }
        }
    }
}

@Composable
internal fun LargeAnimeCard(
    anime: AnimeEntity,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckIn: () -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (AnimeEntity, SwipeAction) -> Unit,
) {
    val densityScale = settings.densityFor("tracker").scale
    val itemScale = settings.densityFor("tracker").itemScale
    SwipeActionContainer(
        anime = anime,
        startAction = settings.swipeStartAction,
        endAction = settings.swipeEndAction,
        enabled = swipeEnabled,
        hapticEnabled = settings.hapticFeedback,
        soundEnabled = settings.soundFeedback,
        onAction = onSwipe,
    ) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .libraryItemClick(LibraryDisplayItem.AnimeItem(anime), onLongClick, onClick),
        shape = RoundedCornerShape(trackerSettings.coverCornerDp.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (anime.coverOrientation in listOf("portrait", "landscape")) Modifier.aspectRatio(com.animeow.app.data.local.coverAspectRatio(anime.coverOrientation, 2f / 3f)) else Modifier.height((210 * itemScale).dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                if (!anime.coverUrl.isNullOrBlank()) {
                    TrackerCoverImage(
                        model = anime.coverUrl,
                        contentDescription = anime.title,
                        modifier = Modifier.fillMaxSize(),
                        trackerSettings = trackerSettings,
                    )
                }
                CoverProgressOverlay(anime, settings.showProgress, trackerSettings)
                SelectionOverlay(selected)
            }
            Column(
                modifier = Modifier.padding((18 * densityScale).dp),
                verticalArrangement = Arrangement.spacedBy((8 * densityScale).dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy((4 * densityScale).dp),
                    ) {
                        settings.cardInfoOrder.forEach { field ->
                            when (field) {
                                CardInfoField.TITLE -> if (settings.showTitle) {
                                    Text(anime.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                }
                                CardInfoField.METADATA -> AnimeMetadataLine(anime, trackerSettings)
                                CardInfoField.STATUS -> if (settings.showStatus) {
                                    val sColor = trackerStatusColor(anime.status)
                                    WatchStatusBadge(anime.status, sColor, trackerSettings, filled = settings.statusChipFilled)
                                }
                                CardInfoField.PROGRESS -> if ((settings.showProgress && trackerSettings.progressPlacement == CardProgressPlacement.DEFAULT)) {
                                    Text(progressLabel(anime), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                CardInfoField.RATING -> if (settings.showRating && hasAnimeRating(anime)) {
                                    com.animeow.app.ui.icons.RatingText(
                                        ratingLabel(anime), ratingIcon = settings.ratingIconStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    }
                    FilledTonalButton(onClick = onCheckIn) { Text("+1 集") }
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PosterAnimeCard(
    anime: AnimeEntity,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (AnimeEntity, SwipeAction) -> Unit,
) {
    val densityScale = settings.densityFor("tracker").scale
    SwipeActionContainer(
        anime = anime,
        startAction = settings.swipeStartAction,
        endAction = settings.swipeEndAction,
        enabled = swipeEnabled,
        hapticEnabled = settings.hapticFeedback,
        soundEnabled = settings.soundFeedback,
        onAction = onSwipe,
    ) {
    ElevatedCard(
        modifier = Modifier.semantics { this.selected = selected }
            .libraryItemClick(LibraryDisplayItem.AnimeItem(anime), onLongClick, onClick),
        shape = RoundedCornerShape(trackerSettings.coverCornerDp.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column {
            val defaultRatio = settings.coverAspectRatio.ratio
            val isFramedLandscapeInPortraitGrid = anime.coverOrientation == "landscape" && defaultRatio < 1f
            val boxRatio = if (isFramedLandscapeInPortraitGrid) {
                defaultRatio
            } else {
                com.animeow.app.data.local.coverAspectRatio(anime.coverOrientation, defaultRatio)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(boxRatio)
                    .background(
                        if (isFramedLandscapeInPortraitGrid) Color(0xFF141A24)
                        else MaterialTheme.colorScheme.primaryContainer,
                    ),
            ) {
                if (!anime.coverUrl.isNullOrBlank()) {
                    if (isFramedLandscapeInPortraitGrid) {
                        AsyncImage(
                            model = anime.coverUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            alpha = 0.28f,
                        )
                        TrackerCoverImage(
                            model = anime.coverUrl,
                            contentDescription = anime.title,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f),
                            trackerSettings = trackerSettings,
                        )
                    } else {
                        TrackerCoverImage(
                            model = anime.coverUrl,
                            contentDescription = anime.title,
                            modifier = Modifier.fillMaxSize(),
                            trackerSettings = trackerSettings,
                        )
                    }
                } else {
                    Text(
                        anime.title.take(2),
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                        fontWeight = FontWeight.Bold,
                    )
                }
                PosterBadges(
                    anime = anime,
                    settings = settings,
                    trackerSettings = trackerSettings,
                    selected = selected,
                    includeBottomBar = false,
                )
                val titleOnCover = settings.showTitle && settings.coverTitlePosition == CoverTitlePosition.OVERLAY
                val showCoverProgress = settings.showProgress && trackerSettings.showCoverProgress &&
                    trackerSettings.progressPlacement == CardProgressPlacement.COVER_BOTTOM
                val progressWithTitle = titleOnCover && showCoverProgress
                val showBottomBadgeBar = settings.coverBadgeStyle == CoverBadgeStyle.BOTTOM_BAR &&
                    hasVisiblePosterBadges(anime, settings, trackerSettings)
                if (titleOnCover || showCoverProgress || showBottomBadgeBar) {
                    val horizontalPadding = maxOf(9f * densityScale, trackerSettings.coverCornerDp.coerceAtLeast(0f) * 0.38f).dp
                    Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        if (titleOnCover) {
                            val backgroundAlpha = if (progressWithTitle) trackerSettings.coverProgressAlpha else trackerSettings.infoDarkBackgroundOpacity
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.verticalGradient(
                                            0f to Color.Transparent,
                                            0.3f to Color.Black.copy(alpha = backgroundAlpha * 0.6f),
                                            1f to Color.Black.copy(alpha = backgroundAlpha),
                                        ),
                                    )
                                    .padding(
                                        horizontal = horizontalPadding,
                                        vertical = (5 * densityScale).dp,
                                    ),
                            ) {
                                Text(
                                    anime.title,
                                    color = Color.White.copy(alpha = trackerSettings.infoTextOpacity),
                                    maxLines = if (boxRatio > 1.2f && showBottomBadgeBar) 1 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (progressWithTitle) {
                                    Text(progressLabel(anime), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        } else if (showCoverProgress) {
                            CoverProgressOverlayContent(anime, trackerSettings)
                        }
                        if (showBottomBadgeBar) {
                            PosterBottomBadgeBar(
                                anime = anime,
                                settings = settings,
                                trackerSettings = trackerSettings,
                            )
                        }
                    }
                }
                SelectionOverlay(selected)
            }
            val titleBelow = settings.showTitle && settings.coverTitlePosition == CoverTitlePosition.BELOW
            val progressBelow = settings.showProgress && trackerSettings.showCoverProgress &&
                trackerSettings.progressPlacement == CardProgressPlacement.DEFAULT
            val metadataBelow = trackerSettings.showCoverSubjectType ||
                (trackerSettings.showCoverUpdateWeekday && animeUpdateWeekday(anime) != null)
            if (titleBelow || progressBelow || metadataBelow) {
                Column(
                    modifier = Modifier
                        .padding(
                            horizontal = (8 * densityScale).dp,
                            vertical = (3 * densityScale).dp,
                        ),
                ) {
                    if (titleBelow) {
                        Text(
                            anime.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (progressBelow || metadataBelow) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (progressBelow) Text(
                                progressLabel(anime), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (metadataBelow) AnimeMetadataLine(
                                anime = anime,
                                settings = trackerSettings,
                                showSubjectType = trackerSettings.showCoverSubjectType,
                                showUpdateWeekday = trackerSettings.showCoverUpdateWeekday,
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

internal fun coverBadgeInset(
    badgeStyle: CoverBadgeStyle,
    coverCornerDp: Float,
): PaddingValues {
    val safeCorner = coverCornerDp.coerceAtLeast(0f) * 0.42f
    return when (badgeStyle) {
        CoverBadgeStyle.EDGE -> {
            val edgePad = (coverCornerDp.coerceAtLeast(0f) * 0.38f).coerceIn(0f, 10f).dp
            PaddingValues(edgePad)
        }
        CoverBadgeStyle.CORNER -> PaddingValues(maxOf(4f, safeCorner).coerceAtMost(12f).dp)
        else -> PaddingValues(maxOf(7f, safeCorner).coerceAtMost(14f).dp)
    }
}

internal fun hasVisiblePosterBadges(
    anime: AnimeEntity,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
): Boolean {
    val showStatus = settings.showStatus && trackerSettings.showCoverStatus
    val showRating = settings.showRating && trackerSettings.showCoverRating && hasAnimeRating(anime)
    return showStatus || showRating
}

@Composable
internal fun PosterBottomBadgeBar(
    anime: AnimeEntity,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    statusColor: Color = trackerStatusColor(anime.status),
    modifier: Modifier = Modifier,
) {
    val showStatus = settings.showStatus && trackerSettings.showCoverStatus
    val showRating = settings.showRating && trackerSettings.showCoverRating && hasAnimeRating(anime)
    if (!showStatus && !showRating) return
    val cornerSafeHorizontal = maxOf(8f * trackerSettings.infoScale, trackerSettings.coverCornerDp.coerceAtLeast(0f) * 0.42f).dp
    val cornerSafeBottom = maxOf(5f * trackerSettings.infoScale, trackerSettings.coverCornerDp.coerceAtLeast(0f) * 0.3f).dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = trackerSettings.infoDarkBackgroundOpacity))
            .padding(
                start = cornerSafeHorizontal,
                end = cornerSafeHorizontal,
                top = (5 * trackerSettings.infoScale).dp,
                bottom = cornerSafeBottom,
            ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showStatus) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                WatchStatusBadge(anime.status, statusColor, trackerSettings)
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (showRating) {
            com.animeow.app.ui.components.CoverRatingBadge(
                ratingLabel(anime),
                settings,
                trackerSettings,
                textStyle = scaledInfoTextStyle(trackerSettings),
            )
        }
    }
}

@Composable
internal fun BoxScope.PosterBadges(
    anime: AnimeEntity,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    statusColor: Color = trackerStatusColor(anime.status),
    selected: Boolean = false,
    includeBottomBar: Boolean = true,
) {
    val showStatus = settings.showStatus && trackerSettings.showCoverStatus
    val showRating = settings.showRating && trackerSettings.showCoverRating && hasAnimeRating(anime)
    if (!showStatus && !showRating) return
    if (settings.coverBadgeStyle == CoverBadgeStyle.BOTTOM_BAR) {
        if (includeBottomBar) {
            PosterBottomBadgeBar(
                anime = anime,
                settings = settings,
                trackerSettings = trackerSettings,
                statusColor = statusColor,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        return
    }
    val padding = coverBadgeInset(settings.coverBadgeStyle, trackerSettings.coverCornerDp)
    Row(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(padding)
            .padding(end = if (selected) 30.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (showStatus) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.TopStart) {
                WatchStatusBadge(anime.status, statusColor, trackerSettings)
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (showRating) {
            com.animeow.app.ui.components.CoverRatingBadge(
                text = ratingLabel(anime),
                settings = settings,
                trackerSettings = trackerSettings,
                textStyle = scaledInfoTextStyle(trackerSettings),
            )
        }
    }
}

internal fun ratingLabel(anime: AnimeEntity): String =
    normalizeAnimeRatingGrade(anime.ratingGrade) ?: formatAnimeRating(anime.rating)

internal fun hasAnimeRating(anime: AnimeEntity): Boolean =
    anime.rating != null || !anime.ratingGrade.isNullOrBlank()

@Composable
internal fun trackerStatusColor(status: String): Color =
    LocalTrackerStatusColors.current[status]?.let(::Color) ?: when (status.trim()) {
        "在看" -> Color(0xFF2196F3)
        "看完" -> Color(0xFF4CAF50)
        "未看", "想看" -> Color(0xFFFF9800)
        "弃坑" -> Color(0xFFF44336)
        else -> MaterialTheme.colorScheme.primary
    }

@Composable
internal fun CompactAnimeRow(
    anime: AnimeEntity,
    settings: AppearanceSettings,
    trackerSettings: TrackerSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckIn: () -> Unit,
    swipeEnabled: Boolean,
    onSwipe: (AnimeEntity, SwipeAction) -> Unit,
) {
    val densityScale = settings.densityFor("tracker").scale
    val itemScale = settings.densityFor("tracker").itemScale
    SwipeActionContainer(
        anime = anime,
        startAction = settings.swipeStartAction,
        endAction = settings.swipeEndAction,
        enabled = swipeEnabled,
        hapticEnabled = settings.hapticFeedback,
        soundEnabled = settings.soundFeedback,
        onAction = onSwipe,
    ) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .libraryItemClick(LibraryDisplayItem.AnimeItem(anime), onLongClick, onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Box {
        Row(
            modifier = Modifier.padding(
                horizontal = (10 * densityScale).dp,
                vertical = (7 * densityScale).dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((10 * densityScale).dp),
        ) {
            AnimeCover(
                anime,
                width = (38 * itemScale).dp,
                height = (52 * itemScale).dp,
                trackerSettings = trackerSettings,
                showProgress = settings.showProgress,
            )
            Column(modifier = Modifier.weight(1f)) {
                if (settings.showTitle) {
                    Text(
                        anime.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                    )
                }
                AnimeMetadataLine(anime, trackerSettings)
            }
            if (settings.showStatus || (settings.showProgress && trackerSettings.progressPlacement == CardProgressPlacement.DEFAULT) || settings.showRating && hasAnimeRating(anime)) {
                Column(horizontalAlignment = Alignment.End) {
                    if (settings.showStatus) {
                        val sColor = trackerStatusColor(anime.status)
                        WatchStatusBadge(anime.status, sColor, trackerSettings, filled = settings.statusChipFilled)
                    }
                    if (settings.showRating && hasAnimeRating(anime)) {
                        com.animeow.app.ui.icons.RatingText(
                            ratingLabel(anime), ratingIcon = settings.ratingIconStyle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if ((settings.showProgress && trackerSettings.progressPlacement == CardProgressPlacement.DEFAULT)) {
                        Text(
                            if (anime.totalEpisodes > 0) "${anime.watchedEpisodes}/${anime.totalEpisodes}" else anime.watchedEpisodes.toString(),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            IconButton(
                onClick = onCheckIn,
                modifier = Modifier.size((36 * itemScale.coerceAtLeast(1f)).dp),
            ) {
                Icon(AppIcons.Add, contentDescription = "+1 集")
            }
        }
        SelectionOverlay(selected, Alignment.CenterEnd)
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeActionContainer(
    anime: AnimeEntity,
    startAction: SwipeAction,
    endAction: SwipeAction,
    enabled: Boolean,
    hapticEnabled: Boolean,
    soundEnabled: Boolean,
    onAction: (AnimeEntity, SwipeAction) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled || startAction == SwipeAction.NONE && endAction == SwipeAction.NONE) {
        content()
        return
    }
    val feedback = com.animeow.app.ui.components.LocalActionFeedback.current
    val scope = rememberCoroutineScope()
    val commitGuard = remember { CardSwipeCommitGuard() }
    val state = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * 0.78f },
    )
    SwipeToDismissBox(
        state = state,
        modifier = Modifier.requireDeliberateCardSwipe(commitGuard),
        enableDismissFromStartToEnd = startAction != SwipeAction.NONE,
        enableDismissFromEndToStart = endAction != SwipeAction.NONE,
        onDismiss = { dismissValue ->
            val shouldCommit = commitGuard.isArmed
            commitGuard.isArmed = false
            val action = when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> startAction
                SwipeToDismissBoxValue.EndToStart -> endAction
                SwipeToDismissBoxValue.Settled -> SwipeAction.NONE
            }
            if (shouldCommit && action != SwipeAction.NONE) {
                if (hapticEnabled || soundEnabled) feedback.confirm()
                onAction(anime, action)
            }
            scope.launch {
                state.snapTo(SwipeToDismissBoxValue.Settled)
            }
        },
        backgroundContent = {
            val fromStart = state.targetValue == SwipeToDismissBoxValue.StartToEnd
            val action = if (fromStart) startAction else endAction
            val color = when (action) {
                SwipeAction.INCREMENT -> MaterialTheme.colorScheme.tertiary
                SwipeAction.CYCLE_STATUS -> MaterialTheme.colorScheme.primary
                SwipeAction.EDIT -> MaterialTheme.colorScheme.secondary
                SwipeAction.TRASH -> MaterialTheme.colorScheme.error
                SwipeAction.NONE -> MaterialTheme.colorScheme.surfaceVariant
            }
            val icon = when (action) {
                SwipeAction.INCREMENT -> AppIcons.AddCircle
                SwipeAction.CYCLE_STATUS -> AppIcons.Sync
                SwipeAction.EDIT -> AppIcons.Edit
                SwipeAction.TRASH -> AppIcons.Delete
                SwipeAction.NONE -> AppIcons.Close
            }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.large)
                    .background(color.copy(alpha = 0.17f))
                    .padding(horizontal = 22.dp),
                horizontalArrangement = if (fromStart) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = color)
                Spacer(Modifier.size(7.dp))
                Text(action.displayName, color = color, fontWeight = FontWeight.SemiBold)
            }
        },
        content = { content() },
    )
}

internal class CardSwipeCommitGuard {
    var isArmed: Boolean = false
}

/**
 * A fast page-navigation flick must never be enough to mutate a library item.
 * Card actions are armed only after a long, strongly horizontal drag; velocity
 * alone cannot bypass this guard as it can with SwipeToDismissBox's fling logic.
 */
internal fun Modifier.requireDeliberateCardSwipe(
    guard: CardSwipeCommitGuard,
): Modifier = pointerInput(guard) {
    val minimumCommitDistance = 160.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        guard.isArmed = false
        var totalX = 0f
        var totalY = 0f
        var pressed = true
        while (pressed) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val delta = change.position - change.previousPosition
            totalX += delta.x
            totalY += delta.y
            val requiredDistance = max(size.width * 0.78f, minimumCommitDistance)
            if (
                abs(totalX) >= requiredDistance &&
                abs(totalX) > abs(totalY) * 1.5f
            ) {
                guard.isArmed = true
            }
            pressed = change.pressed
        }
    }
}

@Composable
internal fun AnimeCover(
    anime: AnimeEntity,
    width: Dp,
    height: Dp,
    trackerSettings: TrackerSettings,
    showProgress: Boolean = true,
) {
    val isLandscape = anime.coverOrientation == "landscape"
    val effectiveWidth = if (isLandscape) (height.value * 1.24f).dp else width
    val ratio = com.animeow.app.data.local.coverAspectRatio(anime.coverOrientation, width.value / height.value)
    Box {
        LibraryCover(anime.title, anime.coverUrl, effectiveWidth, effectiveWidth / ratio, trackerSettings)
        CoverProgressOverlay(anime, showProgress, trackerSettings, compact = effectiveWidth.value < 54)
    }
}

@Composable
internal fun CoverProgressOverlayContent(
    anime: AnimeEntity,
    tracker: TrackerSettings,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = tracker.coverProgressAlpha.coerceIn(0.15f, 0.95f)))
            .semantics { contentDescription = "${anime.title}，${progressLabel(anime)}" }
            .padding(horizontal = 3.dp, vertical = 2.dp),
    ) {
        Text(
            if (compact) "${anime.watchedEpisodes}" else "${anime.watchedEpisodes} / ${anime.totalEpisodes.takeIf { it > 0 } ?: "?"}",
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
        )
        if (anime.totalEpisodes > 0) LinearProgressIndicator(
            progress = { (anime.watchedEpisodes.toFloat() / anime.totalEpisodes).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = Color.White,
            trackColor = Color.DarkGray,
            drawStopIndicator = {},
        )
    }
}

@Composable
internal fun BoxScope.CoverProgressOverlay(anime: AnimeEntity, enabled: Boolean, tracker: TrackerSettings, compact: Boolean = false) {
    if (!enabled || !tracker.showCoverProgress || tracker.progressPlacement != CardProgressPlacement.COVER_BOTTOM) return
    CoverProgressOverlayContent(anime, tracker, compact, Modifier.align(Alignment.BottomCenter))
}

@Composable
internal fun LibraryCover(
    title: String,
    coverUrl: String?,
    width: Dp,
    height: Dp,
    trackerSettings: TrackerSettings,
) {
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(trackerSettings.coverCornerDp.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (!coverUrl.isNullOrBlank()) {
            TrackerCoverImage(
                model = coverUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                trackerSettings = trackerSettings,
            )
        } else {
            Text(
                text = title.take(2),
                modifier = Modifier.padding(6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
internal fun TrackerCoverImage(
    model: String,
    contentDescription: String?,
    modifier: Modifier,
    trackerSettings: TrackerSettings,
) {
    val colorFilter = remember(trackerSettings.coverSaturation) {
        ColorFilter.colorMatrix(
            ColorMatrix().apply { setToSaturation(trackerSettings.coverSaturation) },
        )
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier.graphicsLayer { alpha = trackerSettings.coverImageOpacity },
        contentScale = when (trackerSettings.coverFitMode) {
            CoverFitMode.CROP -> ContentScale.Crop
            CoverFitMode.FIT -> ContentScale.Fit
            CoverFitMode.STRETCH -> ContentScale.FillBounds
        },
        colorFilter = colorFilter,
    )
}

@Composable
internal fun InfoPill(
    text: String,
    settings: TrackerSettings,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = settings.infoLightBackgroundOpacity),
                RoundedCornerShape(settings.infoCornerDp.dp),
            )
            .padding(
                horizontal = (7 * settings.infoScale).dp,
                vertical = (3 * settings.infoScale).dp,
            ),
        style = scaledInfoTextStyle(settings),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = settings.infoTextOpacity),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun AnimeMetadataLine(
    anime: AnimeEntity,
    settings: TrackerSettings,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    showSubjectType: Boolean = settings.showSubjectType,
    showUpdateWeekday: Boolean = settings.showUpdateWeekday,
) {
    val labels = buildList {
        if (showSubjectType) add(subjectTypeLabel(anime.subjectType))
        if (showUpdateWeekday) animeUpdateWeekday(anime)?.let { add(weekdayLabel(it)) }
    }
    if (labels.isNotEmpty()) {
        Text(
            labels.joinToString(" · "),
            color = color,
            style = scaledInfoTextStyle(settings),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SeriesMetadataLine(
    item: LibraryDisplayItem.SeriesItem,
    settings: TrackerSettings,
    showSubjectType: Boolean = settings.showSubjectType,
    showUpdateWeekday: Boolean = settings.showUpdateWeekday,
    labels: List<String> = seriesMetadataLabels(item, showSubjectType, showUpdateWeekday),
) {
    if (labels.isNotEmpty()) {
        Text(
            labels.joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = scaledInfoTextStyle(settings),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun seriesMetadataLabels(
    item: LibraryDisplayItem.SeriesItem,
    showSubjectType: Boolean,
    showUpdateWeekday: Boolean,
): List<String> = buildList {
        if (showSubjectType) {
            item.members.map { subjectTypeLabel(it.subjectType) }.distinct().takeIf(List<String>::isNotEmpty)
                ?.let { add(it.joinToString("/")) }
        }
        if (showUpdateWeekday) {
            item.members.mapNotNull(::animeUpdateWeekday).distinct().sorted()
                .takeIf(List<Int>::isNotEmpty)
                ?.let { add(it.joinToString("/") { day -> weekdayLabel(day) }) }
        }
        if (item.members.isEmpty() && (showSubjectType || showUpdateWeekday)) add("暂无成员")
}

@Composable
internal fun scaledInfoTextStyle(settings: TrackerSettings) =
    MaterialTheme.typography.labelSmall.copy(
        fontSize = MaterialTheme.typography.labelSmall.fontSize * settings.infoScale,
        lineHeight = MaterialTheme.typography.labelSmall.lineHeight * settings.infoScale,
    )

internal fun seriesCountLabel(item: LibraryDisplayItem.SeriesItem, settings: TrackerSettings): String =
    if (settings.showSeriesCount) "系列 · ${item.members.size} 部" else "系列"

internal fun subjectTypeLabel(value: String): String = when (normalizeSubjectType(value)) {
    "book" -> "书籍"
    else -> "动画"
}

internal fun weekdayLabel(day: Int): String = when (day) {
    1 -> "周一更新"
    2 -> "周二更新"
    3 -> "周三更新"
    4 -> "周四更新"
    5 -> "周五更新"
    6 -> "周六更新"
    7, 0 -> "周日更新"
    else -> "每周更新"
}

// Explicit broadcast schedules take precedence; older records keep their reminder/date fallback.
internal fun animeUpdateWeekday(anime: AnimeEntity): Int? =
    anime.broadcastDay?.takeIf { it in 1..7 } ?: anime.reminderDay ?: anime.airDate.toAirDateWeekdayOrNull()

internal fun String?.toAirDateWeekdayOrNull(): Int? {
    val normalized = this?.trim()?.take(10)?.takeIf { it.length == 10 } ?: return null
    val date = runCatching { LocalDate.parse(normalized) }.getOrNull() ?: return null
    return date.dayOfWeek.value
}

@Composable
internal fun readableGridColumns(requested: Int): Int {
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val preferred = requested.coerceIn(2, 6)
    if (fontScale < 1.2f) return preferred
    val availableWidth = LocalWindowInfo.current.containerSize.width / density.density - 32
    val fittingColumns = ((availableWidth + 12) / (96f * fontScale + 12)).toInt().coerceAtLeast(2)
    return preferred.coerceAtMost(fittingColumns)
}

@Composable
internal fun BentoCollectionHeader(
    title: String,
    subtitle: String,
    scope: HomeBottomScope?,
    layout: BentoCollectionLayout,
    onScopeSelected: (HomeBottomScope) -> Unit,
    onLayoutSelected: (BentoCollectionLayout) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val controls: @Composable () -> Unit = {
        if (scope != null) HomeBottomScopeToggle(value = scope, onSelected = onScopeSelected)
        BentoCollectionLayoutToggle(value = layout, onSelected = onLayoutSelected)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 360.dp || fontScale >= 1.2f) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionHeader(title, subtitle)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) { controls() }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(title, subtitle, Modifier.weight(1f).padding(end = 8.dp))
                controls()
            }
        }
    }
}

@Composable
internal fun SectionHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    if (com.animeow.app.ui.legacy.LocalFrontendMode.current == com.animeow.app.ui.theme.FrontendMode.LEGACY) {
        com.animeow.app.ui.legacy.ClassicSectionHeading(title, subtitle, modifier)
        return
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, modifier = Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
