package com.animeow.app.ui.navigation

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.NavigationLabelMode
import org.junit.Rule
import org.junit.Test

class FloatingNavigationBarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun fiveDestinationsStayReachableAtNarrowWidthWithLargeTextAndMargins() {
        val current = mutableStateOf(AppDestination.TRACKER)
        val mode = mutableStateOf(NavigationLabelMode.SELECTED)
        val destinations = listOf(AppDestination.TRACKER, AppDestination.DISCOVERY, AppDestination.CALENDAR, AppDestination.COMMUNITY, AppDestination.PROFILE)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.4f)) {
                MaterialTheme {
                    FloatingNavigationBar(
                        AppearanceSettings(floatingBarHorizontalMargin = 60, floatingBarHeight = 48, navigationLabelMode = mode.value),
                        destinations, current.value, { current.value = it }, Modifier.width(320.dp), applySystemInsets = false,
                    )
                }
            }
        }
        NavigationLabelMode.entries.forEach { labels ->
            compose.runOnIdle { mode.value = labels }
            destinations.forEach { destination ->
                compose.onNodeWithContentDescription(destination.label)
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
            }
        }
    }
}
