package com.animeow.app.ui.community

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.community.CommunityCommentPage
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommunityThreadUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun commentReadErrorCanRetryWithoutLosingTheUnsentDraft() = scene { activity, state, api, controller ->
        api.readComments = { _, _, _, _ -> throw java.io.IOException("synthetic failure") }
        compose.runOnIdle { controller.loadComments(1); controller.updateDraft("保留尚未发送的评论") }
        compose.onNodeWithText("评论暂时无法读取").assertIsDisplayed()
        compose.onNodeWithContentDescription("写下你的评论…").assertTextContains("保留尚未发送的评论")
        draw(activity, "am02-comment-error-large", false)
        api.readComments = { _, _, _, _ -> CommunityCommentPage(emptyList(), null) }
        compose.onNodeWithText("重新加载").performClick()
        compose.onNodeWithText("评论暂时无法读取").assertDoesNotExist()
        assertEquals("保留尚未发送的评论", state.value.commentDraft)
    }

    @Test fun sendingDisablesDuplicateTapsUntilTheServerConfirms() = scene { activity, state, api, controller ->
        val pending = CompletableDeferred<Unit>(); api.onCreate = { pending.await() }
        compose.runOnIdle { controller.updateDraft("合成评论内容") }
        compose.onNodeWithContentDescription("发送").performClick()
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
        assertTrue(state.value.commentsBusy); assertEquals(1, api.writes.size)
        draw(activity, "am02-comment-sending-large", false)
        pending.complete(Unit)
        await { !state.value.commentsBusy }
        assertEquals("", state.value.commentDraft); assertEquals(1, api.writes.size)
    }

    @Test
    @Config(qualifiers = "w760dp-h360dp-land-xhdpi")
    fun failedReplyPreservesItsRecipientAndTextAndCanBeCancelled() = scene(dark = true, landscape = true) { activity, state, api, controller ->
        api.onReply = { throw java.io.IOException("synthetic failure") }
        compose.runOnIdle { controller.startReply(10); controller.updateReplyDraft("失败后保留这段回复") }
        compose.onNodeWithContentDescription("发送").performClick()
        compose.onNodeWithText("回复 合成昵称").assertIsDisplayed()
        compose.onNodeWithContentDescription("回复 合成昵称…").assertTextContains("失败后保留这段回复")
        draw(activity, "am02-reply-failed-dark-landscape", true)
        compose.onNodeWithText("取消回复").performClick()
        assertNull(state.value.replyTarget); assertEquals("", state.value.replyDraft)
    }

    private fun scene(dark: Boolean = false, landscape: Boolean = false,
        run: (ComponentActivity, MutableStateFlow<CommunityUiState>, FakeCommunityThreadApi, CommunityThreadController) -> Unit,
    ) {
        val session = CloudSession(1, "synthetic", "synthetic-token")
        val state = MutableStateFlow(CommunityUiState(session = session))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeCommunityThreadApi()
        val controller = CommunityThreadController(scope, state, api, { session }, { _, fallback -> fallback }, { _, _ -> })
        controller.open(communityTestPost())
        try { Robolectric.buildActivity(ComponentActivity::class.java).setup().use { activityController ->
            val activity = activityController.get()
            val config = android.content.res.Configuration(activity.resources.configuration).apply {
                fontScale = 1.4f
            }
            @Suppress("DEPRECATION") activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            activity.setContent { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1.4f)) {
                    val value by state.collectAsState()
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        value.commentsError?.let { error -> CommunityThreadError("评论暂时无法读取", error, { controller.loadComments(1) }) }
                        CommentComposer(value = if (value.replyTarget != null) value.replyDraft else value.commentDraft,
                            placeholder = value.replyTarget?.let { "回复 ${it.author.nickname}…" } ?: "写下你的评论…", enabled = !value.commentsBusy,
                            onValueChange = { if (value.replyTarget != null) controller.updateReplyDraft(it) else controller.updateDraft(it) },
                            onSend = { value.replyTarget?.let { controller.reply(it.id, value.replyDraft) } ?: controller.add(value.commentDraft) },
                            sendEnabled = (if (value.replyTarget != null) value.replyDraft else value.commentDraft).isNotBlank(),
                            replyingTo = value.replyTarget?.author?.nickname, onCancelReply = controller::cancelReply)
                    }
                    }
                }
            } }
            activityController.visible()
            measure(activity, landscape)
            run(activity, state, api, controller)
        } } finally { scope.cancel() }
    }

    private fun await(predicate: () -> Boolean) = compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle(); predicate() }
    private fun measure(activity: ComponentActivity, landscape: Boolean) {
        compose.waitForIdle()
        val root = activity.window.decorView; val width = if (landscape) 1520 else 720; val height = if (landscape) 720 else 1520
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height); compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
    }
    private fun draw(activity: ComponentActivity, name: String, landscape: Boolean) {
        measure(activity, landscape)
        val root = activity.window.decorView; val width = root.width; val height = root.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bitmap))
        val colors = hashSetOf<Int>(); var samples = 0; var opaque = 0
        for (y in 0 until height step 12) for (x in 0 until width step 12) {
            val pixel = bitmap.getPixel(x, y); colors += pixel; samples++; if (pixel ushr 24 > 240) opaque++
        }
        assertTrue(colors.size > 12)
        assertTrue("The complete window must draw, including the landscape viewport", opaque > samples * 0.95)
        File("../../build/am02-community-20261106/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
