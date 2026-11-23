package com.animeow.app.data.preferences

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CharacterRelationPreferencesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun singleEndedPairsAndRemovalKeepOtherSavedPairs() = withStore { preferences ->
        preferences.add(" 师父 ", " 徒弟 ")
        preferences.add("监护人", "")
        assertEquals(listOf(RelationRolePreset("师父", "徒弟"), RelationRolePreset("监护人", "")), preferences.presets.first())
        preferences.remove(RelationRolePreset("师父", "徒弟"))
        assertEquals(listOf(RelationRolePreset("监护人", "")), preferences.presets.first())
    }

    @Test fun diskReopenRetainsCustomPreset() = runBlocking {
        val path = folder.root.resolve("reopen.preferences_pb").absolutePath.toPath()
        val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writer = PreferenceDataStoreFactory.create(scope = writerScope,
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { path })
        try { CharacterRelationPreferences(writer).add("师父", "徒弟") }
        finally { writerScope.coroutineContext[Job]!!.cancelAndJoin() }
        val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reader = PreferenceDataStoreFactory.create(scope = readerScope,
                storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { path })
            assertEquals(listOf(RelationRolePreset("师父", "徒弟")), CharacterRelationPreferences(reader).presets.first())
        } finally { readerScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun duplicateAndEmptyPairsAreRejectedWithoutChangingSavedPairs() = withStore { preferences ->
        preferences.add("师父", "徒弟")
        assertTrue(runCatching { preferences.add(" 师父 ", "徒弟") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { preferences.add("", " ") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { preferences.add("老师", "学生") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf(RelationRolePreset("师父", "徒弟")), preferences.presets.first())
    }

    @Test fun capacityRejectsOverflowAndAllowsAddingAfterRemoval() = withStore { preferences ->
        repeat(20) { preferences.add("称谓$it", "对应$it") }
        assertTrue(runCatching { preferences.add("新称谓", "新对应") }.exceptionOrNull() is IllegalArgumentException)
        preferences.remove(RelationRolePreset("称谓0", "对应0"))
        preferences.add("新称谓", "新对应")
        assertEquals(20, preferences.presets.first().size)
        assertTrue(RelationRolePreset("新称谓", "新对应") in preferences.presets.first())
    }

    private fun withStore(block: suspend (CharacterRelationPreferences) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope,
                storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { folder.root.resolve("relations.preferences_pb").absolutePath.toPath() })
            block(CharacterRelationPreferences(store))
        } finally { scope.cancel() }
    }
}
