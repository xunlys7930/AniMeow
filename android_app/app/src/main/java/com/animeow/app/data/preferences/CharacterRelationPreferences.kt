package com.animeow.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.relationPresetStore by preferencesDataStore(name = "character_relation_presets")

data class RelationRolePreset(val sourceRole: String, val targetRole: String) {
    val label: String get() = if (sourceRole == targetRole) sourceRole
        else "${sourceRole.ifBlank { "留空" }} / ${targetRole.ifBlank { "留空" }}"
}

val BUILT_IN_RELATION_PRESETS = listOf(
    RelationRolePreset("老师", "学生"), RelationRolePreset("父亲", "儿子"),
    RelationRolePreset("母亲", "女儿"), RelationRolePreset("朋友", "朋友"),
)

/** 设备上的快捷填写偏好；不改变已保存关系，不加入资料库或角色分享包。 */
class CharacterRelationPreferences internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.relationPresetStore)
    private val key = stringPreferencesKey("custom_role_pairs")
    val presets = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { decode(it[key]) }

    suspend fun add(source: String, target: String) {
        val preset = RelationRolePreset(source.trim().take(120), target.trim().take(120))
        require(preset.sourceRole.isNotBlank() || preset.targetRole.isNotBlank()) { "请先填写至少一端称谓" }
        require(preset !in BUILT_IN_RELATION_PRESETS) { "这个称谓预设已经存在" }
        store.edit { values ->
            val current = decode(values[key])
            require(preset !in current) { "这个称谓预设已经存在" }
            require(current.size < 20) { "最多保存 20 个自定义预设，请先移除不用的预设" }
            values[key] = encode(current + preset)
        }
    }

    suspend fun remove(preset: RelationRolePreset) {
        store.edit { values -> values[key] = encode(decode(values[key]).filterNot { it == preset }) }
    }

    private fun decode(raw: String?): List<RelationRolePreset> = runCatching {
        val array = JSONArray(raw ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            RelationRolePreset(item.optString("source").trim().take(120), item.optString("target").trim().take(120))
                .takeIf { it.sourceRole.isNotBlank() || it.targetRole.isNotBlank() }
        }.distinct().take(20)
    }.getOrDefault(emptyList())

    private fun encode(presets: List<RelationRolePreset>): String = JSONArray().apply {
        presets.forEach { put(JSONObject().put("source", it.sourceRole).put("target", it.targetRole)) }
    }.toString()
}
