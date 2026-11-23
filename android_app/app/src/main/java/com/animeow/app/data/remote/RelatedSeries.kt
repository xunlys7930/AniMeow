package com.animeow.app.data.remote

internal val SERIES_RELATIONS = setOf("前传", "续集", "番外篇", "总集篇", "不同演绎", "相同世界观", "不同世界观", "其他")

internal data class RelatedSeriesCandidates(val items: List<RemoteAnime>, val incomplete: Boolean)

internal suspend fun collectSeriesCandidates(seed: RemoteAnime, fetch: suspend (Long, String) -> List<RemoteAnime>): RelatedSeriesCandidates {
    require(seed.importSource == "bangumi" && seed.importId.toLongOrNull()?.let { it > 0 } == true) { "同系列候选目前支持 Bangumi 来源，其他来源可使用搜索多选添加" }
    val found = linkedMapOf(seed.importId to seed)
    val queue = ArrayDeque<String>().apply { add(seed.importId) }
    val visited = mutableSetOf<String>()
    var incomplete = false
    while (queue.isNotEmpty() && visited.size < 12 && found.size < 60) {
        val id = queue.removeFirst()
        if (!visited.add(id)) continue
        val items = try { fetch(id.toLong(), seed.subjectType) }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { if (visited.size == 1) throw error else { incomplete = true; continue } }
        for (item in items) {
            if (item.importId !in found && found.size < 60) { found[item.importId] = item; queue.add(item.importId) }
            else if (item.importId !in found) incomplete = true
        }
    }
    return RelatedSeriesCandidates(found.values.toList(), incomplete || queue.isNotEmpty())
}
