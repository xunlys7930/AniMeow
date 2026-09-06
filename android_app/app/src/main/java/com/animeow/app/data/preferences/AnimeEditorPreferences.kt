package com.animeow.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.animeEditorDataStore by preferencesDataStore(name = "anime_editor_preferences")

enum class AnimeEditorModule(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    BASIC("basic", "封面与基本资料", "标题、封面、联网补全和评分"),
    PROGRESS("progress", "状态与进度", "收藏状态、当前进度与目标进度"),
    TAGS("tags", "标签", "检索和整理作品的自定义标签"),
    DETAILS("details", "日期与备注", "制作信息、日期、集数拆分和评价"),
    REMINDER("reminder", "更新提醒", "每周提醒的星期和时间"),
    RELATED("related", "系列归纳", "系列选择、重名提示与自动归纳建议");

    companion object {
        fun fromStorage(value: String): AnimeEditorModule? = entries.firstOrNull { it.storageKey == value }
    }
}

enum class AnimeEditorPreset(val storageKey: String, val displayName: String, val description: String) {
    QUICK("quick", "快速收录", "只保留基本资料和观看进度"),
    COMPLETE("complete", "完整编辑", "显示全部模块，适合精细整理"),
    CUSTOM("custom", "我的布局", "按你的模块顺序和显隐显示");

    companion object {
        fun fromStorage(value: String?): AnimeEditorPreset =
            entries.firstOrNull { it.storageKey == value } ?: COMPLETE
    }
}

enum class AnimeEditorDensity(
    val storageKey: String,
    val displayName: String,
    val spacingScale: Float,
    val itemScale: Float,
    val description: String,
) {
    COMPACT("compact", "紧凑", 0.64f, 0.82f, "压缩模块留白和封面预览，一屏编辑更多字段"),
    COMFORTABLE("comfortable", "舒适", 1f, 1f, "保持输入区域与留白平衡"),
    RELAXED("relaxed", "宽松", 1.34f, 1.14f, "放大模块间距和预览，更适合逐项填写");

    companion object {
        fun fromStorage(value: String?): AnimeEditorDensity =
            entries.firstOrNull { it.storageKey == value } ?: COMFORTABLE
    }
}

enum class EditorExitBehavior(val storageKey: String, val displayName: String, val description: String) {
    ASK("ask", "每次询问", "有修改时提供保存、放弃和继续编辑"),
    AUTO_SAVE("auto_save", "自动保存", "返回时校验并自动保存"),
    DISCARD("discard", "直接放弃", "返回时不保留本次修改");

    companion object {
        fun fromStorage(value: String?): EditorExitBehavior =
            entries.firstOrNull { it.storageKey == value } ?: ASK
    }
}

data class AnimeEditorSettings(
    val preset: AnimeEditorPreset = AnimeEditorPreset.COMPLETE,
    val density: AnimeEditorDensity = AnimeEditorDensity.COMFORTABLE,
    val exitBehavior: EditorExitBehavior = EditorExitBehavior.ASK,
    val moduleOrder: List<AnimeEditorModule> = AnimeEditorModule.entries,
    val hiddenModules: Set<AnimeEditorModule> = emptySet(),
) {
    fun normalized(): AnimeEditorSettings {
        val order = moduleOrder.distinct() + AnimeEditorModule.entries.filterNot(moduleOrder::contains)
        return copy(
            moduleOrder = order,
            hiddenModules = hiddenModules.intersect(AnimeEditorModule.entries.toSet()) - AnimeEditorModule.BASIC,
        )
    }
}

fun animeEditorPresetSettings(preset: AnimeEditorPreset): AnimeEditorSettings = when (preset) {
    AnimeEditorPreset.QUICK -> AnimeEditorSettings(
        preset = preset,
        density = AnimeEditorDensity.COMPACT,
        hiddenModules = setOf(
            AnimeEditorModule.TAGS,
            AnimeEditorModule.DETAILS,
            AnimeEditorModule.REMINDER,
            AnimeEditorModule.RELATED,
        ),
    )
    AnimeEditorPreset.COMPLETE -> AnimeEditorSettings(preset = preset)
    AnimeEditorPreset.CUSTOM -> AnimeEditorSettings(preset = preset)
}

class AnimeEditorPreferences(context: Context) {
    private val dataStore = context.applicationContext.animeEditorDataStore

    val settings: Flow<AnimeEditorSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::decodeAnimeEditorSettings)

    suspend fun snapshot(): AnimeEditorSettings = settings.first()

    suspend fun save(value: AnimeEditorSettings) {
        val normalized = value.normalized()
        dataStore.edit { preferences ->
            preferences[Keys.PRESET] = normalized.preset.storageKey
            preferences[Keys.DENSITY] = normalized.density.storageKey
            preferences[Keys.EXIT_BEHAVIOR] = normalized.exitBehavior.storageKey
            preferences[Keys.MODULE_ORDER] = normalized.moduleOrder.joinToString(",", transform = AnimeEditorModule::storageKey)
            preferences[Keys.HIDDEN_MODULES] = normalized.hiddenModules.map(AnimeEditorModule::storageKey).toSet()
        }
    }

    private object Keys {
        val PRESET = stringPreferencesKey("preset")
        val DENSITY = stringPreferencesKey("density")
        val EXIT_BEHAVIOR = stringPreferencesKey("exit_behavior")
        val MODULE_ORDER = stringPreferencesKey("module_order")
        val HIDDEN_MODULES = stringSetPreferencesKey("hidden_modules")
    }

    private fun decodeAnimeEditorSettings(preferences: Preferences): AnimeEditorSettings {
        val order = preferences[Keys.MODULE_ORDER].orEmpty().split(',')
            .mapNotNull(AnimeEditorModule::fromStorage)
        val hidden = preferences[Keys.HIDDEN_MODULES].orEmpty()
            .mapNotNull(AnimeEditorModule::fromStorage)
            .toSet()
        return AnimeEditorSettings(
            preset = AnimeEditorPreset.fromStorage(preferences[Keys.PRESET]),
            density = AnimeEditorDensity.fromStorage(preferences[Keys.DENSITY]),
            exitBehavior = EditorExitBehavior.fromStorage(preferences[Keys.EXIT_BEHAVIOR]),
            moduleOrder = order.ifEmpty { AnimeEditorModule.entries },
            hiddenModules = hidden,
        ).normalized()
    }
}
