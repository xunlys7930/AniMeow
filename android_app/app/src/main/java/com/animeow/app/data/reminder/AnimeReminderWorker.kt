package com.animeow.app.data.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.animeow.app.data.local.AniMeowDatabase

class AnimeReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val animeId = inputData.getLong(KEY_ANIME_ID, -1L)
        if (animeId <= 0) return Result.success()
        // The periodic task is always retained as a safety net. When exact alarms are
        // available the AlarmManager receiver owns delivery, preventing duplicate notices.
        if (AnimeReminderScheduler(applicationContext).canScheduleExactAlarms()) return Result.success()
        val anime = AniMeowDatabase.getInstance(applicationContext).libraryDao().getAnime(animeId)
            ?: return Result.success()
        if (anime.deletedAt != null || anime.reminderDay == null || anime.reminderTime.isNullOrBlank()) {
            return Result.success()
        }
        AnimeReminderNotifier.show(applicationContext, anime)
        return Result.success()
    }

    companion object {
        const val KEY_ANIME_ID = "anime_id"
    }
}
