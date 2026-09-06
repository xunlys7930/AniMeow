package com.animeow.app.ui.reminder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.reminder.AnimeReminderNotifier
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ReminderManagementViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as AniMeowApplication
    private val repository = app.libraryRepository

    val reminders: StateFlow<List<AnimeEntity>> = repository.observeAnimes()
        .map { animes ->
            animes.filter { anime ->
                anime.reminderDay in 1..7 && !anime.reminderTime.isNullOrBlank()
            }.sortedWith(
                compareBy<AnimeEntity> { it.reminderDay }
                    .thenBy { it.reminderTime }
                    .thenBy { it.title },
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun canScheduleExactAlarms(): Boolean = app.reminderScheduler.canScheduleExactAlarms()

    suspend fun synchronize(): String {
        val result = app.reminderScheduler.syncAll(app.database)
        return if (result.exactAlarmsEnabled) {
            "已同步 ${result.configuredCount} 条提醒，当前使用准时闹钟"
        } else {
            "已同步 ${result.configuredCount} 条提醒，当前使用系统后台调度"
        }
    }

    fun sendTestNotification(): String =
        if (AnimeReminderNotifier.showTest(getApplication())) {
            "测试通知已发送"
        } else {
            "尚未获得通知权限"
        }

    suspend fun cancelReminder(anime: AnimeEntity): String {
        repository.clearReminder(anime.id)
        return try {
            app.reminderScheduler.cancel(anime.id)
            "已取消《${anime.title}》的提醒"
        } catch (error: Exception) {
            app.operationLog.recordError(error, "提醒管理 · 取消提醒")
            "提醒设置已清除；系统任务清理失败，可点击重新同步"
        }
    }
}
