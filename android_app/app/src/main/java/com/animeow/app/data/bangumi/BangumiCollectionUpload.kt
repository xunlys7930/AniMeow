package com.animeow.app.data.bangumi

import com.animeow.app.data.games.GameEntity
import com.animeow.app.data.games.gameCollectionType
import com.animeow.app.data.games.gameStatuses
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.sourceReferences
import com.animeow.app.data.local.isBookSubjectType
import org.json.JSONObject
import com.animeow.app.data.cloud.safeCloudMessage

internal enum class BangumiUploadKind(val id: Int, val label: String) {
    ANIME(2, "动画"), BOOK(1, "书籍"), GAME(4, "游戏"),
}

internal data class BangumiUploadAccount(val userId: Long, val bangumiId: Long, val username: String)

internal data class BangumiUploadRemote(
    val subjectId: Long, val kind: BangumiUploadKind, val type: Int, val rate: Int,
    val progress: Int, val fingerprint: String, val snapshot: String,
    val title: String = subjectId.toString(),
) {
    val tags = JSONObject(snapshot).getJSONArray("tags").let { array -> (0 until array.length()).map(array::getString) }
    val comment = JSONObject(snapshot).getString("comment")
    val volumes = JSONObject(snapshot).getInt("vol_status")
    companion object {
        fun parse(json: JSONObject, kind: BangumiUploadKind): BangumiUploadRemote {
            fun number(key: String, range: LongRange): Long {
                val value = json.opt(key) as? Number ?: error("收藏字段读取不完整，请重新读取")
                val long = value.toLong()
                check(value.toDouble() == long.toDouble() && long in range) { "收藏字段无效，请重新读取" }
                return long
            }
            val id = number("subject_id", 1..Long.MAX_VALUE)
            check(number("subject_type", 1L..6L) == kind.id.toLong()) { "收藏分类不一致，请重新读取" }
            val type = number("type", 1L..5L).toInt()
            val rate = number("rate", 0L..10L).toInt()
            val progress = number("ep_status", 0L..10000L).toInt()
            number("vol_status", 0L..10000L)
            val tags = json.optJSONArray("tags")
            check(json.opt("private") is Boolean && json.opt("comment") is String && tags != null && (0 until tags.length()).all { tags.opt(it) is String }) {
                "私密收藏字段读取不完整，请重新读取"
            }
            val fingerprint = json.getString("fingerprint")
            check(fingerprint.matches(Regex("[a-f0-9]{64}"))) { "收藏对比凭据无效，请重新读取" }
            val snapshot = JSONObject().apply {
                for (key in listOf("subject_id", "subject_type", "type", "rate", "ep_status", "vol_status", "private", "comment", "tags", "fingerprint")) put(key, json.get(key))
            }
            return BangumiUploadRemote(id, kind, type, rate, progress, fingerprint, snapshot.toString(), json.optString("title").ifBlank { id.toString() })
        }
    }
}

internal data class BangumiUploadLocal(val anime: AnimeEntity? = null, val game: GameEntity? = null, val animeTags: List<String> = emptyList()) {
    val id get() = anime?.id ?: game!!.id
    val kind get() = if (game != null) BangumiUploadKind.GAME else if (anime!!.subjectType.isBookSubjectType()) BangumiUploadKind.BOOK else BangumiUploadKind.ANIME
    val title get() = anime?.title ?: game!!.title
    val subjectId = anime?.sourceReferences()?.filter { it.source?.trim()?.lowercase(java.util.Locale.ROOT) == "bangumi" }
        ?.mapNotNull { it.externalId?.toLongOrNull()?.takeIf { id -> id > 0 } }?.distinct()?.singleOrNull() ?: game?.bangumiId
    val status get() = anime?.status ?: game!!.status
    val tags = game?.let { com.animeow.app.data.games.gameTagNames(it.tags).sorted() } ?: animeTags
    val comment get() = (anime?.review ?: game?.review).orEmpty()
    val bookVolumes get() = anime?.takeIf { kind == BangumiUploadKind.BOOK }?.bookReadVolumes?.takeIf { it in 0..10000 }
    val bookChapters get() = anime?.takeIf { kind == BangumiUploadKind.BOOK }?.bookReadChapters?.takeIf { it in 0..10000 }
    val type get() = anime?.let { if (it.status.trim() == "未看") 1 else bangumiCollectionType(it.status) }
        ?: game?.takeIf { it.status in gameStatuses }?.let { gameCollectionType(it.status) }
    // A fractional/letter score has no lossless BGM representation; never round it silently.
    val rate: Int? get() = game?.rating?.takeIf { it in 0..10 } ?: anime?.let {
        if (!it.ratingGrade.isNullOrBlank()) null
        else if (it.rating == null || it.rating == 0) 0
        else it.rating.takeIf { score -> score in 10..100 && score % 10 == 0 }?.div(10)
    }
    val progress get() = anime?.takeIf { kind == BangumiUploadKind.ANIME }?.watchedEpisodes?.takeIf { it in 0..2000 }
    val ratingText get() = game?.let { if (it.rating == 0) "未评分" else "${it.rating} 分" }
        ?: anime!!.let { it.ratingGrade?.takeIf(String::isNotBlank) ?: it.rating?.takeIf { score -> score > 0 }?.let { score -> "${score / 10.0} 分" } ?: "未评分" }
}

