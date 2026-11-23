package com.animeow.app.ui.playback

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.animeow.app.data.community.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36], qualifiers = "w375dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchInteractionUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun confirmedMatchingCanBeReviewedAndClearedWithConfirmation() = scene(tab = "匹配") { _, model ->
        compose.onNodeWithText("已关联作品").assertIsDisplayed()
        compose.onNodeWithText("重新匹配").assertIsDisplayed()
        draw("watch-matching-management")
        compose.onNodeWithText("清除关联").performClick()
        compose.onNodeWithText("清除弹幕与评论关联？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertNotNull(model.state.value.program)
        compose.onNodeWithText("清除关联").performClick()
        compose.onAllNodesWithText("清除关联").onLast().performClick()
        assertNull(model.state.value.program)
        compose.onNodeWithText("已关联作品").assertDoesNotExist()
    }

    @Test fun numericTitleSearchShowsTheSelectedSeasonBeforeOpeningTheConfirmedEpisode() = scene(tab = "匹配") { api, model ->
        api.onSearch = { query ->
            assertEquals("86", query)
            listOf(WatchSubject(302189, "合成分季作品"))
        }
        api.onPrograms = { subject ->
            listOf(watchTestProgram.copy(subjectId = subject, episodeId = 902, title = "合成分季作品",
                episodeTitle = "开篇", number = 29.0))
        }
        compose.onNodeWithText("作品名称、#编号或 Bangumi 链接").performTextInput("86")
        compose.onNodeWithText("查找作品").performClick()
        compose.onNodeWithText("合成分季作品 · #302189").performClick()
        assertEquals(123, model.state.value.program?.subjectId)
        compose.onNodeWithText("合成分季作品 · 本季第 1 集 · 连续第 29 集 · 开篇").performClick()
        assertEquals(902, model.state.value.program?.episodeId)
        compose.onNodeWithText("讨论").assertIsDisplayed().assertIsSelected()
    }

    @Test fun selectingAttackOnTitanFromFarDownShowsLoadingAndRetriesInTheEpisodeStep() = scene(tab = "匹配") { api, model ->
        val selected = WatchSubject(55770, "进击的巨人")
        val first = WatchProgram(55770, 256446, selected.title, "合成第一集标题", number = 1.0)
        val pending = CompletableDeferred<List<WatchProgram>>()
        api.onSearch = { (1..18).map { WatchSubject(60000 + it, "其他候选 $it") } + selected }
        api.onPrograms = { subject -> assertEquals(selected.id, subject); pending.await() }
        compose.onNodeWithText("作品名称、#编号或 Bangumi 链接").performTextInput("进击的巨人")
        compose.onNodeWithText("查找作品").performClick()
        val result = "进击的巨人 · #55770"
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(result))
        compose.onNodeWithText(result).performClick()
        compose.onNodeWithText("确认集数").assertIsDisplayed()
        compose.onNodeWithText("正在读取分集目录…").assertIsDisplayed()
        pending.completeExceptionally(IOException("合成目录读取失败"))
        idle()
        compose.onNodeWithText("合成目录读取失败").assertIsDisplayed()
        api.onPrograms = { listOf(first) }
        compose.onNodeWithText("重试读取集数").performClick()
        compose.onNodeWithText("进击的巨人 · 第 1 集 · 合成第一集标题").performClick()
        assertEquals(256446, model.state.value.program?.episodeId)
        compose.onNodeWithText("讨论").assertIsDisplayed().assertIsSelected()
    }

    @Test fun failedCommentReadsKeepTheUnsentTextAndCanBeRetried() = scene { api, model ->
        api.onList = { _, kind, _, _, _ -> if (kind == "comment") throw java.io.IOException("合成读取失败") else WatchPage(emptyList()) }
        model.loadComments(); idle()
        compose.onNodeWithText("写评论（公开，最多 2000 字）").performTextInput("尚未发送的合成评论")
        compose.onNodeWithText("合成读取失败").assertIsDisplayed()
        draw("watch-comments-error-large")
        api.onList = { _, _, _, _, _ -> WatchPage(emptyList()) }
        compose.onNodeWithText("重新读取").performClick()
        compose.onNodeWithText("合成读取失败").assertDoesNotExist()
        assertEquals("尚未发送的合成评论", model.state.value.draft)
    }

    @Test @Config(qualifiers = "w800dp-h375dp-land-xhdpi")
    fun darkLandscapePanelKeepsTheComposerAndNavigationReachableAtLargeText() = scene(dark = true, narrow = true) { _, model ->
        compose.onNodeWithText("写评论（公开，最多 2000 字）").performTextInput("合成横屏草稿")
        compose.onNodeWithText("发送").assertIsDisplayed().assertIsEnabled()
        draw("watch-comments-dark-landscape-large")
        compose.onNodeWithText("讨论").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("显示弹幕").assertIsDisplayed()
        assertEquals("合成横屏草稿", model.state.value.draft)
    }

    @Test fun reportingRequiresAReasonAndOrdinaryAccountsHaveNoManagementTab() = scene { api, model ->
        api.onList = { _, kind, _, _, _ -> WatchPage(if (kind == "comment") listOf(watchTestItem().copy(authorId = 2, canReport = true)) else emptyList()) }
        model.loadComments(); idle()
        compose.onNodeWithText("管理").assertDoesNotExist()
        compose.onNodeWithText("举报").performScrollTo().performClick()
        compose.onNodeWithText("提交举报").assertIsNotEnabled()
        compose.onNodeWithText("原因").performTextInput("合成举报原因")
        draw("watch-report-reason-large")
        compose.onNodeWithText("提交举报").performClick()
        compose.onNodeWithText("举报已提交，等待管理员处理").assertIsDisplayed()
    }

    @Test fun spoilerAndDeletedCardsDoNotExposeTheirBodyBeforeAnExplicitChoice() {
        var row by mutableStateOf(watchTestItem(content = "合成剧透正文").copy(spoiler = true))
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { host ->
        host.get().setContent { MaterialTheme { WatchCommentCard(row, false, onDelete = {}, onReport = {}, onBlock = {}, onHide = {}, onSeek = {}) } }
        compose.onNodeWithText("合成剧透正文").assertDoesNotExist()
        compose.onNodeWithText("包含剧透，点击展开").performClick()
        compose.onNodeWithText("合成剧透正文").assertIsDisplayed()
        compose.runOnIdle { row = row.copy(unavailable = true) }
        compose.onNodeWithText("合成剧透正文").assertDoesNotExist()
        compose.onNodeWithText("原内容已删除或暂不可查看").assertIsDisplayed()
        }
    }

    @Test fun theManagementEntryWaitsForRoleLoadingAndRequiresAnExplicitDecisionReason() = scene(admin = true, tab = "管理") { _, _ ->
        compose.onNodeWithText("处理").performScrollTo().performClick()
        compose.onNodeWithText("移除内容").assertIsNotEnabled()
        compose.onNodeWithText("保留内容").assertIsNotEnabled()
        compose.onNodeWithText("原因").performTextInput("合成审核原因")
        draw("watch-moderation-large")
        compose.onNodeWithText("取消").performScrollTo().assertIsDisplayed()
        draw("watch-moderation-large-bottom")
        compose.onNodeWithText("移除内容").performScrollTo().assertIsEnabled()
    }

    @Test fun theActualOverlayDrawsThePausedVideoTimestampAndCanBeTurnedOff() {
        var enabled by mutableStateOf(true)
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { host ->
            host.get().setContent { MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    WatchDanmakuOverlay(listOf(watchTestItem().copy(content = "合成弹幕渲染", kind = "danmaku", timeMs = 0)),
                        DanmakuSettings(enabled = enabled, density = 4), observedPosition = 1500, playing = false, position = { 1500L }, Modifier.fillMaxSize())
                }
            } }
            compose.waitForIdle()
            val before = draw("watch-overlay-paused")
            compose.runOnIdle { enabled = false }
            val after = draw("watch-overlay-disabled")
            assertNotEquals(before, after)
        }
    }
    @Test fun aCommunityReferenceDoesNotFallBackToStaleBodyWhenTheOriginalWasRemoved() {
        val api = FakeWatchApi().apply { onItem = { watchTestItem(content = "").copy(unavailable = true, status = "removed") } }
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { host ->
            host.get().setContent { MaterialTheme {
                com.animeow.app.ui.community.WatchReferenceCard(watchTestItem(content = "必须隐藏的过时原文"), overrideApi = api)
            } }
            idle(); compose.onNodeWithText("必须隐藏的过时原文").assertDoesNotExist()
            compose.onNodeWithText("原评论暂不可查看").assertIsDisplayed()
            compose.onNodeWithText("查看原评论").assertDoesNotExist()
            draw("watch-reference-withdrawn")
        }
    }
    @Test fun aFreshCommunityReferencePreservesSpoilerFoldingAndItsReturnLinks() {
        val item = watchTestItem(content = "合成引用剧透").copy(spoiler = true, title = "合成动画", episodeTitle = "第一集", timeMs = 12000)
        val api = FakeWatchApi().apply { onItem = { item } }
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { host ->
            host.get().setContent { MaterialTheme { com.animeow.app.ui.community.WatchReferenceCard(item, overrideApi = api) } }
            idle(); compose.onNodeWithText("合成引用剧透").assertDoesNotExist()
            compose.onNodeWithText("查看原评论").assertIsDisplayed()
            compose.onNodeWithText("本集讨论").assertIsDisplayed()
            compose.onNodeWithText("包含剧透，点击展开").performClick()
            compose.onNodeWithText("合成引用剧透").assertIsDisplayed()
            draw("watch-reference-with-links")
        }
    }
    @Test @Config(qualifiers = "w800dp-h375dp-land-xhdpi")
    fun aQuotedCommunityDraftCanScrollToPublishWithoutPublishingOnOpen() {
        val item = watchTestItem(content = "合成评论引用").copy(title = "合成动画", episodeTitle = "本集讨论")
        val api = FakeWatchApi().apply { onItem = { item } }
        var published = 0
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { host ->
            host.get().setContent { MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1.4f)) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(375.dp).fillMaxHeight()) {
                            com.animeow.app.ui.community.CommunityComposer(text = "", images = emptyList(), draftPresetMedia = emptyList(),
                                maxImages = 4, maxTextLength = 2000, busy = false, busyLabel = null, presetImages = emptyList(), presetDisclaimer = "",
                                onTextChange = {}, onPickImages = {}, onRemoveImage = {}, onRemovePresetMedia = {}, onMoveImage = { _, _ -> },
                                onMovePresetMedia = { _, _ -> }, onCropPresetMedia = {}, onPickPresetMedia = {}, onConfirmPresetSelection = {},
                                onPublish = { published++ }, watchReferenceId = 1, watchApi = api)
                        }
                    }
                }
            } }
            idle(); assertEquals(0, published)
            compose.onNodeWithText("发布").performScrollTo().assertIsDisplayed().assertIsEnabled()
            draw("watch-share-composer-landscape-large")
            compose.onNodeWithText("发布").performClick(); assertEquals(1, published)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun aQuotedCommunityDraftInsideModalBottomSheetDoesNotCrashAndPublishesCleanly() {
        val item = watchTestItem(content = "合成评论引用底部弹窗验证").copy(title = "合成动画", episodeTitle = "第二集")
        val api = FakeWatchApi().apply { onItem = { item } }
        var published = 0
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { host ->
            host.get().setContent { MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    com.animeow.app.ui.components.StableModalBottomSheet(onDismissRequest = {}) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("分享评论到社区")
                            com.animeow.app.ui.community.CommunityComposer(
                                modifier = Modifier.weight(1f, fill = false),
                                text = "", images = emptyList(), draftPresetMedia = emptyList(),
                                maxImages = 4, maxTextLength = 2000, busy = false, busyLabel = null,
                                presetImages = emptyList(), presetDisclaimer = "",
                                onTextChange = {}, onPickImages = {}, onRemoveImage = {}, onRemovePresetMedia = {},
                                onMoveImage = { _, _ -> }, onMovePresetMedia = { _, _ -> }, onCropPresetMedia = {},
                                onPickPresetMedia = {}, onConfirmPresetSelection = {},
                                onPublish = { published++ }, watchReferenceId = 7L, watchApi = api,
                            )
                        }
                    }
                }
            } }
            idle()
            compose.onNodeWithText("分享评论到社区").assertIsDisplayed()
            compose.onNodeWithText("写点转发感想（可选）…").assertIsDisplayed()
            compose.onNodeWithText("发布").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            assertEquals(1, published)
        }
    }

    @Test fun watchDiscussionEntryButtonAppearsForAnimeAndHidesForBooks() {
        val anime = com.animeow.app.data.local.AnimeEntity(id = 11, title = "合成动画", subjectType = "anime")
        val book = com.animeow.app.data.local.AnimeEntity(id = 12, title = "合成轻小说", subjectType = "book")
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { host ->
            host.get().setContent { MaterialTheme {
                Column {
                    WatchDiscussionEntryButton(anime)
                    WatchDiscussionEntryButton(book)
                }
            } }
            idle()
            compose.onAllNodesWithText("作品讨论与单集评论").assertCountEquals(1)
        }
    }

    private fun scene(dark: Boolean = false, narrow: Boolean = false, admin: Boolean = false, tab: String = "讨论", run: (FakeWatchApi, WatchViewModel) -> Unit) {
        val api = FakeWatchApi().apply { this.admin = admin }
        val model = WatchViewModel(api, MemoryWatchStorage())
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { host ->
            val activity = host.get()
            val config = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.4f }
            @Suppress("DEPRECATION") activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            activity.setContent { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1.4f)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurface) {
                        Box(Modifier.fillMaxSize()) {
                        WatchInteractionPanel(model, if (narrow) Modifier.width(368.dp).fillMaxHeight() else Modifier.fillMaxSize(),
                            hasPlayback = true, onShare = {}, onPost = {}, onSeek = {}, initialTab = tab)
                        }
                    }
                }
            } }
            try {
                model.bindDiscussion(123, 456); model.setActive(true); idle()
                run(api, model)
            } finally { model.setActive(false); idle() }
        }
    }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle() }
    private fun draw(name: String): Int {
        compose.waitForIdle()
        var checksum = 0
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last { it.width > 0 && it.height > 0 }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            for (y in 0 until view.height step 8) for (x in 0 until view.width step 8) checksum = checksum * 31 + bitmap.getPixel(x, y)
            val file = File("../../build/watch-interaction-20261106/$name.png"); file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        return checksum
    }
}
