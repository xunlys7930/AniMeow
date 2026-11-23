package com.animeow.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.data.community.SensitiveWord
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.ui.community.CommunitySensitiveWordsDialog
import com.animeow.app.ui.components.PageNavigationBar
import com.animeow.app.ui.tracker.PinnedAnimeShelf
import com.animeow.app.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ManagementPagingInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val large = AppearanceSettings(fontScale = 1.4f, useDynamicColor = false, themeMode = ThemeMode.LIGHT, motionLevel = MotionLevel.NONE)

    @Test fun knownPageJumpRejectsOutOfRangeAndKeepsButtonsUsableAtLargeFont() {
        val page = mutableStateOf(1)
        compose.setContent { AniMeowTheme(large) { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                PageNavigationBar(page.value, 120, page.value < 120, false, { page.value = it })
            }
        } } }
        compose.onNodeWithText("上一页").assertIsNotEnabled()
        compose.onNodeWithTag("page-jump").performClick()
        compose.onNodeWithTag("page-number-input").performTextReplacement("121")
        compose.onNodeWithText("跳转").assertIsNotEnabled()
        compose.onNodeWithTag("page-number-input").performTextReplacement("80")
        compose.onNodeWithText("跳转").performClick()
        compose.runOnIdle { assertEquals(80, page.value) }
        compose.onNodeWithText("上一页").performClick()
        compose.runOnIdle { assertEquals(79, page.value) }
        compose.onNodeWithText("下一页").performClick()
        screenshot("pagination-large")
    }

    @Test fun unknownPageJumpShowsCancelableLocationInsteadOfInventingTotal() {
        val loading = mutableStateOf(false)
        var requested = 0
        var cancelled = false
        compose.setContent { AniMeowTheme(large) { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                PageNavigationBar(1, null, true, loading.value, { requested = it; loading.value = true },
                    status = if (loading.value) "正在读取第 4 页…" else null,
                    onCancel = { cancelled = true; loading.value = false })
            }
        } } }
        compose.onNodeWithTag("page-jump").performClick()
        compose.onNodeWithTag("page-number-input").performTextReplacement("35")
        compose.onNodeWithText("跳转").performClick()
        compose.runOnIdle { assertEquals(35, requested) }
        compose.onNodeWithText("下一页").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
        compose.onNodeWithTag("page-jump").assertIsEnabled()
    }

    @Test fun sensitiveWordsHaveIndependentEditorSearchJumpAndDeletionConfirmation() {
        val page = mutableStateOf(1)
        val search = mutableStateOf("")
        val busy = mutableStateOf(false)
        var deleted = 0L
        var imported: List<String> = emptyList()
        val all = (1..125).map { SensitiveWord(it.toLong(), "测试词 $it", "2026-09-13") }
        compose.setContent { AniMeowTheme(large) {
            CommunitySensitiveWordsDialog(all.drop((page.value - 1) * 50).take(50), all.size, page.value, search.value, false,
                {}, {}, { deleted = it }, { imported = it }, { search.value = it }, { page.value = it }, busy = busy.value)
        } }
        compose.onNodeWithText("批量导入").assertIsDisplayed()
        screenshot("sensitive-words-large")
        compose.onNodeWithTag("page-jump").performClick()
        compose.onNodeWithTag("page-number-input").performTextReplacement("3")
        compose.onNodeWithText("跳转").performClick()
        compose.onNodeWithText("测试词 101").assertIsDisplayed()
        compose.onNodeWithText("下一页").assertIsNotEnabled()
        compose.onNodeWithContentDescription("删除敏感词 测试词 101").performClick()
        compose.runOnIdle { assertEquals(0L, deleted) }
        compose.onNodeWithText("删除", substring = false).performClick()
        compose.runOnIdle { assertEquals(101L, deleted) }
        compose.onNodeWithText("批量导入").performClick()
        compose.onNodeWithTag("sensitive-editor").performTextInput("示例甲\n示例乙,示例甲")
        compose.onNodeWithText("导入", substring = false).performClick()
        compose.runOnIdle { assertEquals(listOf("示例甲", "示例乙"), imported); busy.value = true }
        compose.onNodeWithText("批量导入").assertIsNotEnabled()
        compose.onNodeWithContentDescription("删除敏感词 测试词 101").assertIsNotEnabled()
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithTag("sensitive-search").performTextInput("示例")
        compose.runOnIdle { assertEquals("示例", search.value) }
    }

    @Test fun hidingPinBadgesKeepsPinnedItemsAndLongPressUnpinAvailable() {
        val show = mutableStateOf(true)
        val anime = AnimeEntity(id = 17, title = "置顶示例", status = "在看")
        var unpinned: Long? = null
        var opened: Long? = null
        compose.setContent { AniMeowTheme(large) { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                PinnedAnimeShelf(listOf(anime), { opened = it }, { unpinned = it }, show.value)
            }
        } } }
        compose.onNodeWithTag("pin-badge-17", useUnmergedTree = true).assertExists()
        compose.runOnIdle { show.value = false }
        compose.onNodeWithTag("pin-badge-17", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("置顶示例").performTouchInput { longClick() }
        compose.runOnIdle { assertNull(opened); assertNull(unpinned) }
        compose.onNodeWithText("取消置顶", substring = false).performClick()
        compose.runOnIdle { assertEquals(17L, unpinned) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(220)
        val root = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-213-review").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
