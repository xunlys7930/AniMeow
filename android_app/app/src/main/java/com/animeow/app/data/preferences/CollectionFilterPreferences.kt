package com.animeow.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.collectionFiltersStore by preferencesDataStore(name = "collection_filters")

/** Names, rather than device-local IDs, keep restored conditions from selecting unrelated tags. */
data class CollectionFilterConditions(
    val query: String = "",
    val status: String = "全部",
    val subjectType: String = "all",
    val tags: List<String> = emptyList(),
    val matchAllTags: Boolean = false,
    val years: List<String> = emptyList(),
    val sort: String = "recent",
    val ascending: Boolean = false,
) {
    fun normalized() = copy(query = query.trim(), tags = tags.distinct().sorted(), years = years.distinct().sorted())

    fun missingConditions(availableTags: List<String>, statuses: List<String>): List<String> =
        tags.filter { it !in availableTags }.map { "标签「$it」" } +
            if (status != "全部" && status !in statuses) listOf("状态「$status」") else emptyList()
}

data class CollectionFilterPlan(val id: String, val name: String, val module: String, val conditions: CollectionFilterConditions)

internal fun collectionFilterPlansToJson(plans: List<CollectionFilterPlan>): JSONArray = JSONArray().apply {
    plans.forEach { plan ->
        val value = plan.conditions.normalized()
        put(JSONObject().put("id", plan.id).put("name", plan.name).put("module", plan.module)
            .put("query", value.query).put("status", value.status).put("subjectType", value.subjectType)
            .put("tags", JSONArray(value.tags)).put("matchAllTags", value.matchAllTags)
            .put("years", JSONArray(value.years)).put("sort", value.sort).put("ascending", value.ascending))
    }
}

internal fun collectionFilterPlansFromJson(json: JSONArray): List<CollectionFilterPlan> {
    require(json.length() <= 100) { "筛选方案过多，请先整理后重试" }
    return (0 until json.length()).map { index ->
        val row = json.getJSONObject(index)
        fun strings(key: String): List<String> = row.optJSONArray(key)?.let { array ->
            require(array.length() <= 1000) { "筛选条件过多" }
            (0 until array.length()).map { array.getString(it).also { value -> require(value.length <= 200) { "筛选条件文字过长" } } }
        }.orEmpty()
        val id = row.getString("id"); val name = row.getString("name").trim(); val module = row.getString("module")
        require(id.isNotBlank() && id.length <= 100 && name.isNotBlank() && name.length <= 40) { "方案名称应为 1 至 40 个字" }
        require(module in listOf("library", "game", "audio")) { "此筛选方案的作品分类暂不支持" }
        val query = row.optString("query"); val status = row.optString("status", "全部")
        val subjectType = row.optString("subjectType", "all"); val sort = row.optString("sort", "recent")
        require(query.length <= 200 && status.length <= 200) { "查找或状态文字过长" }
        require(subjectType in listOf("all", "anime", "book")) { "此筛选方案的作品类型暂不支持" }
        require(sort in listOf("recent", "updated", "air_date", "finish_date", "rating", "title", "progress")) { "此筛选方案的排序方式暂不支持" }
        val conditions = CollectionFilterConditions(query, status, subjectType, strings("tags"), row.optBoolean("matchAllTags"), strings("years"), sort, row.optBoolean("ascending")).normalized()
        if (module != "library") require(subjectType == "all" && sort == "recent" && !conditions.ascending && conditions.years.isEmpty() && !conditions.matchAllTags &&
            (if (module == "game") conditions.tags.size <= 1 else conditions.tags.isEmpty())) { "该分类暂不支持方案中的部分筛选条件" }
        CollectionFilterPlan(id, name, module, conditions)
    }.also { plans ->
        require(plans.map { it.id }.distinct().size == plans.size) { "筛选方案编号重复" }
        require(plans.map { it.module to it.name }.distinct().size == plans.size) { "同一分类存在同名筛选方案，请整理后重试" }
    }
}

/** Private preferences: native backups include them; public appearance profiles never do. */
class CollectionFilterPreferences(context: Context) {
    private val store = context.applicationContext.collectionFiltersStore
    private val key = stringPreferencesKey("plans")
    val plans = store.data.map { collectionFilterPlansFromJson(JSONArray(it[key] ?: "[]")) }
    suspend fun snapshot(): JSONArray = collectionFilterPlansToJson(plans.first())
    suspend fun save(plan: CollectionFilterPlan) {
        store.edit { values ->
            val current = collectionFilterPlansFromJson(JSONArray(values[key] ?: "[]"))
            require(current.none { it.id != plan.id && it.module == plan.module && it.name == plan.name.trim() }) { "已有同名方案，请改名或选择该方案更新" }
            val next = current.filterNot { it.id == plan.id } + plan.copy(name = plan.name.trim(), conditions = plan.conditions.normalized())
            val encoded = collectionFilterPlansToJson(next)
            collectionFilterPlansFromJson(encoded)
            values[key] = encoded.toString()
        }
    }
    suspend fun delete(id: String) {
        store.edit { values -> values[key] = collectionFilterPlansToJson(
            collectionFilterPlansFromJson(JSONArray(values[key] ?: "[]")).filterNot { it.id == id }).toString() }
    }
    suspend fun restore(json: JSONArray, merge: Boolean = false) {
        val incoming = collectionFilterPlansFromJson(json)
        store.edit { values ->
            val local = if (merge) collectionFilterPlansFromJson(JSONArray(values[key] ?: "[]")) else emptyList()
            val combined = local + incoming.filter { row -> local.none { it.id == row.id || (it.module == row.module && it.name == row.name) } }
            val encoded = collectionFilterPlansToJson(combined)
            collectionFilterPlansFromJson(encoded)
            values[key] = encoded.toString()
        }
    }
}
