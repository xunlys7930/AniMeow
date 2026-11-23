package com.animeow.app.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AnimeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val animeId = intent.getLongExtra(AnimeReminderWorker.KEY_ANIME_ID, -1L)
        if (animeId <= 0 && animeId != AnimeReminderScheduler.TEST_ANIME_ID) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AnimeReminderScheduler(context).deliver(animeId,
                    intent.getLongExtra(AnimeReminderScheduler.KEY_DUE, 0), "系统闹钟")
            } catch (error: Exception) {
                Log.w(TAG, "提醒触发或下一周期调度失败", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AnimeReminderReceiver"
    }
}
