package com.animeow.app.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.animeow.app.MainActivity
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.preferences.ReminderPreferences
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ReminderSyncSummary(val configuredCount: Int, val exactAlarmsEnabled: Boolean)

/** Both delivery paths refer to one occurrence. Opening the app never restarts its countdown. */
class AnimeReminderScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val state = appContext.getSharedPreferences("reminder_occurrences_v2", Context.MODE_PRIVATE)

    fun setReminderLimit(animeId: Long, remainingCount: Int?, untilDate: String?) {
        state.edit().apply {
            // 0 与"未设置"必须区分：未设置才是"一直循环"。所以这里把 0 归一到 1，
            // 避免"剩余 0 次"被 getReminderLimit 当成有效值、又在重排时被当成无限。
            if (remainingCount != null) {
                putInt("remaining_count_$animeId", remainingCount.coerceAtLeast(1))
            } else {
                remove("remaining_count_$animeId")
            }
            if (!untilDate.isNullOrBlank()) {
                putString("until_date_$animeId", untilDate.trim())
            } else {
                remove("until_date_$animeId")
            }
        }.apply()
    }

    fun getReminderLimit(animeId: Long): Pair<Int?, String?> {
        // 0 是合法值，表示"设定次数已用尽"，界面据此显示已完成，而不是回退成一直循环。
        val remainingCount = if (state.contains("remaining_count_$animeId")) {
            state.getInt("remaining_count_$animeId", 0).coerceAtLeast(0)
        } else null
        val untilDate = state.getString("until_date_$animeId", null)?.takeIf(String::isNotBlank)
        return Pair(remainingCount, untilDate)
    }

    /** 读取剩余次数；null 表示用户没有设置次数（一直循环），0 表示已用尽。 */
    private fun storedRemainingCount(animeId: Long): Int? =
        if (state.contains("remaining_count_$animeId")) {
            state.getInt("remaining_count_$animeId", 0).coerceAtLeast(0)
        } else null

    private fun storedUntilDate(animeId: Long): java.time.LocalDate? =
        state.getString("until_date_$animeId", null)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }

    suspend fun schedule(anime: AnimeEntity) {
        mutex.withLock { scheduleLocked(anime) }
    }

    /** 返回本次注册路径（"exact"/"inexact"）；null 表示按规则未注册（已暂停或无需提醒）。 */
    private suspend fun scheduleLocked(anime: AnimeEntity, afterDelivery: Boolean = false): String? {
        val settings = ReminderPreferences(appContext).snapshot()
        val day = anime.reminderDay
        val time = anime.reminderTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        val isCompleted = anime.status.trim() in com.animeow.app.data.REMINDER_STOP_STATUSES
        if (settings.paused || anime.deletedAt != null || day !in 1..7 || time == null ||
            (settings.autoStopWhenCompleted && isCompleted)
        ) {
            stopScheduling(anime.id)
            return null
        }
        val remainingCount = storedRemainingCount(anime.id)
        val untilLimitDate = storedUntilDate(anime.id)
        if (shouldStopForLimit(remainingCount, untilLimitDate, java.time.LocalDate.now())) {
            stopScheduling(anime.id)
            return null
        }
        val endOfUntilDay = untilLimitDate?.atTime(LocalTime.MAX)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        val now = System.currentTimeMillis()
        val signature = "$day|$time|${settings.leadMinutes}|${ZoneId.systemDefault().id}"
        val previous = state.getLong("due_${anime.id}", 0)
        val due = if (state.getString("signature_${anime.id}", null) == signature &&
            previous > now - CATCH_UP_MILLIS && state.getLong("processed_${anime.id}", 0) < previous
        ) previous else nextReminderOccurrence(day!!, time, leadMinutes = settings.leadMinutes).toInstant().toEpochMilli()

        if (endOfUntilDay != null && due > endOfUntilDay) {
            stopScheduling(anime.id)
            return null
        }

        // Remove the old periodic implementation once, without resetting current one-shot work.
        if (!state.getBoolean("migrated_${anime.id}", false)) {
            workManager.cancelUniqueWork("anime_reminder_${anime.id}")
            alarmManager.cancel(legacyIntent(anime.id))
        }
        if (!afterDelivery && previous > 0 && previous != due) workManager.cancelUniqueWork(workName(anime.id, previous))
        state.edit().putLong("due_${anime.id}", due).putString("signature_${anime.id}", signature)
            .putBoolean("migrated_${anime.id}", true).apply()
        return enqueue(anime.id, due)
    }

    private fun enqueue(animeId: Long, due: Long): String {
        val triggerAt = due.coerceAtLeast(System.currentTimeMillis() + 500)
        val intent = alarmIntent(animeId, due)
        var exact = false
        if (canScheduleExactAlarms()) {
            try {
                // 使用 setAlarmClock 替代 setExactAndAllowWhileIdle
                // setAlarmClock 在 Doze 模式下优先级最高，几乎不会被系统延迟
                // 同时会在状态栏显示闹钟图标，用户能感知到提醒已设置
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAt, alarmClockInfoIntent(animeId)),
                    intent
                )
                exact = true
            } catch (_: SecurityException) { /* Permission can change after canScheduleExactAlarms(). */ }
        }
        if (!exact) alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        val request = OneTimeWorkRequestBuilder<AnimeReminderWorker>()
            .setInitialDelay((triggerAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(AnimeReminderWorker.KEY_ANIME_ID to animeId, KEY_DUE to due))
            .addTag(TAG).addTag("reminder_anime_$animeId").build()
        workManager.enqueueUniqueWork(workName(animeId, due), ExistingWorkPolicy.KEEP, request)
        return if (exact) "exact" else "inexact"
    }

    /** Returns only after notification posting and scheduling the next occurrence have finished. */
    suspend fun deliver(animeId: Long, due: Long, path: String) = mutex.withLock {
        if (animeId == TEST_ANIME_ID) {
            if (due <= 0 || due != state.getLong("test_due", 0)) return@withLock
            if (System.currentTimeMillis() < due) { enqueue(animeId, due); return@withLock }
            val posted = AnimeReminderNotifier.showTest(appContext, scheduled = true)
            state.edit().remove("test_due").putString("last_result", if (posted) "后台定时测试已送达 · $path" else "后台测试触发，通知被系统关闭")
                .putLong("last_check", System.currentTimeMillis()).apply()
            return@withLock
        }
        val anime = AniMeowDatabase.getInstance(appContext).libraryDao().getAnime(animeId)
        if (anime == null) { cancel(animeId); return@withLock }
        val settings = ReminderPreferences(appContext).snapshot()
        val signature = "${anime.reminderDay}|${anime.reminderTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }}|${settings.leadMinutes}|${ZoneId.systemDefault().id}"
        if (settings.paused || anime.deletedAt != null || anime.reminderDay !in 1..7 || anime.reminderTime.isNullOrBlank()) {
            stopScheduling(animeId)
            return@withLock
        }
        // A queued alarm/worker may outlive a status or limit change. Recheck before posting.
        if ((settings.autoStopWhenCompleted && anime.status.trim() in com.animeow.app.data.REMINDER_STOP_STATUSES) ||
            shouldStopForLimit(storedRemainingCount(animeId), storedUntilDate(animeId), java.time.LocalDate.now())
        ) {
            stopScheduling(animeId)
            return@withLock
        }
        if (due <= 0 || due != state.getLong("due_$animeId", 0) || signature != state.getString("signature_$animeId", null)) {
            scheduleLocked(anime, afterDelivery = true)
            return@withLock
        }
        if (state.getLong("processed_$animeId", 0) >= due) return@withLock
        val now = System.currentTimeMillis()
        if (now < due) { enqueue(animeId, due); return@withLock }
        val expired = now - due > CATCH_UP_MILLIS
        val posted = !expired && AnimeReminderNotifier.show(appContext, anime)
        var limitReached = false
        // 只有真正送达才消耗次数：错过的提醒、通知权限被关闭的提醒都不该扣掉用户设定的次数。
        if (posted) {
            remainingAfterDelivery(storedRemainingCount(animeId))?.let { next ->
                state.edit().putInt("remaining_count_$animeId", next).apply()
                if (next <= 0) {
                    limitReached = true
                }
            }
        }
        val untilLimitDate = storedUntilDate(animeId)
        val endOfUntilDay = untilLimitDate?.atTime(LocalTime.MAX)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        if (endOfUntilDay != null && System.currentTimeMillis() >= endOfUntilDay) {
            limitReached = true
        }

        state.edit().putLong("processed_$animeId", due).putLong("last_check", now)
            .putString("last_result", when {
                posted && limitReached -> "《${anime.title}》已发送，并已完成设定的全部提醒 · $path"
                posted -> "《${anime.title}》已发送 · $path"
                expired -> "错过的提醒已过期，未消耗设定次数，已安排下一周"
                else -> "提醒已触发，但通知权限或渠道被关闭，未消耗设定次数"
            }).apply()

        if (limitReached) {
            stopScheduling(animeId)
            return@withLock
        }
        // Each occurrence has a different work name: never cancel the running worker itself.
        scheduleLocked(anime, afterDelivery = true)
    }

    /**
     * 只停止调度，保留用户设置的结束条件（剩余次数、截止日期）。
     *
     * 自动停止必须走这里：暂停、看完、次数用尽、到期都曾复用 [cancel]，而 cancel 会顺手
     * 删掉次数与截止日期，于是"暂停再恢复""重启 App""改时区""恢复备份"都会把限制静默清空，
     * 提醒悄悄退回一直循环，界面上也没有任何提示。
     */
    fun stopScheduling(animeId: Long) {
        alarmManager.cancel(alarmIntent(animeId, 0))
        alarmManager.cancel(legacyIntent(animeId))
        workManager.cancelUniqueWork("anime_reminder_$animeId")
        workManager.cancelAllWorkByTag("reminder_anime_$animeId")
        state.edit().remove("due_$animeId").remove("signature_$animeId").remove("processed_$animeId").apply()
        AnimeReminderNotifier.cancel(appContext, animeId)
    }

    /** 用户主动关闭提醒、或作品被删除/移入回收站/合并时才清除结束条件。 */
    fun cancel(animeId: Long) {
        stopScheduling(animeId)
        clearLimit(animeId)
    }

    fun clearLimit(animeId: Long) {
        state.edit().remove("remaining_count_$animeId").remove("until_date_$animeId").apply()
    }

    suspend fun syncAll(database: AniMeowDatabase): ReminderSyncSummary = mutex.withLock {
        val configured = database.libraryDao().getActiveAnimes().filter { anime ->
            anime.reminderDay in 1..7 && anime.reminderTime?.let { runCatching { LocalTime.parse(it) }.isSuccess } == true
        }
        val activeIds = configured.map { it.id }.toSet()
        state.all.keys.filter { it.startsWith("due_") }.mapNotNull { it.removePrefix("due_").toLongOrNull() }
            .filterNot { it in activeIds }.forEach(::cancel)
        var exactCount = 0
        var inexactCount = 0
        var notRegistered = 0
        configured.forEach { anime ->
            when (scheduleLocked(anime)) {
                "exact" -> exactCount++
                "inexact" -> inexactCount++
                else -> notRegistered++
            }
        }
        // 诊断快照：只记"注册是否成功、走的哪条路径、权限当时状态"，
        // 不只记最后是否送达——上次故障排查时注册结果完全不可见。
        state.edit()
            .putLong("diag_sync_at", System.currentTimeMillis())
            .putInt("diag_configured", configured.size)
            .putInt("diag_exact", exactCount)
            .putInt("diag_inexact", inexactCount)
            .putInt("diag_stopped", notRegistered)
            .apply()
        ReminderSyncSummary(configured.size, canScheduleExactAlarms())
    }

    fun scheduleTest(): String {
        val due = System.currentTimeMillis() + 60_000
        state.getLong("test_due", 0).takeIf { it > 0 }?.let { workManager.cancelUniqueWork(workName(TEST_ANIME_ID, it)) }
        state.edit().putLong("test_due", due).apply()
        enqueue(TEST_ANIME_ID, due)
        return "已安排 1 分钟后的测试，可以返回桌面或锁屏等待"
    }

    fun diagnostics(): String {
        val lines = mutableListOf<String>()
        val last = state.getLong("last_check", 0)
        lines += state.getString("last_result", null) ?: "尚无执行记录"
        if (last != 0L) lines += formatDiagnosticTime(last)
        val syncAt = state.getLong("diag_sync_at", 0)
        if (syncAt > 0) {
            val configured = state.getInt("diag_configured", 0)
            val exact = state.getInt("diag_exact", 0)
            val inexact = state.getInt("diag_inexact", 0)
            val stopped = state.getInt("diag_stopped", 0)
            lines += "上次调度注册 ${formatDiagnosticTime(syncAt)}：配置 $configured · 系统闹钟 $exact（回退 $inexact） · 未注册 $stopped"
        } else {
            lines += "尚无调度注册记录"
        }
        lines += "精确闹钟权限：" + if (canScheduleExactAlarms()) "已授权" else "未授权"
        lines += "电池优化白名单：" + if (isIgnoringBatteryOptimizations()) "已加入" else "未加入"
        return lines.joinToString("\n")
    }

    private fun formatDiagnosticTime(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))

    fun canScheduleExactAlarms(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /** 检测是否已加入电池优化白名单（忽略电池优化） */
    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    private fun alarmClockInfoIntent(animeId: Long): PendingIntent {
        // 点击状态栏闹钟图标时打开应用
        // setAlarmClock 的核心价值是 Doze 模式下的高优先级唤醒，跳转页面为次要功能
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        return PendingIntent.getActivity(
            appContext,
            animeId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun alarmIntent(animeId: Long, due: Long): PendingIntent = PendingIntent.getBroadcast(appContext, 0,
        Intent(appContext, AnimeReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            data = Uri.parse("animeow://reminder/$animeId")
            putExtra(AnimeReminderWorker.KEY_ANIME_ID, animeId)
            putExtra(KEY_DUE, due)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun legacyIntent(animeId: Long): PendingIntent = PendingIntent.getBroadcast(appContext, animeId.toInt(),
        Intent(appContext, AnimeReminderReceiver::class.java).apply { action = ACTION_REMINDER },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun workName(animeId: Long, due: Long) = "anime_occurrence_${animeId}_$due"

    companion object {
        const val TAG = "anime_reminders"
        const val KEY_DUE = "occurrence_due"
        const val TEST_ANIME_ID = -2L
        internal const val CATCH_UP_MILLIS = 6 * 60 * 60 * 1000L
        private const val ACTION_REMINDER = "com.animeow.app.action.EXACT_REMINDER"
        private val mutex = Mutex()
    }
}

/**
 * 是否应该因为结束条件而停止调度。
 *
 * 抽成纯函数是为了让"次数用尽 / 到期"这套规则能被单元测试直接覆盖 ——
 * 之前这两个测试只是在测试体里手写了一遍算术，没有碰过生产代码。
 */
internal fun shouldStopForLimit(
    remainingCount: Int?,
    untilDate: java.time.LocalDate?,
    today: java.time.LocalDate,
): Boolean {
    if (remainingCount != null && remainingCount <= 0) return true
    if (untilDate != null && today.isAfter(untilDate)) return true
    return false
}

/** 送达一次之后的剩余次数；null 表示未设置次数（一直循环）。 */
internal fun remainingAfterDelivery(remainingCount: Int?): Int? =
    remainingCount?.let { (it - 1).coerceAtLeast(0) }

internal fun nextReminderOccurrence(
    day: Int,
    time: LocalTime,
    now: ZonedDateTime = ZonedDateTime.now(),
    leadMinutes: Int = 0,
): ZonedDateTime {
    val date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.of(day)))
    return (0..2).asSequence().map { week ->
        date.plusWeeks(week.toLong()).atTime(time).atZone(now.zone).minusMinutes(leadMinutes.coerceIn(0, 120).toLong())
    }.first { it.isAfter(now) }
}

internal fun nextReminderDelay(
    day: Int,
    time: LocalTime,
    now: LocalDateTime = LocalDateTime.now(),
    leadMinutes: Int = 0,
    zone: ZoneId = ZoneId.systemDefault(),
): Duration = now.atZone(zone).let { Duration.between(it, nextReminderOccurrence(day, time, it, leadMinutes)) }
