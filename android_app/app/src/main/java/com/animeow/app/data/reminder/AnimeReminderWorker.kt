package com.animeow.app.data.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class AnimeReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val animeId = inputData.getLong(KEY_ANIME_ID, -1L)
        if (animeId <= 0 && animeId != AnimeReminderScheduler.TEST_ANIME_ID) return Result.success()
        AnimeReminderScheduler(applicationContext).deliver(animeId,
            inputData.getLong(AnimeReminderScheduler.KEY_DUE, 0), "后台任务")
        return Result.success()
    }

    companion object {
        const val KEY_ANIME_ID = "anime_id"
    }
}
