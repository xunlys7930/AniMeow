package com.animeow.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.calendarDataStore by preferencesDataStore(name = "calendar_preferences")

enum class CalendarPagePreset(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    MONTH_FOCUS("month_focus", "清爽月历", "紧凑月历优先，弱化封面与回忆信息"),
    BALANCED("balanced", "均衡", "月历、当日日程与历史回顾保持平衡"),
    JOURNAL("journal", "追番手账", "日程和回忆优先，更适合回顾观看轨迹"),
    CUSTOM("custom", "自定义", "当前配置由你单独调整");

    companion object {
        fun fromStorage(value: String?): CalendarPagePreset =
            entries.firstOrNull { it.storageKey == value } ?: BALANCED
    }
}

enum class CalendarMarkerStyle(
    val storageKey: String,
    val displayName: String,
) {
    TINT("tint", "色彩底纹"),
    DOTS("dots", "事件圆点"),
    COUNT("count", "数量角标");

    companion object {
        fun fromStorage(value: String?): CalendarMarkerStyle =
            entries.firstOrNull { it.storageKey == value } ?: TINT
    }
}

enum class CalendarContentDensity(
    val storageKey: String,
    val displayName: String,
    val spacingScale: Float,
    val itemScale: Float,
    val description: String,
) {
    COMPACT("compact", "紧凑", 0.65f, 0.8f, "缩小日程卡片和封面，一屏查看更多事件"),
    COMFORTABLE("comfortable", "舒适", 1f, 1f, "保持日历和日程信息平衡"),
    RELAXED("relaxed", "宽松", 1.35f, 1.2f, "放大封面、卡片和行距，阅读更加从容");

    companion object {
        fun fromStorage(value: String?): CalendarContentDensity =
            entries.firstOrNull { it.storageKey == value } ?: COMFORTABLE
    }
}

enum class CalendarModule(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    MONTH("month", "月历", "按月份浏览作品事件与打卡记录"),
    SCHEDULE("schedule", "选中日期日程", "展示当前选中日期的全部事件"),
    HISTORY("history", "那年今日", "回顾其他年份同一天发生的记录");

    companion object {
        fun fromStorage(value: String): CalendarModule? = entries.firstOrNull { it.storageKey == value }
    }
}

data class CalendarDisplaySettings(
    val preset: CalendarPagePreset = CalendarPagePreset.BALANCED,
    val markerStyle: CalendarMarkerStyle = CalendarMarkerStyle.TINT,
    val density: CalendarContentDensity = CalendarContentDensity.COMFORTABLE,
    val showCovers: Boolean = true,
    val showLegend: Boolean = true,
    val moduleOrder: List<CalendarModule> = CalendarModule.entries,
    val hiddenModules: Set<CalendarModule> = emptySet(),
    val enabledEventTypes: Set<String> = DEFAULT_CALENDAR_EVENT_TYPES,
) {
    fun normalized(): CalendarDisplaySettings {
        val normalizedOrder = moduleOrder.distinct() + CalendarModule.entries.filterNot(moduleOrder::contains)
        val normalizedHidden = hiddenModules.intersect(CalendarModule.entries.toSet())
        val normalizedEvents = enabledEventTypes.intersect(DEFAULT_CALENDAR_EVENT_TYPES)
        return copy(
            moduleOrder = normalizedOrder,
            hiddenModules = if (normalizedHidden.size == CalendarModule.entries.size) {
                normalizedHidden - normalizedOrder.first()
            } else {
                normalizedHidden
            },
            enabledEventTypes = normalizedEvents.ifEmpty { DEFAULT_CALENDAR_EVENT_TYPES },
        )
    }
}

class CalendarPreferences(context: Context) {
    private val dataStore = context.applicationContext.calendarDataStore

