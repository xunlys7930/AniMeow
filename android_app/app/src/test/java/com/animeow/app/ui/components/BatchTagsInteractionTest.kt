package com.animeow.app.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.animeow.app.data.BatchTagDraft
import com.animeow.app.data.local.TagEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatchTagsInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectionIsExplicitAndFailedApplyPreservesAllChoicesForRetry() {
        var attempts = 0
        var applied = emptySet<Long>()
        var removing = false
        var error by mutableStateOf<String?>(null)
        compose.setContent { MaterialTheme {
            BatchTagSheet(listOf(TagEntity(id = 1, name = "标签一"), TagEntity(id = 2, name = "标签二")), 3, false, error,
                onApply = { ids, remove, _ -> attempts++; applied = ids; removing = remove; error = "模拟失败，请重试" }, onDismissRequest = {})
        } }
        compose.onNodeWithTag("batch-tag-apply").assertIsNotEnabled()
        compose.onNodeWithTag("batch-tag-1").performClick()
        compose.onNodeWithTag("batch-tag-2").performClick()
        assertEquals(0, attempts)
        compose.onNodeWithTag("batch-tag-apply").performClick()
        compose.onNodeWithText("模拟失败，请重试").assertExists()
        compose.onNodeWithTag("batch-tag-1").assertIsOn()
        compose.onNodeWithTag("batch-tag-2").assertIsOn()
        compose.onNodeWithText("移除", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("batch-tag-apply").performClick()
        assertEquals(2, attempts)
        assertEquals(setOf(1L, 2L), applied)
        assertTrue(removing)
    }

    @Test fun creatingTagOnlyAddsADraftAndCancelNeverAppliesIt() {
        var calls = 0
        var drafts = emptyList<BatchTagDraft>()
        compose.setContent { MaterialTheme {
            BatchTagSheet(emptyList(), 2, false, null,
                onApply = { _, _, values -> calls++; drafts = values }, onDismissRequest = {})
        } }
        compose.onNodeWithText("新建标签并选中").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("标签名称").performTextInput("待创建标签")
        compose.onNodeWithText("加入本次选择").performClick()
        assertEquals(0, calls)
        compose.onNodeWithText("待创建标签（新建）").assertExists()
        compose.onNodeWithText("取消新建").performClick()
        compose.onNodeWithTag("batch-tag-apply").assertIsNotEnabled()
        assertTrue(drafts.isEmpty())
    }

    @Test fun longListWithLargeTextKeepsApplyVisibleAndReturnsToNewTagForm() {
        var applied = emptySet<Long>()
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.4f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    BatchTagSheet((1L..40L).map { TagEntity(id = it, name = "用于检查长标题和大字体的标签$it") }, 3, false, null,
                        onApply = { ids, _, _ -> applied = ids }, onDismissRequest = {})
                }
            }
        }
        compose.onNodeWithTag("batch-tag-list").performScrollToNode(hasTestTag("batch-tag-40"))
        compose.onNodeWithTag("batch-tag-40").performClick()
        compose.onNodeWithTag("batch-tag-apply").assertIsDisplayed().performClick()
        assertEquals(setOf(40L), applied)
        compose.onNodeWithText("新建标签并选中").performClick()
        compose.onNodeWithText("标签名称").assertIsDisplayed().performTextInput("尚未确认的新标签")
        compose.onNodeWithTag("batch-tag-apply").assertIsNotEnabled()
        compose.onNodeWithText("取消新建").performScrollTo().performClick()
        compose.onNodeWithTag("batch-tag-apply").assertIsEnabled()
    }
}
