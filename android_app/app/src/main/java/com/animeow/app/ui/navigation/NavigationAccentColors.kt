package com.animeow.app.ui.navigation

import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import com.animeow.app.ui.theme.LocalAccentColorScheme

@Composable
internal fun navigationAccentColors() = LocalAccentColorScheme.current.let { accent ->
    if (com.animeow.app.ui.theme.LocalIndependentThemeColor.current == null) NavigationBarItemDefaults.colors()
    else NavigationBarItemDefaults.colors(
        selectedIconColor = accent.onPrimaryContainer,
        selectedTextColor = accent.primary,
        indicatorColor = accent.primaryContainer,
    )
}
