package com.animeow.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.preferences.AppearancePreferences
import com.animeow.app.ui.anime.*
import com.animeow.app.ui.theme.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProgressInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val base = AppearanceSettings(useDynamicColor = false, motionLevel = MotionLevel.NONE, themeMode = ThemeMode.LIGHT)
    private val work = AnimeEntity(id = 9913, title = "星海旅人", watchedEpisodes = 8, totalEpisodes = 12, status = "在看")

    @Composable private fun Example(settings: AppearanceSettings, anime: AnimeEntity, onCheckIn: () -> Unit, onEdit: () -> Unit) {
        AniMeowTheme(settings) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Text("观看进度 · ${settings.detailProgressMode.displayName}", Modifier.padding(20.dp),
                        style = MaterialTheme.typography.titleLarge)
                    DetailModulesLayout(Modifier.weight(1f).fillMaxWidth(), settings.detailLayout, listOf(DetailModule.PROGRESS),
                        anime, emptyList(), emptyList(), null, emptyList(), settings.characterImageAlignment,
                        onEdit, onCheckIn, {}, {}, {}, {}, {}, {}, {}, {})
                    AnimeDetailQuickBar(anime, onCheckIn, onEdit, {}, false, {})
                }
            }
        }
    }

    @Test fun defaultKeepsVisibleButtonsAndBothModesFitLightDarkAndLargeText() {
        val settings = mutableStateOf(base)
        val anime = mutableStateOf(work)
        var edits = 0
        compose.setContent { Example(settings.value, anime.value, { anime.value = anime.value.copy(watchedEpisodes = anime.value.watchedEpisodes + 1) }, { edits++ }) }
        compose.onAllNodesWithText("+1 集").assertCountEquals(2)
        compose.onAllNodesWithText("修改进度").assertCountEquals(2)
        compose.onAllNodesWithText("+1 集")[1].performClick()
        compose.runOnIdle { assertEquals(9, anime.value.watchedEpisodes) }
        compose.onAllNodesWithText("修改进度")[1].performClick()
        compose.runOnIdle { assertEquals(1, edits) }
        for (mode in DetailProgressMode.entries) for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (scale in listOf(1f, 1.4f)) {
            compose.runOnIdle { settings.value = base.copy(detailProgressMode = mode, themeMode = theme, fontScale = scale); anime.value = work }
            compose.onNodeWithText("状态").assertIsDisplayed()
            compose.onNodeWithText("追番提醒").assertIsDisplayed()
            screenshot("detail-progress-${mode.storageKey}-${theme.storageKey}-${(scale * 100).toInt()}")
        }
    }

    @Test fun combinedTapAndLongPressRemainDistinctAcrossAllFiveLayouts() {
        val settings = mutableStateOf(base.copy(detailProgressMode = DetailProgressMode.COMBINED))
        val anime = mutableStateOf(work)
        var increments = 0
        var edits = 0
        compose.setContent { Example(settings.value, anime.value, {
            increments++; anime.value = anime.value.copy(watchedEpisodes = anime.value.watchedEpisodes + 1)
        }, { edits++ }) }
        for ((index, layout) in DetailLayout.entries.withIndex()) {
            compose.runOnIdle { settings.value = settings.value.copy(detailLayout = layout); anime.value = work }
            compose.onAllNodesWithTag("combined-progress")[0].performClick()
            compose.onAllNodesWithTag("combined-progress")[0].performTouchInput { longClick() }
            compose.onAllNodesWithTag("combined-progress")[1].performTouchInput { longClick() }
            compose.onNodeWithContentDescription("修改进度").performClick()
            compose.runOnIdle {
                assertEquals(index + 1, increments)
                assertEquals((index + 1) * 3, edits)
                assertEquals(9, anime.value.watchedEpisodes)
            }
        }
    }

    @Test fun completedProgressCanStillBeEditedAndUnknownTotalsCanIncrement() {
        val anime = mutableStateOf(work.copy(watchedEpisodes = 12))
        var increments = 0
        var edits = 0
        compose.setContent { AniMeowTheme(base) { CombinedProgressButton(anime.value, { increments++ }, { edits++ }) } }
        compose.onNodeWithTag("combined-progress").performClick()
        compose.onNodeWithTag("combined-progress").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(0, increments); assertEquals(2, edits); anime.value = work.copy(totalEpisodes = 0) }
        compose.onNodeWithText("已看 8 集").assertIsDisplayed()
        compose.onNodeWithTag("combined-progress").performClick()
        compose.runOnIdle { assertEquals(1, increments); anime.value = work.copy(watchedEpisodes = Int.MAX_VALUE, totalEpisodes = 0) }
        compose.onNodeWithTag("combined-progress").performClick()
        compose.runOnIdle { assertEquals(1, increments); assertEquals(3, edits) }
    }

    @Test fun exactProgressShowsErrorsRestoresDraftAndAcceptsKeyboardConfirmation() {
        var saved: Int? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AniMeowTheme(base) { ProgressEditDialog(work, {}, { saved = it }) } }
        compose.onNodeWithTag("exact-progress-input").performTextReplacement("13")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("不能超过总集数；如资料有误，请在「编辑完整资料」中修改总集数。").assertIsDisplayed()
        compose.onNodeWithTag("exact-progress-input").performTextReplacement("9")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("exact-progress-input").assertTextContains("9")
        screenshot("exact-progress-edit")
        compose.onNodeWithTag("exact-progress-input").performImeAction()
        compose.runOnIdle { assertEquals(9, saved) }
    }

    @Test fun editorShowsExplicitFieldsAndClampsOnlyStepperActions() {
        val settings = mutableStateOf(base)
        val watched = mutableStateOf("8")
        val total = mutableStateOf("12")
        val status = mutableStateOf("在看")
        compose.setContent { AniMeowTheme(settings.value) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text("编辑作品 · 星海旅人", Modifier.padding(bottom = 16.dp), style = MaterialTheme.typography.titleLarge)
                    ProgressEditorCard(watched.value, total.value, status.value, listOf("未看", "在看", "看完", "想看", "搁置", "弃坑"),
                        { watched.value = it.filter(Char::isDigit) }, { total.value = it.filter(Char::isDigit) }, { status.value = it })
                }
            }
        } }
        compose.onNodeWithTag("editor-progress-increment").performClick()
        compose.onNodeWithText("已看 9 / 12 集").assertIsDisplayed()
        compose.onNodeWithText("已看集数").performTextReplacement("12")
        compose.onNodeWithTag("editor-progress-increment").assertIsNotEnabled()
        compose.onNodeWithText("已看集数").performTextReplacement("0")
        compose.onNodeWithTag("editor-progress-decrement").assertIsNotEnabled()
        compose.onNodeWithText("总集数").performTextReplacement("0")
        compose.onNodeWithTag("editor-progress-increment").performClick()
        compose.runOnIdle { assertEquals("1", watched.value); assertEquals("在看", status.value) }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (scale in listOf(1f, 1.4f)) {
            compose.runOnIdle { watched.value = "8"; total.value = "12"; settings.value = base.copy(fontScale = scale, themeMode = theme) }
            compose.onNodeWithText("已看集数").assertIsDisplayed()
            compose.onNodeWithText("总集数").assertIsDisplayed()
            compose.onNodeWithText("弃坑").assertIsDisplayed()
            screenshot("editor-progress-${theme.storageKey}-${(scale * 100).toInt()}")
        }
    }

    @Test fun progressPreferenceSurvivesDataStoreReload() = runBlocking {
        val preferences = AppearancePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = preferences.snapshot()
        try {
            preferences.restore(original.copy(detailProgressMode = DetailProgressMode.COMBINED))
            assertEquals(DetailProgressMode.COMBINED, AppearancePreferences(InstrumentationRegistry.getInstrumentation().targetContext).snapshot().detailProgressMode)
            preferences.restore(original.copy(detailProgressMode = DetailProgressMode.SEPARATE))
            assertEquals(DetailProgressMode.SEPARATE, preferences.snapshot().detailProgressMode)
        } finally { preferences.restore(original) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Allow the Material ripple to finish before capturing the resting appearance.
        Thread.sleep(600)
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "progress-213-review/$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
