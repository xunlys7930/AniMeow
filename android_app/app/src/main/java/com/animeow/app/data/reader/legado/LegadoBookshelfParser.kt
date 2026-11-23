package com.animeow.app.data.reader.legado

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.allTitles
import com.animeow.app.data.local.isBookSubjectType
import com.animeow.app.data.reader.BookReadingStateEntity
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

data class LegadoBookItem(
    val bookUrl: String,
    val name: String,
    val author: String,
    val origin: String,
    val originName: String,
    val coverUrl: String?,
    val intro: String?,
    val durChapterIndex: Int,
    val durChapterTitle: String?,
    val durChapterPos: Int,
    val totalChapterNum: Int,
    val durChapterTime: Long,
    val latestChapterTitle: String?,
) {
    val isUnread: Boolean
        get() = durChapterIndex <= 0 && durChapterPos <= 0 && durChapterTime <= 0L

    /** 1-based 已读章数；未读新书为 0 */
    val readChapters: Int
        get() = if (isUnread) 0 else (durChapterIndex + 1).coerceAtLeast(1)

    val lastReadIso: String?
        get() = durChapterTime.takeIf { it > 0L }?.let { millis ->
            runCatching { Instant.ofEpochMilli(millis).toString() }.getOrNull()
        }
}

enum class LegadoMatchKind {
    LINKED_BOOK_URL,
    TITLE_MATCH,
    CREATE_NEW,
}

data class LegadoSyncPreviewItem(
    val item: LegadoBookItem,
    val selected: Boolean = true,
    val matchKind: LegadoMatchKind,
    val matchedAnimeId: Long?,
    val matchedAnimeTitle: String?,
    val candidateBookIds: List<Pair<Long, String>> = emptyList(),
    val targetStatus: String,
    val unitMismatchProtected: Boolean,
    val syncProgressToMain: Boolean,
    val localWatchedSummary: String? = null,
)

object LegadoBookshelfParser {
    private const val MAX_JSON_BYTES = 32 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 512
    private const val MAX_BOOKS = 5_000

    /**
     * 自动识别《开源阅读》导出的 `bookshelf.json`、Web 服务响应 JSON 或整包备份 `backup*.zip`。
     */
    fun parseStream(input: InputStream, fileNameHint: String? = null): List<LegadoBookItem> {
        val buffered = BufferedInputStream(input)
        buffered.mark(8)
        val header = ByteArray(4)
        val read = buffered.read(header)
        buffered.reset()
        val isZipMagic = read >= 4 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            (header[2] == 0x03.toByte() || header[2] == 0x05.toByte())
        val isZipHint = fileNameHint?.lowercase(Locale.ROOT)?.endsWith(".zip") == true
        val jsonText = if (isZipMagic || isZipHint) {
            extractBookshelfJsonFromZip(buffered)
        } else {
            readLimitedUtf8(buffered, MAX_JSON_BYTES)
        }
        return parseJsonText(jsonText)
    }

