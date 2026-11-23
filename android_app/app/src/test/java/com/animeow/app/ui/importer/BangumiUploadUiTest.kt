package com.animeow.app.ui.importer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.animeow.app.data.bangumi.*
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AniMeowDatabase
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BangumiUploadUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun newCollectionIsUnselectedUntilReviewAndRequiresPrivateConfirmation() = scene { activity, model, remote, db ->
        compose.runOnIdle { model.read() }; await { model.state.value.preview != null }
        assertEquals(0, model.state.value.selectedCount)
        compose.onNodeWithText("核对并确认合并").assertIsNotEnabled()
        scrollTo("仅选待新建收藏 (1)").performClick()
        assertEquals(1, model.state.value.selectedCount)
        compose.onNodeWithText("已选 1 部（上传 1，含新建 1）").assertIsDisplayed()
        draw(activity, "upload-preview-light-large")
        compose.onNodeWithText("核对并确认合并").performClick()
        compose.onNodeWithText("新建收藏仅自己可见").assertIsOn()
        draw(activity, "upload-confirm-light-large")
        compose.onNodeWithText("返回核对").performClick()
        assertTrue(remote.applied.isEmpty())
        compose.onNodeWithText("核对并确认合并").performClick()
        compose.onNodeWithText("确认合并", substring = false).performClick()
        await { !model.state.value.uploading && model.state.value.preview == null }
        assertEquals("uploaded", JSONObject(model.state.value.report!!).getJSONArray("items").getJSONObject(0).getString("state"))
        assertEquals(1, remote.applied.size)
        assertEquals(3, runBlocking { db.libraryDao().getAnime(1)!! }.watchedEpisodes)
    }

    @Test fun fractionalScoresAndPrivateValuesArePreservedAndFailureIsRecoverable() = scene(dark = true, landscape = true, rate = 85) { activity, model, remote, _ ->
        remote.rows[1] = uploadFixtureRow(1)
        remote.onApply = { throw java.io.IOException("/private/synthetic/token-secret") }
        compose.runOnIdle { model.read() }; await { model.state.value.preview != null }
        scrollTo("8.5 分没有无损的整数对应，不能直接上传；可明确选择采用 Bangumi 评分。").assertIsDisplayed()
        compose.onNodeWithText("合成私密短评").assertDoesNotExist()
        scrollTo("全选可上传差异 (1)").performClick()
        assertFalse(model.state.value.choices.values.single().rating)
        compose.onNodeWithText("核对并确认合并").performClick()
        compose.onNodeWithText("确认合并", substring = false).performClick()
        await { !model.state.value.uploading && model.state.value.preview == null }
        scrollTo("结果待核实，请重新读取").assertIsDisplayed()
        compose.onNodeWithText("合成私密短评").assertDoesNotExist()
        val entry = JSONObject(model.state.value.report!!).getJSONArray("items").getJSONObject(0)
        assertFalse(entry.optString("message").contains("token-secret"))
        draw(activity, "upload-uncertain-dark-landscape-large", 1520, 720)
        scrollTo("读取完整收藏对比").assertIsEnabled()
    }

    @Test fun adoptingRemoteRatingRequiresConfirmationAndDoesNotUploadIt() = scene(rate = 85) { activity, model, remote, db ->
        remote.rows[1] = uploadFixtureRow(1)
        compose.runOnIdle { model.read() }; await { model.state.value.preview != null }
        scrollTo("↓ 采用：7 分").performClick().assertIsSelected()
        assertTrue(model.state.value.choices.values.single().downloadRating)
        compose.onNodeWithText("核对并确认合并").performClick()
        compose.onNodeWithText("采用远端评分会替换本机小数或字母评分；采用状态或进度可能减少记录值。只修改勾选字段，不生成观看历史。").assertExists()
        draw(activity, "merge-download-confirm-large")
        compose.onNodeWithText("确认合并", substring = false).performClick()
        await { !model.state.value.uploading && model.state.value.preview == null }
        assertTrue(remote.applied.isEmpty())
        assertEquals(70, runBlocking { db.libraryDao().getAnime(1)!! }.rating)
        scrollTo("已采用 Bangumi 值").assertIsDisplayed()
        draw(activity, "merge-download-result-large")
    }

    @Test fun manualAssociationRequiresReadingTheIdentityAndExplicitConfirmation() = scene(unlinked = true) { activity, model, remote, db ->
        remote.rows[42] = uploadFixtureRow(42)
        compose.runOnIdle { model.read() }; await { model.state.value.preview != null }
        scrollTo("未关联 (1)").performClick()
        scrollTo("确认 Bangumi 关联").performClick()
        draw(activity, "merge-association-number-large")
        compose.onNodeWithContentDescription("Bangumi 作品编号").performScrollTo().performTextInput("42")
        scrollTo("读取作品资料").performClick()
        await { model.state.value.association != null }
        compose.onNodeWithText("Bangumi：合成关联作品42（#42）").assertExists()
        assertNull(runBlocking { db.libraryDao().getAnime(1)!! }.mergedSourcesJson)
        draw(activity, "merge-association-confirm-large")
        compose.onNodeWithText("确认关联").performClick()
        await { !model.state.value.reading && model.state.value.preview?.rows?.single()?.local?.subjectId == 42L }
        assertNull(runBlocking { db.libraryDao().getAnime(1)!! }.externalSource)
        assertTrue(remote.applied.isEmpty())
    }

    @Test fun remoteOnlyCollectionIsCreatedOnlyAfterExplicitConfirmation() = scene { activity, model, remote, db ->
        remote.rows[42] = uploadFixtureRow(42).put("title", "仅远端的合成作品")
        compose.runOnIdle { model.read() }; await { model.state.value.preview != null }
        scrollTo("仅 Bangumi (1)").performClick()
        scrollTo("添加到本机").performClick()
        draw(activity, "merge-remote-import-confirm-large")
        assertEquals(1, runBlocking { db.libraryDao().getAllAnimesIncludingDeleted().size })
        compose.onNodeWithText("确认添加").performClick()
        await { !model.state.value.uploading && model.state.value.preview == null }
        assertEquals(2, runBlocking { db.libraryDao().getAllAnimesIncludingDeleted().size })
        assertTrue(remote.applied.isEmpty())
        assertEquals(0, runBlocking { db.libraryDao().getAllAnimesIncludingDeleted().single { it.externalId == "42" }.watchedEpisodes })
    }

    @Test fun bookProgressUsesExplicitIndependentUnitsAndLocalEditsDoNotUpload() = scene(book = true) { activity, model, remote, db ->
        remote.rows[1] = uploadFixtureRow(1, kind = 1, progress = 17).put("vol_status", 3)
        compose.onNodeWithText("书籍").performClick()
        compose.runOnIdle { model.read() }; await { model.state.value.preview != null }
        scrollTo("↓ 采用：3 卷").performClick()
        scrollTo("↓ 采用：17 话").performClick()
        compose.onNodeWithText("核对并确认合并").performClick()
        compose.onNodeWithText("确认合并", substring = false).performClick()
        await { !model.state.value.uploading && model.state.value.preview == null }
        compose.runOnIdle { model.read() }; await { model.state.value.preview != null }
        scrollTo("全部本机 (1)").performClick()
        scrollTo("编辑卷话进度").performClick()
        compose.onNodeWithContentDescription("已读卷数").performScrollTo().performTextReplacement("0")
        compose.onNodeWithContentDescription("已读话数").performScrollTo().performTextReplacement("5")
        draw(activity, "merge-book-progress-large")
        scrollTo("保存本机卷话进度").performClick()
        await { !model.state.value.reading && model.state.value.preview != null }
        val saved = runBlocking { db.libraryDao().getAnime(1)!! }
        assertEquals(0, saved.bookReadVolumes); assertEquals(5, saved.bookReadChapters); assertEquals(3, saved.watchedEpisodes)
        assertEquals(0, model.state.value.selectedCount); assertTrue(remote.applied.isEmpty())
    }

    @Test fun stoppingFinishesCurrentItemAndDoesNotStartTheNextOne() = scene(two = true) { _, model, remote, _ ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        remote.onApply = { entered.complete(Unit); release.await() }
        compose.runOnIdle { model.read() }; await { model.state.value.preview != null }
        scrollTo("仅选待新建收藏 (2)").performClick()
        compose.onNodeWithText("核对并确认合并").performClick()
        compose.onNodeWithText("确认合并", substring = false).performClick()
        await { entered.isCompleted }
        scrollTo("完成当前项后停止").performClick()
        assertTrue(model.state.value.stopping)
        release.complete(Unit)
        await { !model.state.value.uploading }
        assertEquals(1, remote.applied.size)
        assertEquals("stopped", JSONObject(model.state.value.report!!).getString("state"))
    }

    @Test fun batchFieldAndStatusFilterWorkSafelyAcrossMultipleWorks() = scene(two = true) { activity, model, remote, db ->
        runBlocking {
            val second = db.libraryDao().getAnime(2)!!
            db.libraryDao().updateAnime(second.copy(status = "看完", rating = 0, watchedEpisodes = 1))
        }
        remote.rows[1] = uploadFixtureRow(1, type = 1, rate = 7, progress = 2)
        remote.rows[2] = uploadFixtureRow(2, type = 3, rate = 8, progress = 10)
        compose.runOnIdle { model.read() }; await { model.state.value.preview != null }

        scrollTo("批量操作（当前列表 2 部）").assertIsDisplayed()
        scrollTo("在看 (1)").performClick()
        scrollTo("批量操作（当前列表 1 部）").assertIsDisplayed()
        scrollTo("全部状态 (2)").performClick()
        scrollTo("批量操作（当前列表 2 部）").assertIsDisplayed()

        // Batch-toggle status uploads both; rating excludes rate=0 clearing; progress excludes regression 1<10.
        scrollTo("↑ 状态 (2)").performClick()
        assertTrue(model.state.value.choices.values.all { it.status && !it.rating && !it.progress })
        scrollTo("↑ 评分 (1)").performClick()
        assertTrue(model.state.value.choices[1L]!!.rating)
        assertFalse(model.state.value.choices[2L]!!.rating)
        scrollTo("↑ 进度推进 (1)").performClick()
        assertTrue(model.state.value.choices[1L]!!.progress)
        assertFalse(model.state.value.choices[2L]!!.progress)
        draw(activity, "batch-operations-light-large")

        scrollTo("清空选择").performClick()
        assertEquals(0, model.state.value.selectedCount)
    }

    private fun scene(dark: Boolean = false, landscape: Boolean = false, rate: Int = 90, two: Boolean = false, unlinked: Boolean = false, book: Boolean = false,
        run: (ComponentActivity, BangumiUploadViewModel, FakeBangumiUploadRemote, AniMeowDatabase) -> Unit,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AniMeowDatabase::class.java).allowMainThreadQueries().build()
        val remote = FakeBangumiUploadRemote()
        val directory = File(context.cacheDir, "upload-ui-${java.util.UUID.randomUUID()}")
        val service = BangumiUploadService(context, db, remote, BangumiUploadJournal(directory), pageDelayMs = 0, rowDelayMs = 0)
        val store = ViewModelStore()
        val model = BangumiUploadViewModel(context, service).also { store.put("upload", it) }
        runBlocking { repeat(if (two) 2 else 1) { index ->
            db.libraryDao().insertAnime(AnimeEntity(title = "合成的中文长标题与上传确认作品${index + 1}", externalSource = if (unlinked) null else "bangumi",
                externalId = if (unlinked) null else "${index + 1}", subjectType = if (book) "book" else "anime", status = "在看", rating = rate, watchedEpisodes = 3, totalEpisodes = 12))
        } }
        try {
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                val configuration = android.content.res.Configuration(activity.resources.configuration).apply {
                    fontScale = 1.4f; screenWidthDp = if (landscape) 760 else 360; screenHeightDp = if (landscape) 360 else 760
                    orientation = if (landscape) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
                }
                @Suppress("DEPRECATION") activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                activity.setContent { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    CompositionLocalProvider(LocalDensity provides Density(2f, 1.4f)) { BangumiUploadScreen({}, model) }
                } }
                controller.visible()
                draw(activity, "upload-initial-${if (dark) "dark" else "light"}", if (landscape) 1520 else 720, if (landscape) 720 else 1520)
                run(activity, model, remote, db)
            }
        } finally { store.clear(); db.close(); directory.deleteRecursively() }
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(15000) { shadowOf(Looper.getMainLooper()).idle(); condition() }

    private fun scrollTo(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }

    private fun draw(activity: ComponentActivity, name: String, width: Int = 720, height: Int = 1520) {
        compose.waitForIdle()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val target = WindowInspector.getGlobalWindowViews().lastOrNull { it !== root && it.isShown && it.width > 0 } ?: root
        val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        target.draw(Canvas(bitmap))
        val colors = hashSetOf<Int>()
        for (y in 0 until bitmap.height step 12) for (x in 0 until bitmap.width step 12) colors += bitmap.getPixel(x, y)
        assertTrue("截图必须包含实际绘制的界面，不能是空窗口", colors.size > 12)
        File("../../build/bgm-merge-20261105/$name.png").apply { parentFile!!.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        bitmap.recycle()
    }
}