    val settings: Flow<CalendarDisplaySettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::decodeCalendarSettings)

    suspend fun snapshot(): CalendarDisplaySettings = settings.first()

    suspend fun save(settings: CalendarDisplaySettings) {
        val normalized = settings.normalized()
        dataStore.edit { values ->
            values[CalendarPreferenceKeys.PRESET] = normalized.preset.storageKey
            values[CalendarPreferenceKeys.MARKER_STYLE] = normalized.markerStyle.storageKey
            values[CalendarPreferenceKeys.DENSITY] = normalized.density.storageKey
            values[CalendarPreferenceKeys.SHOW_COVERS] = normalized.showCovers
            values[CalendarPreferenceKeys.SHOW_LEGEND] = normalized.showLegend
            values[CalendarPreferenceKeys.MODULE_ORDER] = normalized.moduleOrder.joinToString(",", transform = CalendarModule::storageKey)
            values[CalendarPreferenceKeys.HIDDEN_MODULES] = normalized.hiddenModules.map(CalendarModule::storageKey).toSet()
            values[CalendarPreferenceKeys.ENABLED_EVENT_TYPES] = normalized.enabledEventTypes
        }
    }

    suspend fun reset() {
        dataStore.edit { it.clear() }
    }

}

fun calendarPresetSettings(
    preset: CalendarPagePreset,
    enabledEventTypes: Set<String> = DEFAULT_CALENDAR_EVENT_TYPES,
): CalendarDisplaySettings = when (preset) {
    CalendarPagePreset.MONTH_FOCUS -> CalendarDisplaySettings(
        preset = preset,
        markerStyle = CalendarMarkerStyle.DOTS,
        density = CalendarContentDensity.COMPACT,
        showCovers = false,
        showLegend = true,
        hiddenModules = setOf(CalendarModule.HISTORY),
        enabledEventTypes = enabledEventTypes,
    )
    CalendarPagePreset.BALANCED -> CalendarDisplaySettings(enabledEventTypes = enabledEventTypes)
    CalendarPagePreset.JOURNAL -> CalendarDisplaySettings(
        preset = preset,
        markerStyle = CalendarMarkerStyle.COUNT,
        density = CalendarContentDensity.RELAXED,
        showCovers = true,
        showLegend = false,
        moduleOrder = listOf(CalendarModule.SCHEDULE, CalendarModule.HISTORY, CalendarModule.MONTH),
        enabledEventTypes = enabledEventTypes,
    )
    CalendarPagePreset.CUSTOM -> CalendarDisplaySettings(
        preset = preset,
        enabledEventTypes = enabledEventTypes,
    )
}

private fun decodeCalendarSettings(values: Preferences): CalendarDisplaySettings {
    val order = values[CalendarPreferenceKeys.MODULE_ORDER]
        .orEmpty()
        .split(',')
        .mapNotNull(CalendarModule::fromStorage)
    val hidden = values[CalendarPreferenceKeys.HIDDEN_MODULES]
        .orEmpty()
        .mapNotNull(CalendarModule::fromStorage)
        .toSet()
    val enabledEvents = values[CalendarPreferenceKeys.ENABLED_EVENT_TYPES]
        ?.intersect(DEFAULT_CALENDAR_EVENT_TYPES)
        .orEmpty()
        .ifEmpty { DEFAULT_CALENDAR_EVENT_TYPES }
    return CalendarDisplaySettings(
        preset = CalendarPagePreset.fromStorage(values[CalendarPreferenceKeys.PRESET]),
        markerStyle = CalendarMarkerStyle.fromStorage(values[CalendarPreferenceKeys.MARKER_STYLE]),
        density = CalendarContentDensity.fromStorage(values[CalendarPreferenceKeys.DENSITY]),
        showCovers = values[CalendarPreferenceKeys.SHOW_COVERS] ?: true,
        showLegend = values[CalendarPreferenceKeys.SHOW_LEGEND] ?: true,
        moduleOrder = order.ifEmpty { CalendarModule.entries },
        hiddenModules = hidden,
        enabledEventTypes = enabledEvents,
    ).normalized()
}

private object CalendarPreferenceKeys {
    val PRESET = stringPreferencesKey("preset")
    val MARKER_STYLE = stringPreferencesKey("marker_style")
    val DENSITY = stringPreferencesKey("density")
    val SHOW_COVERS = booleanPreferencesKey("show_covers")
    val SHOW_LEGEND = booleanPreferencesKey("show_legend")
    val MODULE_ORDER = stringPreferencesKey("module_order")
    val HIDDEN_MODULES = androidx.datastore.preferences.core.stringSetPreferencesKey("hidden_modules")
    val ENABLED_EVENT_TYPES = androidx.datastore.preferences.core.stringSetPreferencesKey("enabled_event_types")
}

val DEFAULT_CALENDAR_EVENT_TYPES = setOf(
    "broadcast",
    "air_date",
    "watch_start",
    "watch_finish",
    "watch_record",
    "reminder",
)
