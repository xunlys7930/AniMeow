package com.animeow.app.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.FileSystem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PromotionPreferencesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun explicitLegacyOnceWaitsUntilShownAndChoicesPersist() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // Okio uses a replacing atomic move on Windows, unlike java.io.File.renameTo.
            val store = PreferenceDataStoreFactory.create(scope = scope,
                storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { folder.root.resolve("promotion.preferences_pb").absolutePath.toPath() })
            val preferences = PromotionPreferences(store)
            preferences.setMode(PromotionMode.ONCE)
            assertFalse(preferences.registerLaunch())
            assertTrue(preferences.registerLaunch())
            // Another modal can delay this prompt beyond the second launch without losing it.
            assertTrue(preferences.registerLaunch())
            preferences.markShown()
            assertFalse(preferences.registerLaunch())
            preferences.setMode(PromotionMode.EVERY_LAUNCH)
            assertTrue(PromotionPreferences(store).registerLaunch())
            preferences.setMode(PromotionMode.NEVER)
            repeat(3) { assertFalse(PromotionPreferences(store).registerLaunch()) }
        } finally { scope.cancel() }
    }

    @Test fun occasionalPolicyRequiresBothTimeAndLaunchesAndHandlesClockRollback() {
        val shownAt = 100_000L
        val due = shownAt + PROMOTION_COOLDOWN_MILLIS
        assertFalse(shouldShowPromotion(4, PromotionMode.OCCASIONAL, false))
        assertTrue(shouldShowPromotion(5, PromotionMode.OCCASIONAL, false))
        assertFalse(shouldShowPromotion(10, PromotionMode.OCCASIONAL, true, shownAt, 5, due - 1))
        assertFalse(shouldShowPromotion(9, PromotionMode.OCCASIONAL, true, shownAt, 5, due))
        assertTrue(shouldShowPromotion(10, PromotionMode.OCCASIONAL, true, shownAt, 5, due))
        assertFalse(shouldShowPromotion(100, PromotionMode.OCCASIONAL, true, shownAt, 5, shownAt - 1))
        assertFalse(shouldShowPromotion(100, PromotionMode.OCCASIONAL, true))
        assertFalse(shouldShowPromotion(100, PromotionMode.NEVER, false))
        assertFalse(shouldShowPromotion(100, PromotionMode.ONCE, true))
        assertTrue(shouldShowPromotion(2, PromotionMode.EVERY_LAUNCH, true))
    }

    @Test fun defaultWaitsForFiveLaunchesAndCooldownStartsAtActualDisplay() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope,
                storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { folder.root.resolve("occasional.preferences_pb").absolutePath.toPath() })
            var now = 1_000L
            val preferences = PromotionPreferences(store) { now }
            repeat(4) { assertFalse(preferences.registerLaunch()) }
            assertTrue(preferences.registerLaunch())
            // Eligibility alone does not consume the prompt if another modal delays it.
            now += PROMOTION_COOLDOWN_MILLIS
            assertTrue(preferences.registerLaunch())
            preferences.markShown()
            now += PROMOTION_COOLDOWN_MILLIS
            repeat(4) { assertFalse(PromotionPreferences(store) { now }.registerLaunch()) }
            assertTrue(preferences.registerLaunch())
            preferences.markShown()
            repeat(5) { assertFalse(preferences.registerLaunch()) }
            now += PROMOTION_COOLDOWN_MILLIS
            assertTrue(preferences.registerLaunch())
        } finally { scope.cancel() }
    }

    @Test fun previouslyShownDefaultStartsCooldownOnUpgradeButExplicitChoicesSurvive() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope,
                storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { folder.root.resolve("upgrade.preferences_pb").absolutePath.toPath() })
            store.edit {
                it[longPreferencesKey("launch_count")] = 100
                it[booleanPreferencesKey("shown_once")] = true
            }
            var now = 1_000L
            assertFalse(PromotionPreferences(store) { now }.registerLaunch())
            repeat(5) { assertFalse(PromotionPreferences(store) { now }.registerLaunch()) }
            now += PROMOTION_COOLDOWN_MILLIS
            assertTrue(PromotionPreferences(store) { now }.registerLaunch())
            // These are persisted values from the old app, not new in-memory choices.
            for (legacyMode in listOf("ONCE", "NEVER")) {
                store.edit { it[stringPreferencesKey("display_mode")] = legacyMode }
                assertFalse(PromotionPreferences(store) { now }.registerLaunch())
            }
            store.edit { it[stringPreferencesKey("display_mode")] = "EVERY_LAUNCH" }
            assertTrue(PromotionPreferences(store) { now }.registerLaunch())
        } finally { scope.cancel() }
    }

    @Test fun launchCounterNeverWrapsBackToTheFirstLaunch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope,
                storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { folder.root.resolve("limit.preferences_pb").absolutePath.toPath() })
            store.edit { it[longPreferencesKey("launch_count")] = Long.MAX_VALUE }
            assertTrue(PromotionPreferences(store).registerLaunch())
        } finally { scope.cancel() }
    }
}
