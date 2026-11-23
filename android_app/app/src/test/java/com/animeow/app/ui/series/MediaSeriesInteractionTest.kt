package com.animeow.app.ui.series

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.room.Room
import com.animeow.app.data.*
import com.animeow.app.data.games.GameEntity
import com.animeow.app.data.local.AniMeowDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class MediaSeriesInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun draftNameMembersAndOriginalVersionsSurviveStateRestorationAndCanSave() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java)
            .setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.allowMainThreadQueries().build()
        try {
            val work = GameEntity(title = "测试游戏")
            runBlocking { db.gameDao().insert(work) }
            val repository = MediaSeriesRepository(db, MediaSeriesKind.GAME)
            val restoration = StateRestorationTester(compose)
            restoration.setContent { MaterialTheme { MediaSeriesDialog(MediaSeriesKind.GAME, repository,
                listOf(MediaSeriesWork(work.syncId, work.title, work.updatedAt)), {}) } }
            // Room posts its observer work to the Android looper, outside Compose's frame clock.
            compose.waitUntil(5_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                compose.onAllNodesWithText("新建系列").fetchSemanticsNodes().any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
            }
            compose.onNodeWithText("新建系列").performClick()
            compose.onNodeWithText("系列名称").performTextInput("保存的系列草稿")
            compose.onNodeWithText(work.title).performScrollTo().performClick()
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(5_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                compose.onAllNodesWithText("系列名称").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("系列名称").assertTextContains("保存的系列草稿")
            compose.onNodeWithText("成员顺序 · 1 部").assertExists()
            compose.onNodeWithText("保存系列").performClick()
            compose.waitUntil(5_000) { runBlocking { db.mediaSeriesDao().series("GAME").size == 1 } }
            assertEquals("保存的系列草稿", runBlocking { db.mediaSeriesDao().series("GAME").single().name })
            assertEquals(work.syncId, runBlocking { db.mediaSeriesDao().members("GAME").single().workSyncId })
        } finally { db.close() }
    }

    @Test fun reorderAndRemoveHaveVisibleButtonsAndSavingWaitsForAName() {
        var order by mutableStateOf(listOf("one", "two"))
        var name by mutableStateOf("")
        compose.setContent { MaterialTheme { MediaSeriesContent(MediaSeriesKind.AUDIO,
            MediaSeriesSnapshot(emptyList(), emptyList()), listOf(MediaSeriesWork("one", "第一部", "v1"), MediaSeriesWork("two", "第二部", "v2")),
            true, name, order, "", false, null, false, {}, {}, { name = it }, {}, { order = it }, {}, {}) } }
        compose.onNodeWithText("保存系列").assertIsNotEnabled()
        compose.onNodeWithText("系列名称").performTextInput("情景音声系列")
        compose.onNodeWithText("保存系列").assertIsEnabled()
        compose.onNodeWithContentDescription("下移第一部").performScrollTo().performClick()
        assertEquals(listOf("two", "one"), order)
        compose.onNodeWithContentDescription("将第二部移出系列").performScrollTo().performClick()
        assertEquals(listOf("one"), order)
        compose.onNodeWithContentDescription("上移第一部").assertIsNotEnabled()
    }
}