internal data class BangumiUploadChoice(val status: Boolean = false, val rating: Boolean = false, val progress: Boolean = false,
    val downloadStatus: Boolean = false, val downloadRating: Boolean = false, val downloadProgress: Boolean = false,
    val tags: Boolean = false, val comment: Boolean = false, val downloadTags: Boolean = false, val downloadComment: Boolean = false,
    val volumes: Boolean = false, val chapters: Boolean = false, val downloadVolumes: Boolean = false, val downloadChapters: Boolean = false,
) {
    val uploadSelected get() = status || rating || progress || tags || comment || volumes || chapters
    val downloadSelected get() = downloadStatus || downloadRating || downloadProgress || downloadTags || downloadComment || downloadVolumes || downloadChapters
    val selected get() = uploadSelected || downloadSelected
    val uploadFieldCount get() = listOf(status, rating, progress, tags, comment, volumes, chapters).count { it }
    val downloadFieldCount get() = listOf(downloadStatus, downloadRating, downloadProgress, downloadTags, downloadComment, downloadVolumes, downloadChapters).count { it }
    val selectedFieldCount get() = uploadFieldCount + downloadFieldCount
}

internal data class BangumiUploadRow(val local: BangumiUploadLocal, val remote: BangumiUploadRemote?, val blocked: String? = null) {
    val isUnlinked get() = local.subjectId == null
    val isNewRemote get() = blocked == null && remote == null
    val downloadable get() = if (blocked != null || remote == null) BangumiUploadChoice() else BangumiUploadChoice(
        status = local.type != remote.type,
        rating = local.rate != remote.rate,
        progress = local.kind == BangumiUploadKind.ANIME && remote.progress in 0..2000 && local.progress != remote.progress,
        tags = bangumiTagSet(local.tags) != bangumiTagSet(remote.tags) && validBangumiUploadTags(remote.tags) &&
            bangumiTagSet(remote.tags).distinct().size == remote.tags.size &&
            (local.game == null || remote.tags.none { it.contains(',') || it.contains('，') } && remote.tags.joinToString("，").length <= 1024),
        comment = local.comment != remote.comment && remote.comment.length <= 5000,
        volumes = local.kind == BangumiUploadKind.BOOK && local.bookVolumes != remote.volumes,
        chapters = local.kind == BangumiUploadKind.BOOK && local.bookChapters != remote.progress,
    )
    fun downloadFields(choice: BangumiUploadChoice): JSONObject = JSONObject().apply {
        check(!(choice.status && choice.downloadStatus || choice.rating && choice.downloadRating || choice.progress && choice.downloadProgress || choice.tags && choice.downloadTags || choice.comment && choice.downloadComment || choice.volumes && choice.downloadVolumes || choice.chapters && choice.downloadChapters)) {
            "同一字段只能选择一个方向"
        }
        val allowed = downloadable
        if (choice.downloadStatus) { check(allowed.status); put("type", remote!!.type) }
        if (choice.downloadRating) { check(allowed.rating); put("rate", remote!!.rate) }
        if (choice.downloadProgress) { check(allowed.progress); put("progress", remote!!.progress) }
        if (choice.downloadTags) { check(allowed.tags); put("tags", org.json.JSONArray(remote!!.tags)) }
        if (choice.downloadComment) { check(allowed.comment); put("comment", remote!!.comment) }
        if (choice.downloadVolumes) { check(allowed.volumes); put("volumes", remote!!.volumes) }
        if (choice.downloadChapters) { check(allowed.chapters); put("chapters", remote!!.progress) }
    }
    val available get() = if (blocked != null) BangumiUploadChoice() else BangumiUploadChoice(
        status = local.type != null && local.type != remote?.type,
        rating = local.rate != null && local.rate != (remote?.rate ?: 0),
        progress = local.progress != null && local.progress != (remote?.progress ?: 0),
        tags = validBangumiUploadTags(local.tags) && bangumiTagSet(local.tags) != bangumiTagSet(remote?.tags.orEmpty()),
        comment = local.comment.length <= 5000 && local.comment != remote?.comment.orEmpty(),
        volumes = local.bookVolumes != null && local.bookVolumes != (remote?.volumes ?: 0),
        chapters = local.bookChapters != null && local.bookChapters != (remote?.progress ?: 0),
    )
    fun fields(choice: BangumiUploadChoice): JSONObject = JSONObject().apply {
        check(blocked == null) { blocked.orEmpty() }
        if (choice.status && available.status) put("type", local.type)
        if (choice.rating && available.rating) put("rate", local.rate)
        if (choice.progress && available.progress) put("progress", local.progress)
        if (choice.tags && available.tags) put("tags", org.json.JSONArray(local.tags))
        if (choice.comment && available.comment) put("comment", local.comment)
        if (choice.volumes && available.volumes) put("volumes", local.bookVolumes)
        if (choice.chapters && available.chapters) put("chapters", local.bookChapters)
        check(length() > 0 || choice.downloadSelected) { "请选择实际不同的可上传字段" }
        check(remote != null || has("type")) { "新建收藏需要选择可识别的状态" }
    }
    val hasCoreUploadDiff get() = available.status || available.rating || available.progress || available.volumes || available.chapters
    val hasCoreDownloadDiff get() = downloadable.status || downloadable.rating || downloadable.progress || downloadable.volumes || downloadable.chapters
    val hasCoreDiff get() = hasCoreUploadDiff || hasCoreDownloadDiff
    val hasAnyDiff get() = available.selected || downloadable.selected
    val hasExtraOnlyDiff get() = !hasCoreDiff && (available.tags || available.comment || downloadable.tags || downloadable.comment)
    fun safeUploadChoice(): BangumiUploadChoice {
        val allowed = available
        val raw = allowed.copy(
            rating = allowed.rating && (local.rate ?: 0) > 0,
            progress = allowed.progress && (local.progress ?: 0) > (remote?.progress ?: 0),
            tags = false, comment = false, volumes = false, chapters = false,
        )
        return sanitize(raw)
    }
    fun safeDownloadChoice(): BangumiUploadChoice {
        val down = downloadable
        return sanitize(BangumiUploadChoice(
            downloadStatus = down.status,
            downloadRating = down.rating && (remote?.rate ?: 0) > 0,
            downloadProgress = down.progress && (remote?.progress ?: 0) > (local.progress ?: 0),
        ))
    }
    fun sanitize(choice: BangumiUploadChoice, previous: BangumiUploadChoice = BangumiUploadChoice()): BangumiUploadChoice {
        val allowed = available
        val down = downloadable
        var safe = BangumiUploadChoice(
            status = choice.status && allowed.status && !choice.downloadStatus,
            rating = choice.rating && allowed.rating && !choice.downloadRating,
            progress = choice.progress && allowed.progress && !choice.downloadProgress,
            downloadStatus = choice.downloadStatus && down.status,
            downloadRating = choice.downloadRating && down.rating,
            downloadProgress = choice.downloadProgress && down.progress,
            tags = choice.tags && allowed.tags && !choice.downloadTags,
            comment = choice.comment && allowed.comment && !choice.downloadComment,
            downloadTags = choice.downloadTags && down.tags,
            downloadComment = choice.downloadComment && down.comment,
            volumes = choice.volumes && allowed.volumes && !choice.downloadVolumes,
            chapters = choice.chapters && allowed.chapters && !choice.downloadChapters,
            downloadVolumes = choice.downloadVolumes && down.volumes,
            downloadChapters = choice.downloadChapters && down.chapters,
        )
        if (remote == null && safe.uploadSelected && !safe.status) {
            safe = if (previous.status || !allowed.status) BangumiUploadChoice()
            else safe.copy(status = true)
        }
        return safe
    }
}

