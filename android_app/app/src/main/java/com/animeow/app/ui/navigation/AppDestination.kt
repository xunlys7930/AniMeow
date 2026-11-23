package com.animeow.app.ui.navigation

import com.animeow.app.ui.icons.AppIcons

import com.animeow.app.ui.icons.IconGlyph

enum class AppDestination(
    val route: String,
    val label: String,
    val icon: IconGlyph,
    val selectedIcon: IconGlyph,
) {
    TRACKER(
        route = "tracker",
        label = "收藏",
        icon = AppIcons.Tracker,
        selectedIcon = AppIcons.Tracker.selected(),
    ),
    DISCOVERY(
        route = "discovery",
        label = "发现",
        icon = AppIcons.Discover,
        selectedIcon = AppIcons.Discover.selected(),
    ),
    CALENDAR(
        route = "calendar",
        label = "日历",
        icon = AppIcons.Calendar,
        selectedIcon = AppIcons.Calendar.selected(),
    ),
    COMMUNITY(
        route = "community",
        label = "社区",
        icon = AppIcons.Community,
        selectedIcon = AppIcons.Community.selected(),
    ),
    STATISTICS(
        route = "statistics",
        label = "统计",
        icon = AppIcons.Statistics,
        selectedIcon = AppIcons.Statistics.selected(),
    ),
    PROFILE(
        route = "profile",
        label = "我的",
        icon = AppIcons.Profile,
        selectedIcon = AppIcons.Profile.selected(),
    ),
}
