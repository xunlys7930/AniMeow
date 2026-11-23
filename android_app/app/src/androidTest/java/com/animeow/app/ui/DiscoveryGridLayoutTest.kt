package com.animeow.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.R
import com.animeow.app.data.preferences.DiscoveryDisplayConfig
import com.animeow.app.data.preferences.DiscoveryResultLayout
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.RemoteCatalogSource
import com.animeow.app.ui.screens.DiscoveryGridRow
import com.animeow.app.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DiscoveryGridLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val first = RemoteAnime(RemoteCatalogSource.BANGUMI, "1", "一斩苍穹", score = 5.8, airDate = "2026-07-01")
    private val samples = listOf(first,
        first.copy(id = "2", title = "与你相恋到生命尽头", score = 7.0, tags = listOf("校园", "日常")),
        first.copy(id = "3", title = "奇招百出的维多利亚", score = null, airDate = null))

    @Test fun mixedTitleLengthsAndMissingMetadataKeepThreeColumnActionsAligned() {
        val settings = mutableStateOf(AppearanceSettings(themeMode = ThemeMode.LIGHT))
        val display = mutableStateOf(DiscoveryDisplayConfig(layout = DiscoveryResultLayout.GRID, showSource = false))
        var opened: String? = null
        var imported: String? = null
        compose.setContent {
            AniMeowTheme(settings.value) {
                Surface(Modifier.width(320.dp).fillMaxHeight()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("发现 · 标题与操作对齐", style = MaterialTheme.typography.titleLarge)
                        DiscoveryGridRow(samples, display.value, 8.dp, isAdded = { it.id == "3" },
                            onClick = { opened = it.id }, onImport = { imported = it.id })
                    }
                }
            }
        }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (font in listOf(1f, 1.4f)) {
            compose.runOnIdle { settings.value = settings.value.copy(themeMode = theme, fontScale = font) }
            assertButtonsAligned()
            for (label in listOf("加入", "已收录")) compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().indices.forEach { index ->
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onAllNodesWithText(label, useUnmergedTree = true)[index]
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(1, layouts.single().lineCount)
                assertFalse("Action must be fully readable at $font", layouts.single().isLineEllipsized(0))
            }
            saveAppearanceFollowup(compose, "discovery-grid-${theme.storageKey}-${(font * 100).toInt()}")
        }
        compose.onNodeWithContentDescription("将《一斩苍穹》加入资料库").performClick()
        compose.runOnIdle { assertEquals("1", imported); assertNull(opened) }
        compose.onNode(hasClickLabel("查看《与你相恋到生命尽头》详情")).performClick()
        compose.runOnIdle { assertEquals("2", opened) }
        compose.runOnIdle { display.value = display.value.copy(showTags = false, showScore = false, showAirDate = false) }
        assertButtonsAligned()
    }

    @Test fun twoAndFiveColumnsKeepTheirOwnLayoutsAndImportingDisablesOnlyItsAction() {
        val display = mutableStateOf(DiscoveryDisplayConfig(layout = DiscoveryResultLayout.GRID, gridColumns = 2))
        compose.setContent {
            AniMeowTheme(AppearanceSettings(fontScale = 1.4f)) {
                Surface { DiscoveryGridRow(samples.take(2), display.value, 8.dp, importingKey = first.uniqueKey,
                    onClick = {}, onImport = {}, modifier = Modifier.padding(12.dp)) }
            }
        }
        compose.onNodeWithContentDescription("正在加入《一斩苍穹》").assertIsNotEnabled()
        compose.onNodeWithContentDescription("将《与你相恋到生命尽头》加入资料库").assertIsEnabled()
        compose.runOnIdle { display.value = display.value.copy(gridColumns = 5) }
        compose.onNodeWithContentDescription("正在加入《一斩苍穹》").assertIsNotEnabled()
        compose.onNodeWithContentDescription("将《与你相恋到生命尽头》加入资料库").assertIsEnabled()
    }

    @Test fun reviewGridWithLocalCoversAndDifferentTitleLengths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val covers = samples.map { it.copy(coverUrl = "android.resource://${context.packageName}/${R.drawable.preview_cover}") }
        compose.setContent {
            AniMeowTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT)) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("发现", style = MaterialTheme.typography.headlineLarge)
                        Text("本地示例封面 · 三列", style = MaterialTheme.typography.bodySmall)
                        DiscoveryGridRow(covers, DiscoveryDisplayConfig(showSource = false, showTags = false), 8.dp,
                            onClick = {}, onImport = {})
                        DiscoveryGridRow(covers.reversed(), DiscoveryDisplayConfig(showSource = false, showTags = false), 8.dp,
                            onClick = {}, onImport = {})
                    }
                }
            }
        }
        saveAppearanceFollowup(compose, "discovery-covers-light")
    }

    private fun assertButtonsAligned() {
        val bounds = samples.map {
            val description = if (it.id == "3") "打开已收录的《${it.title}》" else "将《${it.title}》加入资料库"
            compose.onNodeWithContentDescription(description).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        bounds.drop(1).forEach {
            // Compose may round the two-line minimum and fallback-font metrics by a pixel.
            val tolerance = with(compose.density) { 1.dp.toPx() }
            assertEquals(bounds.first().top, it.top, tolerance)
            assertEquals(bounds.first().bottom, it.bottom, tolerance)
        }
    }

    private fun hasClickLabel(label: String) = SemanticsMatcher("click label $label") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }
}