internal data class BangumiUploadPreview(val account: BangumiUploadAccount, val kind: BangumiUploadKind,
    val rows: List<BangumiUploadRow>, val remoteCount: Int, val createdAt: Long,
    val remoteOnly: List<BangumiUploadRemote> = emptyList())

internal data class BangumiUploadAssociation(val preview: BangumiUploadPreview, val local: BangumiUploadLocal,
    val subjectId: Long, val title: String)

internal fun validBangumiUploadTags(tags: List<String>): Boolean = tags.size <= 100 && tags.distinct().size == tags.size &&
    tags.all { it.isNotEmpty() && it.length <= 200 && it.none(Char::isWhitespace) }

private fun bangumiTagSet(tags: List<String>) = tags.map { it.lowercase(java.util.Locale.ROOT) }.sorted()

internal fun bangumiUploadStatus(type: Int?, kind: BangumiUploadKind): String =
    if (type == null) "未收藏" else if (kind == BangumiUploadKind.GAME) when (type) {
        1 -> "想玩"; 2 -> "已玩"; 3 -> "在玩"; 4 -> "搁置"; else -> "弃坑"
    } else when (type) { 1 -> "想看"; 2 -> "看过"; 3 -> "在看"; 4 -> "搁置"; else -> "抛弃" }

internal fun bangumiUploadFailureMessage(error: Throwable, fallback: String): String {
    if (error !is IllegalStateException && error !is IllegalArgumentException) return fallback
    return safeCloudMessage(error.message)?.takeIf { text ->
        text.any { it in '\u4e00'..'\u9fff' } && listOf("/", "\\", "Bearer", "token", "\n").none { text.contains(it, true) }
    } ?: fallback
}
