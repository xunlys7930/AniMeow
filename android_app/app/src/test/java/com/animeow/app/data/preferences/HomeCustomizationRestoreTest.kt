package com.animeow.app.data.preferences

import android.app.Application
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.HomeLayout
import com.animeow.app.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class HomeCustomizationRestoreTest {
    @Test fun restoringTheOpeningAppearancePreservesThemeChangesFromOtherSettings() = runBlocking {
        val preferences = AppearancePreferences(RuntimeEnvironment.getApplication())
        val opening = AppearanceSettings(homeLayout = HomeLayout.BENTO, showProgress = false, showRating = false)
        preferences.restore(opening)
        preferences.applyCollectionPosterStyle()
        preferences.setThemeMode(ThemeMode.DARK)
        preferences.restoreHomeCustomization(opening)
        val restored = preferences.snapshot()
        assertEquals(opening.homeLayout, restored.homeLayout)
        assertEquals(opening.showProgress, restored.showProgress)
        assertEquals(opening.showRating, restored.showRating)
        assertEquals(opening.coverTitlePosition, restored.coverTitlePosition)
        assertEquals(ThemeMode.DARK, restored.themeMode)
    }
}
