package com.animeow.app.data.preferences

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.animeow.app.AniMeowApplication
import com.animeow.app.ui.tracker.TrackerViewModel
import com.animeow.app.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationProfileImportTest {
    @Test fun trackerFollowsCustomizationAndPreservesSuccessiveQuickChanges() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AniMeowApplication>()
        val preferences = TrackerPreferences(application)
        val original = preferences.snapshot()
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            val initial = original.copy(showRecentAdded = false, showStatusCount = false,
                homeModuleOrder = HomeModule.entries.toList(), showCoverRating = true, showCoverProgress = true)
            preferences.save(initial)
            lateinit var model: TrackerViewModel
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[TrackerViewModel::class.java]
            }
            withTimeout(5_000) { model.trackerSettings.first { !it.showStatusCount } }
            preferences.save(initial.copy(showRecentAdded = true,
                homeModuleOrder = HomeModule.normalize(listOf(HomeModule.RECENT))))
            withTimeout(5_000) { model.trackerSettings.first { it.showRecentAdded && it.homeModuleOrder.first() == HomeModule.RECENT } }
            instrumentation.runOnMainSync {
                model.setShowCoverRating(false)
                model.setShowCoverProgress(false)
            }
            val saved = withTimeout(5_000) { preferences.settings.first { !it.showCoverRating && !it.showCoverProgress } }
            assertTrue(saved.showRecentAdded)
            assertEquals(HomeModule.RECENT, saved.homeModuleOrder.first())
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            preferences.save(original)
        }
    }

    @Test fun importingAnOldThemeKeepsTheLocalInterfaceChoiceCompleted() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appearance = AppearancePreferences(context)
        val repository = ConfigurationProfileRepository(context, appearance)
        val original = repository.snapshotCurrent()
        val file = File.createTempFile("legacy-appearance-", ".json", context.cacheDir)
        try {
            appearance.restore(original.appearance.copy(themeMode = ThemeMode.LIGHT, frontendModeChoiceMade = true))
            file.writeText("""{"name":"旧版主题","settings":{"themeMode":"dark"}}""")
            val imported = repository.import(Uri.fromFile(file), persist = false)
            assertFalse(imported.settings.frontendModeChoiceMade)
            repository.apply(imported)
            val applied = appearance.snapshot()
            assertEquals(ThemeMode.DARK, applied.themeMode)
            assertTrue(applied.frontendModeChoiceMade)
        } finally {
            repository.applyConfiguration(original)
            appearance.restore(original.appearance)
            file.delete()
        }
    }
}