    fun parseJsonText(rawText: String): List<LegadoBookItem> {
        val trimmed = rawText.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        require(trimmed.isNotEmpty()) { "所选文件内容为空，请选择有效的《开源阅读》备份或 bookshelf.json" }
        val array = when {
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrElse {
                throw IllegalArgumentException("无法解析《开源阅读》书架 JSON，文件可能已损坏")
            }
            trimmed.startsWith("{") -> {
                val root = runCatching { JSONObject(trimmed) }.getOrElse {
                    throw IllegalArgumentException("无法解析《开源阅读》响应数据")
                }
                if (root.has("isSuccess") && !root.optBoolean("isSuccess", true)) {
                    val msg = root.optString("errorMsg").trim().take(80)
                    throw IllegalArgumentException(msg.ifBlank { "《开源阅读》返回了未成功状态，请确认书架可访问" })
                }
                root.optJSONArray("data")
                    ?: root.optJSONArray("bookshelf")
                    ?: throw IllegalArgumentException("该 JSON 文件中未包含《开源阅读》书架列表")
            }
            else -> throw IllegalArgumentException("无法识别的文件格式，请选择 backup*.zip 或 bookshelf.json")
        }

        val seenUrls = HashSet<String>()
        val result = ArrayList<LegadoBookItem>(minOf(array.length(), MAX_BOOKS))
        for (i in 0 until minOf(array.length(), MAX_BOOKS)) {
            val obj = array.optJSONObject(i) ?: continue
            val type = obj.optInt("type", 0)
            // Legado type == 1 或位掩码 32 代表有声书/音频流，不混入小说阅读列表
            if (type == 1 || (type > 0 && (type and 32) != 0)) continue
            val name = obj.optString("name").trim()
            if (name.isEmpty()) continue
            val author = cleanAuthor(obj.optString("author"))
            val bookUrl = obj.optString("bookUrl").trim().ifEmpty { "legado://$name/$author" }
            if (!seenUrls.add(bookUrl)) continue
            val origin = obj.optString("origin").trim()
            val originName = obj.optString("originName").trim().ifEmpty {
                if (origin == "loc_book") "本地导入" else origin
            }
            val coverUrl = listOf(obj.optString("customCoverUrl"), obj.optString("coverUrl"))
                .map(String::trim)
                .firstOrNull { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
            val intro = listOf(obj.optString("customIntro"), obj.optString("intro"))
                .map(::cleanIntroText)
                .firstOrNull(String::isNotEmpty)
            val durChapterIndex = obj.optInt("durChapterIndex", 0).coerceAtLeast(0)
            val durChapterPos = obj.optInt("durChapterPos", 0).coerceAtLeast(0)
            val totalChapterNum = obj.optInt("totalChapterNum", 0).coerceAtLeast(0)
            val durChapterTime = obj.optLong("durChapterTime", 0L).coerceAtLeast(0L)
            val durChapterTitle = obj.optString("durChapterTitle").trim().takeIf(String::isNotEmpty)
            val latestChapterTitle = obj.optString("latestChapterTitle").trim().takeIf(String::isNotEmpty)
            result += LegadoBookItem(
                bookUrl = bookUrl,
                name = name,
                author = author,
                origin = origin,
                originName = originName,
                coverUrl = coverUrl,
                intro = intro,
                durChapterIndex = durChapterIndex,
                durChapterTitle = durChapterTitle,
                durChapterPos = durChapterPos,
                totalChapterNum = totalChapterNum,
                durChapterTime = durChapterTime,
                latestChapterTitle = latestChapterTitle,
            )
        }
        return result
    }

    private fun extractBookshelfJsonFromZip(input: InputStream): String {
        var entryCount = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ZIP_ENTRIES) { "压缩包内文件过多，不是标准的《开源阅读》备份包" }
                val normalizedName = entry.name.replace('\\', '/')
                require(!normalizedName.startsWith("/") && !normalizedName.split('/').contains("..")) {
                    "压缩包包含不安全的路径条目，已拒绝读取"
                }
                val baseName = normalizedName.substringAfterLast('/')
                if (!entry.isDirectory && baseName.equals("bookshelf.json", ignoreCase = true)) {
                    return readLimitedUtf8(zip, MAX_JSON_BYTES)
                }
                zip.closeEntry()
            }
        }
        throw IllegalArgumentException("未在压缩包中找到 bookshelf.json，请确认这是《开源阅读》导出的备份包")
    }

    private fun readLimitedUtf8(input: InputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "书架数据体积超过安全上限（32 MB），已停止读取" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    fun buildPreview(
        items: List<LegadoBookItem>,
        activeAnimes: List<AnimeEntity>,
        existingStates: Map<Long, BookReadingStateEntity>,
        autoMarkCompleted: Boolean = false,
    ): List<LegadoSyncPreviewItem> {
        // 严格只匹配 book 类型条目，绝不触碰动画条目
        val activeBooks = activeAnimes.filter { it.deletedAt == null && it.subjectType.isBookSubjectType() }
        val byLegadoUrl = activeBooks.mapNotNull { book ->
            val url = existingStates[book.id]?.legadoBookUrl?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            url to book
        }.toMap()
        val claimedBookIds = HashSet<Long>()

        return items.map { item ->
            val targetStatus = when {
                item.isUnread -> "想看"
                autoMarkCompleted && item.totalChapterNum > 0 && item.readChapters >= item.totalChapterNum -> "看完"
                else -> "在看"
            }
            val directMatch = byLegadoUrl[item.bookUrl]
            if (directMatch != null) {
                claimedBookIds += directMatch.id
                val existingState = existingStates[directMatch.id]
                val protectedMismatch = isUnitMismatchProtected(directMatch, item)
                val syncMain = existingState?.syncProgressToMain ?: !protectedMismatch
                return@map LegadoSyncPreviewItem(
                    item = item,
                    selected = true,
                    matchKind = LegadoMatchKind.LINKED_BOOK_URL,
                    matchedAnimeId = directMatch.id,
                    matchedAnimeTitle = directMatch.title,
                    targetStatus = resolveMergedStatus(directMatch.status, targetStatus),
                    unitMismatchProtected = protectedMismatch,
                    syncProgressToMain = syncMain,
                    localWatchedSummary = formatLocalSummary(directMatch),
                )
            }

            val normTitle = normalizeBookTitle(item.name)
            val normAuthor = normalizeAuthorKey(item.author)
            val titleCandidates = activeBooks.filter { book ->
                book.allTitles().any { normalizeBookTitle(it) == normTitle }
            }
            val candidatePairs = titleCandidates.map { it.id to buildCandidateLabel(it, existingStates[it.id]) }

            // 排除已经绑定到其他不同 bookUrl 或已被同批另一本同名异作者书占用的条目，并校验已知作者不冲突
            val compatibleCandidates = titleCandidates.filter { book ->
                if (book.id in claimedBookIds) return@filter false
                val state = existingStates[book.id]
                if (!state?.legadoBookUrl.isNullOrBlank() && state?.legadoBookUrl != item.bookUrl) return@filter false
                val knownLegadoAuthor = normalizeAuthorKey(state?.legadoAuthor.orEmpty())
                if (knownLegadoAuthor.isNotEmpty() && normAuthor.isNotEmpty() && knownLegadoAuthor != normAuthor) {
                    return@filter false
                }
                val localStudioAuthor = normalizeAuthorKey(book.studio.orEmpty())
                // 若本地已有明确个人作者且与 Legado 作者完全不同（且本地无外部卷目录），视为同名不同作者，不自动合并
                if (
                    localStudioAuthor.isNotEmpty() &&
                    normAuthor.isNotEmpty() &&
                    !authorsCompatible(localStudioAuthor, normAuthor) &&
                    book.externalSource.isNullOrBlank()
                ) {
                    return@filter false
                }
                true
            }

            val chosen = when {
                compatibleCandidates.isEmpty() -> null
                compatibleCandidates.size == 1 -> compatibleCandidates.first()
                normAuthor.isNotEmpty() -> compatibleCandidates.firstOrNull { book ->
                    val stateAuthor = normalizeAuthorKey(existingStates[book.id]?.legadoAuthor.orEmpty())
                    val studioAuthor = normalizeAuthorKey(book.studio.orEmpty())
                    authorsCompatible(stateAuthor, normAuthor) || authorsCompatible(studioAuthor, normAuthor)
                }
                else -> null
            }

            if (chosen != null) {
                claimedBookIds += chosen.id
                val protectedMismatch = isUnitMismatchProtected(chosen, item)
                val syncMain = existingStates[chosen.id]?.syncProgressToMain ?: !protectedMismatch
                LegadoSyncPreviewItem(
                    item = item,
                    selected = true,
                    matchKind = LegadoMatchKind.TITLE_MATCH,
                    matchedAnimeId = chosen.id,
                    matchedAnimeTitle = chosen.title,
                    candidateBookIds = candidatePairs,
                    targetStatus = resolveMergedStatus(chosen.status, targetStatus),
                    unitMismatchProtected = protectedMismatch,
                    syncProgressToMain = syncMain,
                    localWatchedSummary = formatLocalSummary(chosen),
                )
            } else {
                LegadoSyncPreviewItem(
                    item = item,
                    selected = true,
                    matchKind = LegadoMatchKind.CREATE_NEW,
                    matchedAnimeId = null,
                    matchedAnimeTitle = null,
                    candidateBookIds = candidatePairs,
                    targetStatus = targetStatus,
                    unitMismatchProtected = false,
                    syncProgressToMain = true,
                )
            }
        }
    }

    /**
     * 卷／章单位冲突保护：
     * 例如用户在追番喵通过 Bangumi 收藏了 12 卷轻小说（watched=8, total=12），
     * 而《开源阅读》里是 360 章网文合集（第 245 章），此时绝不能把主表卷数冲成 245/360。
     */
    fun isUnitMismatchProtected(localBook: AnimeEntity, item: LegadoBookItem): Boolean {
        val localTotal = localBook.totalEpisodes
        val legadoTotal = item.totalChapterNum
        if (localTotal in 1..50 && legadoTotal > localTotal * 3) return true
        if ((localBook.bookReadVolumes ?: 0) > 0 && localTotal in 1..60 && (legadoTotal > 60 || item.readChapters > localTotal)) {
            return true
        }
        if (!localBook.externalSource.isNullOrBlank() && localTotal in 1..40 && item.readChapters > localTotal) {
            return true
        }
        return false
    }

    private fun resolveMergedStatus(localStatus: String, incomingStatus: String): String {
        val cleanLocal = localStatus.trim()
        if (cleanLocal in setOf("看完", "弃坑") && incomingStatus == "想看") return cleanLocal
        if (cleanLocal == "在看" && incomingStatus == "想看") return "在看"
        return incomingStatus
    }

    private fun formatLocalSummary(book: AnimeEntity): String {
        val total = if (book.totalEpisodes > 0) "${book.watchedEpisodes} / ${book.totalEpisodes}" else "${book.watchedEpisodes}"
        return "${book.status} · 进度 $total"
    }

    private fun buildCandidateLabel(book: AnimeEntity, state: BookReadingStateEntity?): String {
        val author = state?.legadoAuthor?.takeIf(String::isNotBlank) ?: book.studio?.takeIf(String::isNotBlank)
        return buildString {
            append(book.title)
            if (author != null) append("（$author）")
            append(" · ${formatLocalSummary(book)}")
        }
    }

    private fun cleanAuthor(raw: String): String =
        raw.trim()
            .removePrefix("作者：")
            .removePrefix("作者:")
            .trim()

    private fun cleanIntroText(raw: String): String =
        raw.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    internal fun normalizeBookTitle(raw: String): String =
        raw.trim()
            .removePrefix("《")
            .removeSuffix("》")
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{Punct}【】「」『』（）()·・:：—\\-]+"), "")

    private fun normalizeAuthorKey(raw: String): String =
        cleanAuthor(raw)
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{Punct}·・]+"), "")

    private fun authorsCompatible(a: String, b: String): Boolean =
        a == b || a.contains(b) || b.contains(a)
}
