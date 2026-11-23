package com.animeow.app.data.reminder

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.preferences.ReminderPreferences
import com.animeow.app.data.preferences.ReminderSettings
import java.io.FileInputStream
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderDeliveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dao = AniMeowDatabase.getInstance(context).libraryDao()
    private val scheduler = AnimeReminderScheduler(context)
    private val prefs = context.getSharedPreferences("reminder_occurrences_v2", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var savedSettings = ReminderSettings()
    private var anime = AnimeEntity(title = "本地提醒测试")

    @Before fun setup() = runBlocking {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        val preferences = ReminderPreferences(context)
        savedSettings = preferences.snapshot()
        preferences.save(ReminderSettings())
        val value = AnimeEntity(title = "本地提醒测试", reminderDay = ZonedDateTime.now().dayOfWeek.value, reminderTime = "23:58")
        anime = value.copy(id = dao.insertAnime(value))
        scheduler.schedule(anime)
    }

    @After fun cleanup() = runBlocking {
        if (anime.id > 0) { scheduler.cancel(anime.id); dao.deleteAnime(anime.id) }
        ReminderPreferences(context).save(savedSettings)
    }

    @Test fun resyncKeepsOccurrenceAndAlarmWorkerDeliverOnlyOnce() = runBlocking {
        val scheduled = prefs.getLong("due_${anime.id}", 0)
        scheduler.schedule(anime)
        assertEquals(scheduled, prefs.getLong("due_${anime.id}", 0))
        val due = System.currentTimeMillis() - 1000
        prefs.edit().putLong("due_${anime.id}", due).commit()
        scheduler.deliver(anime.id, due, "worker-test")
        assertEquals(due, prefs.getLong("processed_${anime.id}", 0))
        assertTrue(prefs.getLong("due_${anime.id}", 0) > System.currentTimeMillis())
        assertTrue(scheduler.diagnostics().contains("已发送"))
        val firstCheck = prefs.getLong("last_check", 0)
        scheduler.deliver(anime.id, due, "alarm-test")
        assertEquals(firstCheck, prefs.getLong("last_check", 0))
        assertEquals(1, manager.activeNotifications.count { it.id == anime.id.toInt() })
    }

    @Test fun earlyAndExpiredOccurrencesNeverPostIncorrectNotifications() = runBlocking {
        val future = prefs.getLong("due_${anime.id}", 0)
        scheduler.deliver(anime.id, future, "early-test")
        assertEquals(0, manager.activeNotifications.count { it.id == anime.id.toInt() })
        assertEquals(0L, prefs.getLong("processed_${anime.id}", 0))
        val expired = System.currentTimeMillis() - 7 * 60 * 60 * 1000L
        prefs.edit().putLong("due_${anime.id}", expired).commit()
        scheduler.deliver(anime.id, expired, "expired-test")
        assertTrue(scheduler.diagnostics().contains("过期"))
        assertEquals(0, manager.activeNotifications.count { it.id == anime.id.toInt() })
        assertTrue(prefs.getLong("due_${anime.id}", 0) > System.currentTimeMillis())
    }

    @Test fun pauseDisableAndDeletedWorkCancelPendingDelivery() = runBlocking {
        val due = System.currentTimeMillis() - 1000
        prefs.edit().putLong("due_${anime.id}", due).commit()
        ReminderPreferences(context).save(ReminderSettings(paused = true))
        scheduler.deliver(anime.id, due, "paused-test")
        assertFalse(prefs.contains("due_${anime.id}"))
        assertEquals(0, manager.activeNotifications.count { it.id == anime.id.toInt() })
        ReminderPreferences(context).save(ReminderSettings())
        scheduler.schedule(anime)
        dao.updateAnime(anime.copy(reminderDay = null, reminderTime = null))
        scheduler.deliver(anime.id, prefs.getLong("due_${anime.id}", 0), "disabled-test")
        assertFalse(prefs.contains("due_${anime.id}"))
        dao.updateAnime(anime)
        scheduler.schedule(anime)
        dao.deleteAnime(anime.id)
        scheduler.deliver(anime.id, prefs.getLong("due_${anime.id}", 0), "deleted-test")
        assertFalse(prefs.contains("due_${anime.id}"))
        WorkManager.getInstance(context).cancelAllWorkByTag("reminder_anime_${anime.id}").result.get(5, TimeUnit.SECONDS)
        assertTrue(WorkManager.getInstance(context).getWorkInfosByTag("reminder_anime_${anime.id}").get(5, TimeUnit.SECONDS).all { it.state.isFinished })
    }
}
