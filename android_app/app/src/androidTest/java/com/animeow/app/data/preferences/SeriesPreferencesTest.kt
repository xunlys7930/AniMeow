package com.animeow.app.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeriesPreferencesTest {
    @get:Rule val temporaryFolder = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

    @Test
    fun reopeningTheStoreRestoresIndependentSeriesViews() = runBlocking {
        val file = temporaryFolder.newFile("views.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = SeriesPreferences(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        val expected = SeriesDisplaySettings(SeriesSort.RATING, false, 4, "在看")
        try {
            preferences.update(10) { expected }
            preferences.update(11) { it.copy(sort = SeriesSort.TITLE, statusFilter = "重温") }
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = SeriesPreferences(PreferenceDataStoreFactory.create(scope = reopenedScope, produceFile = { file }))
            assertEquals(expected, reopened.observe(10).first())
            assertEquals(SeriesSort.TITLE, reopened.observe(11).first().sort)
            assertEquals("重温", reopened.observe(11).first().statusFilter)
            assertEquals(SeriesDisplaySettings(), reopened.observe(12).first())
        } finally {
            reopenedScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun rapidIndependentChangesAreNotLost() = runBlocking {
        val file = temporaryFolder.newFile("rapid.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val preferences = SeriesPreferences(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            val sort = launch { preferences.update(1) { it.copy(sort = SeriesSort.PROGRESS) } }
            val columns = launch { preferences.update(1) { it.copy(columnsOverride = 5) } }
            sort.join(); columns.join()
            assertEquals(SeriesDisplaySettings(sort = SeriesSort.PROGRESS, columnsOverride = 5), preferences.observe(1).first())
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
