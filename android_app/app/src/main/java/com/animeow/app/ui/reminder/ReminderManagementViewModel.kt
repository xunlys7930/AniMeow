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
    private val preferences = com.animeow.app.data.preferences.ReminderPreferences(application)
    val settings = preferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.animeow.app.data.preferences.ReminderSettings())

    suspend fun updateSettings(
        paused: Boolean = settings.value.paused,
        leadMinutes: Int = settings.value.leadMinutes,
        autoStopWhenCompleted: Boolean = settings.value.autoStopWhenCompleted,
    ): String {
        val previous = settings.value
        preferences.save(com.animeow.app.data.preferences.ReminderSettings(paused, leadMinutes, autoStopWhenCompleted))
        app.reminderScheduler.syncAll(app.database)
        return when {
            paused != previous.paused -> if (paused) "已暂停全部提醒，原有星期和时间已保留" else "已恢复提醒，提前 $leadMinutes 分钟通知"
            autoStopWhenCompleted != previous.autoStopWhenCompleted -> if (autoStopWhenCompleted) "已开启：标记看完或弃番时自动停止提醒" else "已关闭：标记看完后仍保留提醒"
            else -> "已更新提前通知时间为 $leadMinutes 分钟"
        }
    }

    fun getReminderLimit(animeId: Long): Pair<Int?, String?> = app.reminderScheduler.getReminderLimit(animeId)

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
    fun isIgnoringBatteryOptimizations(): Boolean = app.reminderScheduler.isIgnoringBatteryOptimizations()
    fun diagnostics(): String = app.reminderScheduler.diagnostics()
    fun scheduleTest(): String = app.reminderScheduler.scheduleTest()

    suspend fun synchronize(): String {
        val result = app.reminderScheduler.syncAll(app.database)
        if (preferences.snapshot().paused) return "全部提醒已暂停，保留 ${result.configuredCount} 条设置"
        return if (result.exactAlarmsEnabled) {
            "已同步 ${result.configuredCount} 条提醒，当前使用准时闹钟"
        } else {
            "已同步 ${result.configuredCount} 条提醒，使用系统闹钟与后台任务，省电模式下可能延迟"
        }
    }

    fun sendTestNotification(): String =
        if (AnimeReminderNotifier.showTest(getApplication())) {
            "测试通知已发送"
        } else {
            "通知权限或追番提醒渠道已关闭，请检查系统通知设置"
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

    /** 把每周提醒写进系统日历作为备份；提醒被取消后再次同步会清理对应日程。 */
    suspend fun syncToCalendar(): String {
        val list = reminders.value
        if (list.isEmpty()) return "还没有每周提醒，无需同步"
        if (preferences.snapshot().paused) return "全部提醒已暂停，恢复后再同步到日历"
        val leadMinutes = settings.value.leadMinutes
        val specs = list.mapNotNull { anime ->
            val day = anime.reminderDay ?: return@mapNotNull null
            val time = anime.reminderTime?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            val (remaining, until) = app.reminderScheduler.getReminderLimit(anime.id)
            val untilDate = until?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            com.animeow.app.data.reminder.CalendarReminderSpec(
                anime.id, anime.title, day, time, remaining, untilDate, leadMinutes,
            )
        }
        if (specs.isEmpty()) return "没有可同步的提醒设置"
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.animeow.app.data.reminder.ReminderCalendarWriter(getApplication()).sync(specs)
        }
        val total = result.created + result.updated + result.failed
        if (total == 0) {
            return if (result.removed > 0) "没有需要写入的提醒，已清理 ${result.removed} 条旧日程"
            else "没有需要写入系统日历的提醒"
        }
        return buildString {
            append("已把 $total 条追番提醒写入系统日历「${result.calendarName}」")
            append("（新建 ${result.created}、更新 ${result.updated}")
            if (result.removed > 0) append("、清理 ${result.removed}")
            append("）")
            if (result.failed > 0) append("；${result.failed} 条写入失败")
            append("；实际提醒以系统日历设置为准")
        }
    }
}
