package com.animeow.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    TRACKER(
        route = "tracker",
        label = "追番",
        icon = Icons.Outlined.MovieFilter,
        selectedIcon = Icons.Rounded.MovieFilter,
    ),
    DISCOVERY(
        route = "discovery",
        label = "发现",
        icon = Icons.Outlined.Explore,
        selectedIcon = Icons.Rounded.Explore,
    ),
    CALENDAR(
        route = "calendar",
        label = "日历",
        icon = Icons.Outlined.CalendarMonth,
        selectedIcon = Icons.Rounded.CalendarMonth,
    ),
    COMMUNITY(
        route = "community",
        label = "社区",
        icon = Icons.Outlined.Groups,
        selectedIcon = Icons.Rounded.Groups,
    ),
    STATISTICS(
        route = "statistics",
        label = "统计",
        icon = Icons.Outlined.Insights,
        selectedIcon = Icons.Rounded.Insights,
    ),
    PROFILE(
        route = "profile",
        label = "我的",
        icon = Icons.Outlined.Person,
        selectedIcon = Icons.Rounded.Person,
    ),
}
