package com.animeow.app.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AniMeowDatabase
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

data class ReminderSyncSummary(
    val configuredCount: Int,
    val exactAlarmsEnabled: Boolean,
)

class AnimeReminderScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun schedule(anime: AnimeEntity) {
        val day = anime.reminderDay ?: run {
            cancel(anime.id)
            return
        }
        val time = anime.reminderTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        if (anime.deletedAt != null || day !in 1..7 || time == null) {
            cancel(anime.id)
            return
        }
        val delay = nextReminderDelay(day, time)
        val fallbackRequest = PeriodicWorkRequestBuilder<AnimeReminderWorker>(
            7,
            TimeUnit.DAYS,
            15,
            TimeUnit.MINUTES,
        )
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putLong(AnimeReminderWorker.KEY_ANIME_ID, anime.id).build())
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            workName(anime.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            fallbackRequest,
        )
        if (canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delay.toMillis(),
                alarmIntent(anime.id),
            )
            return
        }
        alarmManager.cancel(alarmIntent(anime.id))
    }

    fun cancel(animeId: Long) {
        alarmManager.cancel(alarmIntent(animeId))
        workManager.cancelUniqueWork(workName(animeId))
        AnimeReminderNotifier.cancel(appContext, animeId)
    }

    suspend fun syncAll(database: AniMeowDatabase): ReminderSyncSummary {
        workManager.cancelAllWorkByTag(TAG)
        val configured = database.libraryDao().getActiveAnimes().filter { anime ->
            anime.reminderDay in 1..7 &&
                anime.reminderTime?.let { runCatching { LocalTime.parse(it) }.isSuccess } == true
        }
        configured.forEach(::schedule)
        return ReminderSyncSummary(
            configuredCount = configured.size,
            exactAlarmsEnabled = canScheduleExactAlarms(),
        )
    }

    private fun workName(animeId: Long) = "anime_reminder_$animeId"

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun alarmIntent(animeId: Long): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        animeId.toInt(),
        Intent(appContext, AnimeReminderReceiver::class.java).apply {
            action = ACTION_EXACT_REMINDER
            putExtra(AnimeReminderWorker.KEY_ANIME_ID, animeId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val TAG = "anime_reminders"
        private const val ACTION_EXACT_REMINDER = "com.animeow.app.action.EXACT_REMINDER"
    }
}

internal fun nextReminderDelay(
    day: Int,
    time: LocalTime,
    now: LocalDateTime = LocalDateTime.now(ZoneId.systemDefault()),
): Duration {
    var nextDate = now.toLocalDate().with(
        TemporalAdjusters.nextOrSame(DayOfWeek.of(day)),
    )
    var next = LocalDateTime.of(nextDate, time)
    if (!next.isAfter(now.plusMinutes(1))) {
        nextDate = nextDate.plusWeeks(1)
        next = LocalDateTime.of(nextDate, time)
    }
    return Duration.between(now, next).coerceAtLeast(Duration.ZERO)
}
