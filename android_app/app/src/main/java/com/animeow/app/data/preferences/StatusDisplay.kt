package com.animeow.app.data.preferences

import com.animeow.app.data.local.WatchStatusEntity
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.CoverBadgeStyle
import com.animeow.app.ui.theme.CoverTitlePosition
import com.animeow.app.ui.theme.HomeLayout

enum class CollectionPosterPreset(val displayName: String, val progressPlacement: CardProgressPlacement) {
    COMPACT("紧凑海报 · 下方进度", CardProgressPlacement.DEFAULT),
    CLASSIC("旧版封面 · 封面内进度", CardProgressPlacement.COVER_BOTTOM);

    fun apply(settings: TrackerSettings): TrackerSettings = settings.copy(
        progressPlacement = progressPlacement,
        showCoverProgress = true,
        showCoverRating = true,
        showCoverSubjectType = false,
        showCoverUpdateWeekday = this == COMPACT,
    )
}

internal fun AppearanceSettings.withCollectionPosterStyle() = copy(
    homeLayout = HomeLayout.POSTER_WALL,
    coverTitlePosition = CoverTitlePosition.OVERLAY,
    coverBadgeStyle = CoverBadgeStyle.CORNER,
    showTitle = true,
    showProgress = true,
    showRating = true,
)

enum class CardProgressPlacement(val displayName: String) {
    DEFAULT("现有位置"), COVER_BOTTOM("封面底部"), HIDDEN("隐藏进度显示");

    companion object {
        fun fromStorage(value: String?) = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

internal fun parseStatusIds(raw: String?): Set<Long> = raw.orEmpty().split(',')
    .mapNotNull { it.trim().toLongOrNull()?.takeIf { id -> id > 0 } }.toSet()

internal fun visibleWatchStatuses(statuses: List<WatchStatusEntity>, hidden: Set<Long>): List<WatchStatusEntity> =
    statuses.filterNot { it.id in hidden }

internal fun statusOptionNames(statuses: List<WatchStatusEntity>, stored: List<String>, hidden: Set<Long>): List<String> {
    val hiddenNames = statuses.filter { it.id in hidden }.map { it.name }.toSet()
    return (visibleWatchStatuses(statuses, hidden).map { it.name } + stored)
        .filter { it.isNotBlank() && it !in hiddenNames }.distinct()
}
