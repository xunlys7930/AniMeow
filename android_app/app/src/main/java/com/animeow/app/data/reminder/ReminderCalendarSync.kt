package com.animeow.app.data.reminder

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/** 一条要写入系统日历的每周提醒。 */
internal data class CalendarReminderSpec(
    val animeId: Long,
    val title: String,
    /** 1..7 对应周一..周日，与 AnimeEntity.reminderDay 一致。 */
    val dayOfWeek: Int,
    val time: LocalTime,
    /** 剩余提醒次数；null 表示未设置次数（一直循环）。 */
    val remainingCount: Int?,
    val untilDate: LocalDate?,
    /** 提前多少分钟提醒，与应用内「提前通知」保持一致。 */
    val leadMinutes: Int,
)

/** 同步结果计数；文案由调用方拼装。 */
internal data class CalendarSyncResult(
    val calendarName: String,
    val created: Int,
    val updated: Int,
    val removed: Int,
    val failed: Int,
)

/**
 * 日历事件的纯计算部分：RRULE、首次开始时间与标题。
 * 不碰 ContentResolver，方便 JVM 单测直接覆盖。
 */
internal object CalendarReminderPlanner {
    private val weekdayTokens = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
    private val untilFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun weekdayToken(day: Int): String {
        require(day in 1..7) { "星期取值应为 1..7，实际为 $day" }
        return weekdayTokens[day - 1]
    }

    fun eventTitle(title: String): String = "《$title》追番提醒"

    /**
     * 每周重复规则。剩余 0 次表示已用尽，返回 null（不再写入日历）；
     * 次数与截止日期同时存在时两条限制都生效。
     */
    fun buildRrule(day: Int, remainingCount: Int?, untilDate: LocalDate?): String? {
        if (remainingCount == 0) return null
        val base = "FREQ=WEEKLY;BYDAY=${weekdayToken(day)}"
        val count = remainingCount?.let { ";COUNT=$it" } ?: ""
        val until = untilDate?.let { ";UNTIL=${untilFormat.format(it.atTime(23, 59, 59))}" } ?: ""
        return base + count + until
    }

    /** 首次开始时间取「下一次该星期几的这个时刻」，与应用提醒的触发时间一致（不含提前量）。 */
    fun nextStart(day: Int, time: LocalTime, now: ZonedDateTime): ZonedDateTime =
        nextReminderOccurrence(day, time, now, leadMinutes = 0)
}

/**
 * 把每周提醒写进系统日历作为备份。
 * 事件按 animeId 记在 SharedPreferences 里，重复同步走更新，取消提醒后同步会清理旧日程。
 */
internal class ReminderCalendarWriter(context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("reminder_calendar_events", Context.MODE_PRIVATE)

    companion object {
        private const val EVENT_MINUTES = 30L
        private const val PREF_PREFIX = "event_"
    }

    fun sync(specs: List<CalendarReminderSpec>, now: ZonedDateTime = ZonedDateTime.now()): CalendarSyncResult {
        val (calendarId, calendarName) = findWritableCalendar()
            ?: throw IllegalStateException("没有可写的系统日历，请先在系统日历应用中登录或创建一个日历")
        var created = 0
        var updated = 0
        var failed = 0
        val activeIds = mutableSetOf<Long>()
        specs.forEach { spec ->
            val rrule = CalendarReminderPlanner.buildRrule(spec.dayOfWeek, spec.remainingCount, spec.untilDate)
                ?: return@forEach
            activeIds += spec.animeId
            try {
                val start = CalendarReminderPlanner.nextStart(spec.dayOfWeek, spec.time, now)
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, CalendarReminderPlanner.eventTitle(spec.title))
                    put(CalendarContract.Events.DESCRIPTION,
                        "由追番喵 AniMeow 同步的每周追番提醒；在应用内修改或取消提醒后，再次点「同步到日历」即可更新或清理。")
                    put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
                    put(CalendarContract.Events.DTEND, start.plusMinutes(EVENT_MINUTES).toInstant().toEpochMilli())
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    put(CalendarContract.Events.RRULE, rrule)
                    put(CalendarContract.Events.ALL_DAY, 0)
                }
                val storedId = prefs.getLong(PREF_PREFIX + spec.animeId, -1L)
                val eventId = if (storedId > 0 && eventExists(storedId)) {
                    resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, storedId), values, null, null)
                    updated++
                    storedId
                } else {
                    val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
                        ?: throw IllegalStateException("系统日历拒绝写入")
                    created++
                    uri.lastPathSegment?.toLongOrNull() ?: -1L
                }
                if (eventId > 0) {
                    prefs.edit().putLong(PREF_PREFIX + spec.animeId, eventId).apply()
                    // 提醒行失败不回滚事件本身：日历仍可见，提醒以日历应用设置为准。
                    runCatching { syncReminderRow(eventId, spec.leadMinutes) }
                }
            } catch (_: Exception) {
                failed++
            }
        }
        var removed = 0
        prefs.all.keys.toList().forEach { key ->
            val animeId = key.removePrefix(PREF_PREFIX).toLongOrNull() ?: return@forEach
            if (animeId !in activeIds) {
                val eventId = prefs.getLong(key, -1L)
                if (eventId > 0) runCatching {
                    resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null)
                }
                prefs.edit().remove(key).apply()
                removed++
            }
        }
        return CalendarSyncResult(calendarName, created, updated, removed, failed)
    }

    private fun findWritableCalendar(): Pair<Long, String>? {
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}" +
                " AND ${CalendarContract.Calendars.VISIBLE}=1",
            null, null,
        )?.use { cursor ->
            var fallback: Pair<Long, String>? = null
            while (cursor.moveToNext()) {
                val entry = cursor.getLong(0) to (cursor.getString(1) ?: "系统日历")
                if (cursor.getInt(2) == 1) return entry
                if (fallback == null) fallback = entry
            }
            return fallback
        }
        return null
    }

    private fun eventExists(eventId: Long): Boolean =
        resolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events._ID), null, null, null,
        )?.use { it.count > 0 } ?: false

    private fun syncReminderRow(eventId: Long, leadMinutes: Int) {
        resolver.delete(CalendarContract.Reminders.CONTENT_URI,
            "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(eventId.toString()))
        resolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, leadMinutes.coerceIn(0, 120))
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
    }
}
