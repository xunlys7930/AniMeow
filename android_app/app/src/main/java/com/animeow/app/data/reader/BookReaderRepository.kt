package com.animeow.app.data.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.animeow.app.data.diagnostics.OperationLogService
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.WatchRecordEntity
import com.animeow.app.data.local.isBookSubjectType
import com.animeow.app.data.local.watchRecordDate
import com.animeow.app.data.reader.ebook.BookFileHasher
import com.animeow.app.data.reader.ebook.EbookBlock
import com.animeow.app.data.reader.ebook.EbookFormat
import com.animeow.app.data.reader.ebook.EpubBookParser
import com.animeow.app.data.reader.ebook.ParsedEbookCatalog
import com.animeow.app.data.reader.ebook.TxtBookParser
import com.animeow.app.data.reader.legado.LegadoBookshelfParser
import com.animeow.app.data.reader.legado.LegadoSyncPreviewItem
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class LegadoSyncApplySummary(
    val addedCount: Int,
    val updatedCount: Int,
    val unitProtectedCount: Int,
)

data class LoadedReaderBook(
    val anime: AnimeEntity,
    val state: BookReadingStateEntity,
    val catalog: ParsedEbookCatalog,
    val fingerprint: String,
)

class BookReaderRepository(
    context: Context,
    private val database: AniMeowDatabase = AniMeowDatabase.getInstance(context),
    private val operationLog: OperationLogService? = OperationLogService(context, database),
) {
    private val appContext = context.applicationContext
    private val libraryDao = database.libraryDao()
    private val bookDao = database.bookReadingDao()

    fun observeState(animeId: Long): Flow<BookReadingStateEntity?> = bookDao.observeState(animeId)

    fun observeRecentStates(limit: Int = 20): Flow<List<BookReadingStateEntity>> = bookDao.observeRecentStates(limit)

    fun observeBookmarks(animeId: Long): Flow<List<BookBookmarkEntity>> = bookDao.observeBookmarks(animeId)

    /**
     * 从首页快速添加导入本地 `.txt` / `.epub` 并自动创建 `subjectType = "book"` 收藏作品。
     */
    suspend fun importLocalEbookAsNewWork(uri: Uri): Long = withContext(Dispatchers.IO) {
        takePersistableReadPermission(uri)
        val displayName = resolveDisplayName(uri)
        val fallbackTitle = stripBookExtension(displayName)
        val extHint = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val (cachedFile, fingerprint) = openInputStreamOrThrow(uri).use { stream ->
            BookFileHasher.copyStreamToCache(stream, appContext.cacheDir, extHint)
        }
        val header = readHeaderBytes(cachedFile)
        val format = EbookFormat.detect(displayName, header)
        val catalog = when (format) {
            EbookFormat.EPUB -> EpubBookParser.parseCatalog(cachedFile, fallbackTitle)
            EbookFormat.TXT -> TxtBookParser.parseCatalog(cachedFile, appContext.cacheDir, fallbackTitle, fingerprint)
        }
        val savedCoverUri = catalog.coverBytes?.takeIf { it.isNotEmpty() }?.let { bytes ->
            saveCoverBytes(bytes, catalog.coverExtension ?: "jpg")
        }
        val nowIso = Instant.now().toString()
        val animeId = database.withTransaction {
            val createdId = libraryDao.insertAnime(
                AnimeEntity(
                    title = catalog.title.trim().ifEmpty { fallbackTitle.ifEmpty { "本地电子书" } },
                    coverUrl = savedCoverUri,
                    status = "想看",
                    studio = catalog.author,
                    synopsis = catalog.synopsis,
                    watchedEpisodes = 0,
                    totalEpisodes = catalog.chapters.size,
                    subjectType = "book",
                    mediaFormat = "小说",
                    createdAt = nowIso,
                ),
            )
            bookDao.upsertState(
                BookReadingStateEntity(
                    animeId = createdId,
                    localFileUri = uri.toString(),
                    localFileName = displayName,
                    fileFormat = catalog.format.name,
                    fileFingerprint = fingerprint,
                    charsetName = catalog.detectedCharset,
                    chapterIndex = 0,
                    chapterTitle = catalog.chapters.firstOrNull()?.title,
                    totalChapters = catalog.chapters.size,
                    blockIndex = 0,
                    charOffset = 0,
                    progressPermille = 0,
                    syncProgressToMain = true,
                    updatedAt = nowIso,
                ),
            )
            createdId
        }
        operationLog?.record("导入本地电子书建档", "阅读器", mapOf("animeId" to animeId, "format" to catalog.format.name))
        animeId
    }

    /**
     * 为已有书籍作品绑定或重绑本地电子书。若新文件指纹与历史指纹一致，自动保留原有精确章节与段内位置。
     */
    suspend fun bindLocalEbook(animeId: Long, uri: Uri): BookReadingStateEntity = withContext(Dispatchers.IO) {
        takePersistableReadPermission(uri)
        val anime = libraryDao.getAnime(animeId)?.takeIf { it.deletedAt == null }
            ?: throw IllegalStateException("该作品不存在或已移入回收站")
        require(anime.subjectType.isBookSubjectType()) { "仅书籍类型作品支持绑定电子书" }

        val displayName = resolveDisplayName(uri)
        val fallbackTitle = anime.title.ifBlank { stripBookExtension(displayName) }
        val extHint = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val (cachedFile, fingerprint) = openInputStreamOrThrow(uri).use { stream ->
            BookFileHasher.copyStreamToCache(stream, appContext.cacheDir, extHint)
        }
        val format = EbookFormat.detect(displayName, readHeaderBytes(cachedFile))
        val existing = bookDao.getState(animeId)
        val sameBookFile = existing?.fileFingerprint != null && existing.fileFingerprint == fingerprint
        val charsetOverride = if (sameBookFile) existing?.charsetName else null
        val catalog = when (format) {
            EbookFormat.EPUB -> EpubBookParser.parseCatalog(cachedFile, fallbackTitle)
            EbookFormat.TXT -> TxtBookParser.parseCatalog(cachedFile, appContext.cacheDir, fallbackTitle, fingerprint, charsetOverride)
        }

        val savedCoverUri = if (anime.coverUrl.isNullOrBlank() && catalog.coverBytes?.isNotEmpty() == true) {
            saveCoverBytes(catalog.coverBytes, catalog.coverExtension ?: "jpg")
        } else {
            null
        }
        val nowIso = Instant.now().toString()
        val restoredChapter = if (sameBookFile) {
            existing!!.chapterIndex.coerceIn(0, (catalog.chapters.size - 1).coerceAtLeast(0))
        } else {
            0
        }
        val protectedMismatch = anime.totalEpisodes in 1..50 && catalog.chapters.size > anime.totalEpisodes * 3
        val nextState = (existing ?: BookReadingStateEntity(animeId = animeId, updatedAt = nowIso)).copy(
            localFileUri = uri.toString(),
            localFileName = displayName,
            fileFormat = catalog.format.name,
            fileFingerprint = fingerprint,
            charsetName = catalog.detectedCharset,
            chapterIndex = restoredChapter,
            chapterTitle = catalog.chapters.getOrNull(restoredChapter)?.title,
            totalChapters = catalog.chapters.size,
            blockIndex = if (sameBookFile) existing!!.blockIndex else 0,
            charOffset = if (sameBookFile) existing!!.charOffset else 0,
            progressPermille = if (sameBookFile) existing!!.progressPermille else 0,
            syncProgressToMain = existing?.syncProgressToMain ?: !protectedMismatch,
            updatedAt = nowIso,
        )

        database.withTransaction {
            bookDao.upsertState(nextState)
            val updatedAnime = anime.copy(
                coverUrl = anime.coverUrl?.takeIf(String::isNotBlank) ?: savedCoverUri,
                studio = anime.studio?.takeIf(String::isNotBlank) ?: catalog.author,
                synopsis = anime.synopsis?.takeIf(String::isNotBlank) ?: catalog.synopsis,
                totalEpisodes = if (anime.totalEpisodes == 0 && nextState.syncProgressToMain) catalog.chapters.size else anime.totalEpisodes,
            )
            if (updatedAnime != anime) {
                libraryDao.updateAnime(updatedAnime)
            }
        }
        operationLog?.record("绑定本地电子书", "阅读器", mapOf("animeId" to animeId, "format" to catalog.format.name))
        nextState
    }

    suspend fun loadReaderBook(animeId: Long, charsetOverride: String? = null): LoadedReaderBook = withContext(Dispatchers.IO) {
        val anime = libraryDao.getAnime(animeId)?.takeIf { it.deletedAt == null }
            ?: throw IllegalStateException("该书籍已不存在或已移入回收站")
        val state = bookDao.getState(animeId)
            ?: throw IllegalStateException("尚未为《${anime.title}》绑定本地电子书，请先选择 TXT 或 EPUB 文件")
        val uriText = state.localFileUri?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("该阅读进度来自云备份或其他设备，请重新选择本机上的电子书文件以继续阅读")

        val extHint = state.fileFormat?.lowercase(Locale.ROOT)
            ?: state.localFileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        val existingCache = state.fileFingerprint?.let { fp ->
            val cleanExt = if (extHint == "epub") "epub" else if (extHint == "txt") "txt" else null
            cleanExt?.let { File(appContext.cacheDir, "ebook_cache/books/$fp.$it").takeIf(File::isFile) }
        }
        val (workingSource, fingerprint) = if (existingCache != null) {
            existingCache to state.fileFingerprint!!
        } else {
            val uri = uriText.toUri()
            openInputStreamOrThrow(uri).use { stream ->
                BookFileHasher.copyStreamToCache(stream, appContext.cacheDir, extHint)
            }
        }
        val format = EbookFormat.detect(state.localFileName, readHeaderBytes(workingSource))
        val effectiveCharset = charsetOverride ?: state.charsetName
        val catalog = when (format) {
            EbookFormat.EPUB -> EpubBookParser.parseCatalog(workingSource, anime.title)
            EbookFormat.TXT -> TxtBookParser.parseCatalog(
                sourceFile = workingSource,
                cacheDir = appContext.cacheDir,
                fallbackTitle = anime.title,
                fingerprint = fingerprint,
                charsetOverride = effectiveCharset,
            )
        }
        val updatedState = if (charsetOverride != null && catalog.detectedCharset != state.charsetName) {
            state.copy(charsetName = catalog.detectedCharset, totalChapters = catalog.chapters.size, updatedAt = Instant.now().toString())
                .also { bookDao.upsertState(it) }
        } else if (state.totalChapters != catalog.chapters.size) {
            state.copy(totalChapters = catalog.chapters.size, updatedAt = Instant.now().toString())
                .also { bookDao.upsertState(it) }
        } else {
            state
        }
        LoadedReaderBook(
            anime = anime,
            state = updatedState,
            catalog = catalog,
            fingerprint = fingerprint,
        )
    }

    suspend fun loadChapterBlocks(loaded: LoadedReaderBook, chapterIndex: Int): List<EbookBlock> = withContext(Dispatchers.IO) {
        val chapter = loaded.catalog.chapters.getOrNull(chapterIndex)
            ?: return@withContext listOf(EbookBlock.Paragraph("未找到对应章节"))
        when (loaded.catalog.format) {
            EbookFormat.TXT -> TxtBookParser.loadChapterBlocks(loaded.catalog.workingFile, chapter)
            EbookFormat.EPUB -> EpubBookParser.loadChapterBlocks(
                epubFile = loaded.catalog.workingFile,
                fingerprint = loaded.fingerprint,
                chapter = chapter,
                cacheDir = appContext.cacheDir,
            )
        }
    }

    /**
     * 保存精确阅读位置，并在满足联动条件时自动更新作品状态（`想看` -> `在看` / `看完`）与阅读日历足迹。
     */
    suspend fun saveReadingPosition(
        animeId: Long,
        chapterIndex: Int,
        chapterTitle: String,
        totalChapters: Int,
        blockIndex: Int,
        charOffset: Int,
        totalBlocksInChapter: Int,
        autoSyncMainProgress: Boolean,
        autoCompleteLastChapter: Boolean,
    ): BookReadingStateEntity? = withContext(Dispatchers.IO) {
        val nowIso = Instant.now().toString()
        val safeTotal = totalChapters.coerceAtLeast(1)
        val safeChapter = chapterIndex.coerceIn(0, safeTotal - 1)
        val withinChapterRatio = if (totalBlocksInChapter > 1) {
            blockIndex.coerceIn(0, totalBlocksInChapter - 1).toFloat() / (totalBlocksInChapter - 1).toFloat()
        } else {
            1f
        }
        val permille = (((safeChapter + withinChapterRatio) / safeTotal.toFloat()) * 1000f)
            .toInt()
            .coerceIn(0, 1000)

        database.withTransaction {
            val current = bookDao.getState(animeId) ?: return@withTransaction null
            val nextState = current.copy(
                chapterIndex = safeChapter,
                chapterTitle = chapterTitle,
                totalChapters = safeTotal,
                blockIndex = blockIndex.coerceAtLeast(0),
                charOffset = charOffset.coerceAtLeast(0),
                progressPermille = permille,
                lastReadAt = nowIso,
                updatedAt = nowIso,
            )
            bookDao.upsertState(nextState)

            val anime = libraryDao.getAnime(animeId)?.takeIf { it.deletedAt == null }
            if (anime != null) {
                val oneBasedChapter = safeChapter + 1
                val reachedLastBlock = safeChapter == safeTotal - 1 &&
                    (totalBlocksInChapter <= 1 || blockIndex >= totalBlocksInChapter - 2)
                val shouldUpdateMain = autoSyncMainProgress && nextState.syncProgressToMain
                val nextWatched = if (shouldUpdateMain) {
                    maxOf(anime.watchedEpisodes, oneBasedChapter)
                } else {
                    anime.watchedEpisodes
                }
                val nextTotal = if (shouldUpdateMain && anime.totalEpisodes == 0) safeTotal else anime.totalEpisodes
                val nextStatus = when {
                    autoCompleteLastChapter && shouldUpdateMain && reachedLastBlock -> "看完"
                    anime.status.trim() in setOf("想看", "未看") && (safeChapter > 0 || blockIndex > 0) -> "在看"
                    else -> anime.status
                }
                val todayStr = LocalDate.now().toString()
                val nextStartDate = anime.watchStartDate?.takeIf(String::isNotBlank)
                    ?: todayStr.takeIf { nextStatus != anime.status || nextWatched > anime.watchedEpisodes }
                val nextFinishDate = if (nextStatus == "看完" && anime.watchFinishDate.isNullOrBlank()) {
                    todayStr
                } else {
                    anime.watchFinishDate
                }
                val nextBookChapters = maxOf(anime.bookReadChapters ?: 0, oneBasedChapter)
                if (
                    nextWatched != anime.watchedEpisodes ||
                    nextTotal != anime.totalEpisodes ||
                    nextStatus != anime.status ||
                    nextBookChapters != (anime.bookReadChapters ?: 0)
                ) {
                    libraryDao.updateAnime(
                        anime.copy(
                            watchedEpisodes = nextWatched,
                            totalEpisodes = nextTotal,
                            bookReadChapters = nextBookChapters,
                            status = nextStatus,
                            statusChangedAt = if (nextStatus != anime.status) System.currentTimeMillis() else anime.statusChangedAt,
                            watchStartDate = nextStartDate,
                            watchFinishDate = nextFinishDate,
                        ),
                    )
                }
                // 当用户读到新章节（或首次产生阅读行为）时写入日历阅读足迹，同一天同一章不重复刷屏
                if (safeChapter > current.chapterIndex || (current.lastReadAt == null && blockIndex > 0)) {
                    insertDeduplicatedReadingRecord(
                        animeId = animeId,
                        episode = if (shouldUpdateMain) nextWatched.coerceAtLeast(1) else oneBasedChapter,
                        completed = nextStatus == "看完" && reachedLastBlock,
                    )
                }
            }
            nextState
        }
    }

    suspend fun addBookmark(
        animeId: Long,
        chapterIndex: Int,
        chapterTitle: String,
        blockIndex: Int,
        charOffset: Int,
        snippet: String,
        note: String? = null,
    ): BookBookmarkEntity = withContext(Dispatchers.IO) {
        val cleanSnippet = snippet.trim().take(160).ifEmpty { chapterTitle }
        val entity = BookBookmarkEntity(
            animeId = animeId,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            blockIndex = blockIndex,
            charOffset = charOffset,
            snippet = cleanSnippet,
            note = note?.trim()?.takeIf(String::isNotEmpty),
            createdAt = Instant.now().toString(),
        )
        val id = bookDao.insertBookmark(entity)
        entity.copy(id = id)
    }

    suspend fun removeBookmark(bookmarkId: Long) = withContext(Dispatchers.IO) {
        bookDao.deleteBookmark(bookmarkId)
    }

    suspend fun buildLegadoPreviewFromUri(uri: Uri, autoMarkCompleted: Boolean = false): List<LegadoSyncPreviewItem> =
        withContext(Dispatchers.IO) {
            val fileName = resolveDisplayName(uri)
            val books = openInputStreamOrThrow(uri).use { LegadoBookshelfParser.parseStream(it, fileName) }
            buildLegadoPreview(books, autoMarkCompleted)
        }

    suspend fun buildLegadoPreview(
        books: List<com.animeow.app.data.reader.legado.LegadoBookItem>,
        autoMarkCompleted: Boolean = false,
    ): List<LegadoSyncPreviewItem> = withContext(Dispatchers.IO) {
        val activeAnimes = libraryDao.getActiveAnimes()
        val states = bookDao.getAllStates().associateBy { it.animeId }
        LegadoBookshelfParser.buildPreview(books, activeAnimes, states, autoMarkCompleted)
    }

    suspend fun applyLegadoSync(items: List<LegadoSyncPreviewItem>): LegadoSyncApplySummary = withContext(Dispatchers.IO) {
        val selected = items.filter { it.selected }
        if (selected.isEmpty()) return@withContext LegadoSyncApplySummary(0, 0, 0)
        val nowIso = Instant.now().toString()
        val todayStr = LocalDate.now().toString()
        var added = 0
        var updated = 0
        var protectedCount = 0

        database.withTransaction {
            for (entry in selected) {
                val book = entry.item
                val existingAnime = entry.matchedAnimeId?.let { libraryDao.getAnime(it) }
                    ?.takeIf { it.deletedAt == null && it.subjectType.isBookSubjectType() }

                val targetAnimeId = if (existingAnime == null) {
                    added++
                    libraryDao.insertAnime(
                        AnimeEntity(
                            title = book.name,
                            coverUrl = book.coverUrl,
                            status = entry.targetStatus,
                            studio = book.author.takeIf(String::isNotBlank),
                            synopsis = book.intro,
                            watchedEpisodes = if (entry.syncProgressToMain) book.readChapters else 0,
                            totalEpisodes = if (entry.syncProgressToMain) book.totalChapterNum else 0,
                            bookReadChapters = book.readChapters.takeIf { it > 0 },
                            subjectType = "book",
                            mediaFormat = "小说",
                            watchStartDate = if (!book.isUnread) todayStr else null,
                            watchFinishDate = if (entry.targetStatus == "看完") todayStr else null,
                            createdAt = nowIso,
                        ),
                    )
                } else {
                    updated++
                    if (!entry.syncProgressToMain) protectedCount++
                    val nextWatched = if (entry.syncProgressToMain) {
                        maxOf(existingAnime.watchedEpisodes, book.readChapters)
                    } else {
                        existingAnime.watchedEpisodes
                    }
                    val nextTotal = if (entry.syncProgressToMain && book.totalChapterNum > existingAnime.totalEpisodes) {
                        book.totalChapterNum
                    } else {
                        existingAnime.totalEpisodes
                    }
                    val nextStatus = entry.targetStatus
                    libraryDao.updateAnime(
                        existingAnime.copy(
                            coverUrl = existingAnime.coverUrl?.takeIf(String::isNotBlank) ?: book.coverUrl,
                            studio = existingAnime.studio?.takeIf(String::isNotBlank) ?: book.author.takeIf(String::isNotBlank),
                            synopsis = existingAnime.synopsis?.takeIf(String::isNotBlank) ?: book.intro,
                            status = nextStatus,
                            statusChangedAt = if (nextStatus != existingAnime.status) System.currentTimeMillis() else existingAnime.statusChangedAt,
                            watchedEpisodes = nextWatched,
                            totalEpisodes = nextTotal,
                            bookReadChapters = maxOf(existingAnime.bookReadChapters ?: 0, book.readChapters).takeIf { it > 0 }
                                ?: existingAnime.bookReadChapters,
                            watchStartDate = existingAnime.watchStartDate?.takeIf(String::isNotBlank)
                                ?: todayStr.takeIf { !book.isUnread },
                            watchFinishDate = existingAnime.watchFinishDate?.takeIf(String::isNotBlank)
                                ?: todayStr.takeIf { nextStatus == "看完" },
                        ),
                    )
                    existingAnime.id
                }

                val existingState = bookDao.getState(targetAnimeId)
                val previousLegadoChapters = ((existingState?.legadoChapterIndex ?: -1) + 1).coerceAtLeast(0)
                val nextState = (existingState ?: BookReadingStateEntity(animeId = targetAnimeId, updatedAt = nowIso)).copy(
                    legadoBookUrl = book.bookUrl,
                    legadoOriginName = book.originName.takeIf(String::isNotBlank),
                    legadoAuthor = book.author.takeIf(String::isNotBlank),
                    legadoChapterIndex = if (book.isUnread) null else book.durChapterIndex,
                    legadoChapterTitle = if (book.isUnread) null else book.durChapterTitle,
                    legadoTotalChapters = book.totalChapterNum.takeIf { it > 0 },
                    legadoLastReadAt = book.lastReadIso,
                    syncProgressToMain = entry.syncProgressToMain,
                    updatedAt = nowIso,
                )
                bookDao.upsertState(nextState)

                if (!book.isUnread && book.readChapters > previousLegadoChapters) {
                    insertDeduplicatedReadingRecord(
                        animeId = targetAnimeId,
                        episode = book.readChapters,
                        completed = entry.targetStatus == "看完",
                    )
                }
            }
        }
        operationLog?.record(
            "同步开源阅读书架",
            "阅读器",
            mapOf("added" to added, "updated" to updated, "unitProtected" to protectedCount),
        )
        LegadoSyncApplySummary(addedCount = added, updatedCount = updated, unitProtectedCount = protectedCount)
    }

    private suspend fun insertDeduplicatedReadingRecord(
        animeId: Long,
        episode: Int,
        completed: Boolean,
    ) {
        val today = LocalDate.now()
        val alreadyRecordedToday = libraryDao.getWatchRecordsForAnime(animeId).any { record ->
            record.episode == episode && watchRecordDate(record.recordDate) == today
        }
        if (!alreadyRecordedToday) {
            libraryDao.insertWatchRecord(
                WatchRecordEntity(
                    animeId = animeId,
                    episode = episode.coerceAtLeast(1),
                    status = if (completed) "completed" else "watched",
                    recordDate = OffsetDateTime.now().toString(),
                ),
            )
        }
    }

    private fun saveCoverBytes(bytes: ByteArray, ext: String): String {
        val coversDir = File(appContext.filesDir, "covers").apply { mkdirs() }
        val cleanExt = ext.lowercase(Locale.ROOT).takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
        val target = File(coversDir, "ebook_${UUID.randomUUID()}.$cleanExt")
        FileOutputStream(target).use { it.write(bytes) }
        return target.toUri().toString()
    }

    private fun takePersistableReadPermission(uri: Uri) {
        if (uri.scheme == "content") {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    private fun openInputStreamOrThrow(uri: Uri) =
        if (uri.scheme == "file") {
            uri.path?.let(::File)?.takeIf(File::isFile)?.inputStream()
        } else {
            appContext.contentResolver.openInputStream(uri)
        } ?: throw IllegalStateException("无法读取所选电子书文件，文件可能已被移动或系统收回了访问权限，请重新选择文件")

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "file") {
            return uri.lastPathSegment.orEmpty().ifBlank { "ebook.txt" }
        }
        runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)?.trim()
                        if (!name.isNullOrEmpty()) return name
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "ebook.txt"
    }

    private fun stripBookExtension(fileName: String): String =
        fileName.replace(Regex("\\.(epub|txt)$", RegexOption.IGNORE_CASE), "").trim()

    private fun readHeaderBytes(file: File): ByteArray {
        val len = minOf(file.length(), 8L).toInt()
        val header = ByteArray(len)
        file.inputStream().use { input ->
            var off = 0
            while (off < len) {
                val r = input.read(header, off, len - off)
                if (r < 0) break
                off += r
            }
        }
        return header
    }
}
