package com.animeow.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.data.announcements.AnnouncementState
import com.animeow.app.data.announcements.AppAnnouncement
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.ui.announcements.AnnouncementList
import com.animeow.app.ui.announcements.AnnouncementUiState
import com.animeow.app.ui.community.CropShape
import com.animeow.app.ui.community.ImageCropDialog
import com.animeow.app.ui.reminder.ReminderAnimeCard
import com.animeow.app.ui.reminder.ReminderServiceCard
import com.animeow.app.ui.screens.EmptyLibraryCard
import com.animeow.app.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MobilePageLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val appearance = AppearanceSettings(themeMode = ThemeMode.LIGHT, useDynamicColor = false,
        fontScale = 1.4f, motionLevel = MotionLevel.NONE)

    @Test fun emptyLibraryKeepsAllActionsAboveNavigationAtLargeFontSize() {
        var action = ""
        compose.setContent { AniMeowTheme(appearance) { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Text("追番", Modifier.padding(20.dp), style = MaterialTheme.typography.headlineMedium)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    EmptyLibraryCard(false, {}, { action = "add" }, { action = "discover" }, { action = "import" })
                }
                Surface(Modifier.fillMaxWidth().height(72.dp), color = MaterialTheme.colorScheme.surfaceContainer) { Text("底部导航", Modifier.padding(20.dp)) }
            }
        } } }
        compose.onNodeWithText("导入已有资料").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("import", action) }
        val bottom = compose.onNodeWithText("导入已有资料").fetchSemanticsNode().boundsInWindow.bottom
        val navigation = compose.onNodeWithText("底部导航").fetchSemanticsNode().boundsInWindow.top
        assertTrue(bottom < navigation)
        screenshot("empty-library-large")
    }

    @Test fun reminderDiagnosticsExpandWithoutHidingOrTriggeringTheTestAction() {
        var tests = 0
        compose.setContent { AniMeowTheme(appearance) { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                ReminderServiceCard(
                    configuredCount = 2, notificationsGranted = false, exactAlarmsEnabled = false,
                    batteryOptimized = false, busy = false, onRequestPermission = {},
                    onOpenExactAlarmSettings = {}, onOpenBatteryOptimization = {},
                    onSynchronize = {}, onSyncCalendar = {}, onTest = { tests++ },
                    onScheduledTest = {}, diagnostics = "尚无执行记录", onSystemSettings = {},
                )
            }
        } } }
        compose.onNodeWithText("发送测试通知").assertDoesNotExist()
        compose.onNodeWithText("开启准时提醒").assertIsDisplayed()
        screenshot("reminders-compact-large")
        compose.onNodeWithText("提醒没有按时到达？").performClick()
        compose.onNodeWithText("发送测试通知").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, tests) }
        screenshot("reminders-diagnostics-large")
    }

    @Test fun reminderLongTitleAndCancelRemainSeparateActions() {
        var opened = false
        var cancelled = false
        val anime = AnimeEntity(id = 72, title = "星海旅人：从雨后的车站出发去寻找远方的光", reminderDay = 6, reminderTime = "20:30")
        compose.setContent { AniMeowTheme(appearance.copy(themeMode = ThemeMode.DARK)) { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                ReminderAnimeCard(
                    anime = anime,
                    paused = false,
                    leadMinutes = 15,
                    onEdit = { opened = true },
                    onCancel = { cancelled = true },
                )
            }
        } } }
        compose.onNodeWithText(anime.title).performClick()
        compose.runOnIdle { assertTrue(opened); assertFalse(cancelled); opened = false }
        compose.onNodeWithContentDescription("取消《${anime.title}》的提醒").performClick()
        compose.runOnIdle { assertTrue(cancelled); assertFalse(opened) }
        screenshot("reminder-title-dark-large")
    }

    @Test fun announcementFailureKeepsCachedLettersReadableAndRetryAvailable() {
        val item = AppAnnouncement(9, "announcement", "给每一位陪伴小喵的朋友：新版本即将与你见面", "这是一条本地界面检查用的消息。\n谢谢你记录每一段喜欢的故事。", "2026-09-19T04:00:00Z")
        val state = mutableStateOf(AnnouncementUiState(error = "暂时连接不上服务，请稍后重试"))
        var opened = 0L
        var retries = 0
        compose.setContent { AniMeowTheme(appearance) { Surface(Modifier.fillMaxSize()) {
            AnnouncementList(AnnouncementState(items = listOf(item), loaded = true), state.value, true,
                {}, {}, {}, {}, { retries++ }, {}, { opened = it.id }, {}, {}, Modifier.safeDrawingPadding())
        } } }
        compose.onNodeWithText("重新加载").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        screenshot("announcements-error-large")
        compose.onNodeWithText(item.title).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(9L, opened); state.value = AnnouncementUiState() }
        compose.onNodeWithText("开发者后台").assertDoesNotExist()
        screenshot("announcements-reader-large")
    }

    @Test fun developerListKeepsWithdrawSeparateFromReadingTheLetter() {
        val item = AppAnnouncement(12, "message", "小喵来信", "开发者可以先预览，再发布消息或公告。", "2026-09-19T04:00:00Z")
        var withdrawn = 0L
        var opened = false
        compose.setContent { AniMeowTheme(appearance.copy(themeMode = ThemeMode.DARK)) { Surface(Modifier.fillMaxSize()) {
            AnnouncementList(AnnouncementState(isDeveloper = true), AnnouncementUiState(managing = true, managedItems = listOf(item)), true,
                {}, {}, {}, {}, {}, {}, { opened = true }, { withdrawn = it.id }, {}, Modifier.safeDrawingPadding())
        } } }
        compose.onNodeWithText("写消息 / 公告").assertIsDisplayed()
        compose.onNodeWithText("撤回").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(12L, withdrawn); assertFalse(opened) }
        screenshot("announcements-developer-dark-large")
    }

    @Test fun customIconCropIsInsetAndProducesASquareWithoutBlankBands() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "crop-layout-qa.png")
        val bitmap = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(125, 170, 190))
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val result = mutableStateOf<Uri?>(null)
        compose.setContent { AniMeowTheme(appearance) {
            if (result.value == null) ImageCropDialog(Uri.fromFile(source).toString(), CropShape.Rectangle,
                { result.value = it }, {}, "裁剪桌面图标", 512)
        } }
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("确认裁剪").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("重置位置").assertIsDisplayed()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("确认裁剪") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            screenshot("custom-icon-crop-large")
            compose.onNodeWithText("确认裁剪").performClick()
            compose.waitUntil(10_000) { result.value != null }
            val output = BitmapFactory.decodeFile(result.value!!.path)
            assertEquals(512, output.width); assertEquals(512, output.height)
            assertEquals(255, android.graphics.Color.alpha(output.getPixel(5, 5)))
            assertEquals(255, android.graphics.Color.alpha(output.getPixel(506, 506)))
            output.recycle()
        } finally { source.delete(); result.value?.path?.let { File(it).delete() } }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "mobile-followup-qa/$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
