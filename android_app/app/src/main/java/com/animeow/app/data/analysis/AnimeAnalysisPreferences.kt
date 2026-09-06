package com.animeow.app.data.analysis

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.animeAnalysisDataStore by preferencesDataStore(name = "anime_analysis_preferences")

data class AnimeAnalysisSettings(
    val endpoint: String = DEFAULT_ENDPOINT,
    val model: String = DEFAULT_MODEL,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val promptTemplate: String = DEFAULT_PROMPT_TEMPLATE,
    val temperature: Float = 0.85f,
)

class AnimeAnalysisPreferences(context: Context) {
    private val dataStore = context.applicationContext.animeAnalysisDataStore

    val settings: Flow<AnimeAnalysisSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { values ->
            AnimeAnalysisSettings(
                endpoint = values[Keys.ENDPOINT] ?: DEFAULT_ENDPOINT,
                model = values[Keys.MODEL] ?: DEFAULT_MODEL,
                systemPrompt = values[Keys.SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT,
                promptTemplate = values[Keys.PROMPT_TEMPLATE] ?: DEFAULT_PROMPT_TEMPLATE,
                temperature = (values[Keys.TEMPERATURE] ?: 0.85f).coerceIn(0f, 2f),
            )
        }

    suspend fun snapshot(): AnimeAnalysisSettings = settings.first()

    suspend fun save(settings: AnimeAnalysisSettings) {
        dataStore.edit { values ->
            values[Keys.ENDPOINT] = settings.endpoint.trim()
            values[Keys.MODEL] = settings.model.trim()
            values[Keys.SYSTEM_PROMPT] = settings.systemPrompt.trim()
            values[Keys.PROMPT_TEMPLATE] = settings.promptTemplate.trim()
            values[Keys.TEMPERATURE] = settings.temperature.coerceIn(0f, 2f)
        }
    }

    suspend fun reset() {
        dataStore.edit { it.clear() }
    }

    private object Keys {
        val ENDPOINT = stringPreferencesKey("endpoint")
        val MODEL = stringPreferencesKey("model")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val PROMPT_TEMPLATE = stringPreferencesKey("prompt_template")
        val TEMPERATURE = floatPreferencesKey("temperature")
    }
}

const val DEFAULT_ENDPOINT = "https://api.deepseek.com"
const val DEFAULT_MODEL = "deepseek-v4-flash"
const val DEFAULT_SYSTEM_PROMPT = "你是 AniMeow 里的温柔看番风格分析助手。"
const val DEFAULT_PROMPT_TEMPLATE = """你是一位语气可爱、温柔、观察力很强的二次元看番搭子。请根据下面的追番统计摘要，分析我的看番习惯和风格。

要求：
1. 用中文输出，语气可爱但不要幼稚。
2. 重点分析偏好的题材/标签、观看完成度、评分倾向、追番活跃度和可能的口味画像。
3. 给 3 条轻量建议，比如下一步可以补什么类型、如何整理片单。
4. 不要做心理诊断，不要过度推断隐私。
5. 不要原样复述 JSON，只输出分析结果。

追番统计摘要：
{stats}"""
