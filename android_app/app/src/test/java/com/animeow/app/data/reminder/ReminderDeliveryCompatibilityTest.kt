package com.animeow.app.data.reminder

import android.app.Application
import android.app.NotificationManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.AlarmManager
import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.preferences.ReminderPreferences
import com.animeow.app.data.preferences.ReminderSettings
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [30], shadows = [ReminderDeliveryCompatibilityTest.RecordingNotifications::class])
class ReminderDeliveryCompatibilityTest {
    private lateinit var context: Context
    private lateinit var database: AniMeowDatabase
    private lateinit var scheduler: AnimeReminderScheduler

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        try { WorkManager.getInstance(context) }
        catch (_: IllegalStateException) { WorkManager.initialize(context, Configuration.Builder().build()) }
        runBlocking { ReminderPreferences(context).save(ReminderSettings()) }
        database = AniMeowDatabase.getInstance(context)
        scheduler = AnimeReminderScheduler(context)
    }

    @After fun tearDown() {
        if (::database.isInitialized) database.close()
        AniMeowDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    @Test fun exhaustedCountRejectsAnAlreadyQueuedOccurrence() = checkStopped(remaining = 0)
    @Test fun expiredDeadlineRejectsAnAlreadyQueuedOccurrence() = checkStopped(until = LocalDate.now().minusDays(1).toString())
    @Test fun completedWorkRejectsAnAlreadyQueuedOccurrence() = checkStopped(status = "看完")
    @Test fun droppedWorkRejectsAnAlreadyQueuedOccurrence() = checkStopped(status = "弃坑")

    @Test fun completionPreferenceCanStillAllowReminders() = runBlocking(Dispatchers.IO) {
        ReminderPreferences(context).save(ReminderSettings(autoStopWhenCompleted = false))
        val (id, due) = queuedOccurrence("看完")
        scheduler.deliver(id, due, "回归测试")
        assertEquals(1, notifications().postedCount)
        assertNotNull(shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(id.toInt()))
    }

    @Test fun disabledNotificationChannelKeepsRemainingCountAndReportsTheBlock() = runBlocking(Dispatchers.IO) {
        val (id, due) = queuedOccurrence("在看")
        scheduler.setReminderLimit(id, 1, null)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(AnimeReminderNotifier.CHANNEL_ID, "追番提醒", NotificationManager.IMPORTANCE_NONE))
        scheduler.deliver(id, due, "回归测试")
        assertEquals(0, notifications().postedCount)
        assertNull(shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(id.toInt()))
        assertEquals(1, scheduler.getReminderLimit(id).first)
        assertTrue(scheduler.diagnostics().contains("渠道被关闭"))
    }

    @Test
    @Config(sdk = [31])
    fun exactAlarmPermissionRevocationFallsBackAndRegrantRestoresAlarmClock() = runBlocking(Dispatchers.IO) {
        val (id, _) = queuedOccurrence("在看")
        val anime = requireNotNull(database.libraryDao().getAnime(id))
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        scheduler.schedule(anime)
        val fallback = alarms.scheduledAlarms.single { shadowOf(it.operation).savedIntent.data?.toString() == "animeow://reminder/$id" }
        assertNull(fallback.alarmClockInfo)
        assertTrue(fallback.isAllowWhileIdle)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.schedule(anime)
        val exact = alarms.scheduledAlarms.single { shadowOf(it.operation).savedIntent.data?.toString() == "animeow://reminder/$id" }
        assertNotNull(exact.alarmClockInfo)
    }

    private fun checkStopped(status: String = "在看", remaining: Int? = null, until: String? = null) = runBlocking(Dispatchers.IO) {
        val (id, due) = queuedOccurrence(status)
        val state = context.getSharedPreferences("reminder_occurrences_v2", Context.MODE_PRIVATE)
        state.edit().apply {
            if (remaining != null) putInt("remaining_count_$id", remaining)
            if (until != null) putString("until_date_$id", until)
        }.commit()
        scheduler.deliver(id, due, "回归测试")
        assertEquals("A stale notification must never be posted, even if subsequently cancelled", 0, notifications().postedCount)
        assertNull("Stopped reminders must not post a stale notification",
            shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(id.toInt()))
        assertEquals(remaining to until, scheduler.getReminderLimit(id))
        assertFalse(state.contains("due_$id"))
    }

    private suspend fun queuedOccurrence(status: String): Pair<Long, Long> {
        val day = LocalDate.now().dayOfWeek.value
        val time = LocalTime.of(12, 0)
        val id = database.libraryDao().insertAnime(AnimeEntity(title = "兼容性回归", status = status, reminderDay = day, reminderTime = time.toString()))
        val due = System.currentTimeMillis() - 1_000
        context.getSharedPreferences("reminder_occurrences_v2", Context.MODE_PRIVATE).edit()
            .putLong("due_$id", due).putString("signature_$id", "$day|$time|0|${ZoneId.systemDefault().id}")
            .putBoolean("migrated_$id", true).commit()
        return id to due
    }

    private fun notifications() = shadowOf(context.getSystemService(NotificationManager::class.java)) as RecordingNotifications

    @Implements(NotificationManager::class)
    class RecordingNotifications : ShadowNotificationManager() {
        var postedCount = 0
        @Implementation
        override fun notify(tag: String?, id: Int, notification: Notification) {
            postedCount++
            super.notify(tag, id, notification)
        }
    }
}
