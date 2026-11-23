package com.animeow.app.ui.reader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.data.reader.BookBookmarkEntity
import com.animeow.app.data.reader.BookReaderRepository
import com.animeow.app.data.reader.LoadedReaderBook
import com.animeow.app.data.reader.ReaderPageMode
import com.animeow.app.data.reader.ReaderPreferences
import com.animeow.app.data.reader.ReaderSettings
import com.animeow.app.data.reader.ReaderTheme
import com.animeow.app.data.reader.ebook.EbookBlock
import com.animeow.app.data.reader.ebook.EbookChapterRef
import com.animeow.app.data.reader.ebook.EbookFormat
import com.animeow.app.util.userSafeMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReaderJumpPoint(
    val chapterIndex: Int,
    val blockIndex: Int,
)

data class BookReaderUiState(
    val loading: Boolean = true,
    val chapterLoading: Boolean = false,
    val errorMessage: String? = null,
    val requiresFileRebind: Boolean = false,
    val bookTitle: String = "",
    val format: EbookFormat? = null,
    val detectedCharset: String? = null,
    val chapters: List<EbookChapterRef> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentBlocks: List<EbookBlock> = emptyList(),
    val currentBlockIndex: Int = 0,
    val scrollRequestToken: Int = 0,
    val requestedScrollBlockIndex: Int = 0,
    val bookmarks: List<BookBookmarkEntity> = emptyList(),
    val legadoBookUrl: String? = null,
    val legadoChapterTitle: String? = null,
    val undoJumpPoint: ReaderJumpPoint? = null,
    val toastMessage: String? = null,
) {
    val currentChapterTitle: String
        get() = chapters.getOrNull(currentChapterIndex)?.title ?: "正文"
}

class BookReaderViewModel(
    application: Application,
    val animeId: Long,
) : AndroidViewModel(application) {
    private val repository = BookReaderRepository(application)
    private val preferences = ReaderPreferences(application)

    val settings: StateFlow<ReaderSettings> = preferences.settings

    private val _state = MutableStateFlow(BookReaderUiState())
    val state: StateFlow<BookReaderUiState> = _state.asStateFlow()

    private val _volumePageEvents = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val volumePageEvents: SharedFlow<Int> = _volumePageEvents.asSharedFlow()

    private var loadedBook: LoadedReaderBook? = null
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeBookmarks(animeId).collect { list ->
                _state.update { it.copy(bookmarks = list) }
            }
        }
        loadBook()
    }

    fun loadBook(charsetOverride: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, errorMessage = null, requiresFileRebind = false) }
            runCatching {
                val loaded = repository.loadReaderBook(animeId, charsetOverride)
                loadedBook = loaded
                val targetChapter = loaded.state.chapterIndex.coerceIn(0, (loaded.catalog.chapters.size - 1).coerceAtLeast(0))
                val blocks = repository.loadChapterBlocks(loaded, targetChapter)
                val targetBlock = loaded.state.blockIndex.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
                _state.update { prev ->
                    prev.copy(
                        loading = false,
                        errorMessage = null,
                        requiresFileRebind = false,
                        bookTitle = loaded.anime.title,
                        format = loaded.catalog.format,
                        detectedCharset = loaded.catalog.detectedCharset,
                        chapters = loaded.catalog.chapters,
                        currentChapterIndex = targetChapter,
                        currentBlocks = blocks,
                        currentBlockIndex = targetBlock,
                        requestedScrollBlockIndex = targetBlock,
                        scrollRequestToken = prev.scrollRequestToken + 1,
                        legadoBookUrl = loaded.state.legadoBookUrl,
                        legadoChapterTitle = loaded.state.legadoChapterTitle,
                    )
                }
            }.onFailure { error ->
                val msg = error.userSafeMessage("打开电子书失败，请检查文件是否仍在原位置")
                val needsRebind = msg.contains("重新选择") || msg.contains("尚未为") || msg.contains("无法读取所选电子书")
                _state.update {
                    it.copy(
                        loading = false,
                        errorMessage = msg,
                        requiresFileRebind = needsRebind,
                    )
                }
            }
        }
    }

    fun rebindLocalFile(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, errorMessage = null) }
            runCatching {
                repository.bindLocalEbook(animeId, uri)
            }.onSuccess {
                loadBook()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        errorMessage = error.userSafeMessage("绑定电子书文件失败，请重试"),
                        requiresFileRebind = true,
                    )
                }
            }
        }
    }

    fun openChapter(
        chapterIndex: Int,
        targetBlockIndex: Int = 0,
        recordUndo: Boolean = false,
    ) {
        val loaded = loadedBook ?: return
        val chapters = loaded.catalog.chapters
        if (chapterIndex !in chapters.indices) return
        val previousPoint = if (recordUndo) {
            ReaderJumpPoint(_state.value.currentChapterIndex, _state.value.currentBlockIndex)
        } else {
            _state.value.undoJumpPoint
        }
        viewModelScope.launch {
            _state.update { it.copy(chapterLoading = true) }
            runCatching {
                repository.loadChapterBlocks(loaded, chapterIndex)
            }.onSuccess { blocks ->
                val safeBlock = targetBlockIndex.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
                _state.update { prev ->
                    prev.copy(
                        chapterLoading = false,
                        currentChapterIndex = chapterIndex,
                        currentBlocks = blocks,
                        currentBlockIndex = safeBlock,
                        requestedScrollBlockIndex = safeBlock,
                        scrollRequestToken = prev.scrollRequestToken + 1,
                        undoJumpPoint = previousPoint,
                    )
                }
                persistPositionImmediate(chapterIndex, safeBlock, blocks.size)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        chapterLoading = false,
                        toastMessage = error.userSafeMessage("读取章节失败"),
                    )
                }
            }
        }
    }

    fun jumpToBlockInCurrentChapter(targetBlockIndex: Int) {
        val blocks = _state.value.currentBlocks
        if (blocks.isEmpty()) return
        val safeTarget = targetBlockIndex.coerceIn(0, blocks.lastIndex)
        val undo = ReaderJumpPoint(_state.value.currentChapterIndex, _state.value.currentBlockIndex)
        _state.update { prev ->
            prev.copy(
                currentBlockIndex = safeTarget,
                requestedScrollBlockIndex = safeTarget,
                scrollRequestToken = prev.scrollRequestToken + 1,
                undoJumpPoint = undo,
            )
        }
        schedulePositionSave(_state.value.currentChapterIndex, safeTarget, blocks.size)
    }

    fun undoJump() {
        val point = _state.value.undoJumpPoint ?: return
        _state.update { it.copy(undoJumpPoint = null) }
        if (point.chapterIndex == _state.value.currentChapterIndex) {
            val safeBlock = point.blockIndex.coerceIn(0, (_state.value.currentBlocks.size - 1).coerceAtLeast(0))
            _state.update { prev ->
                prev.copy(
                    currentBlockIndex = safeBlock,
                    requestedScrollBlockIndex = safeBlock,
                    scrollRequestToken = prev.scrollRequestToken + 1,
                )
            }
            schedulePositionSave(point.chapterIndex, safeBlock, _state.value.currentBlocks.size)
        } else {
            openChapter(point.chapterIndex, point.blockIndex, recordUndo = false)
        }
    }

    fun onViewportBlockChanged(blockIndex: Int) {
        val current = _state.value
        if (current.loading || current.chapterLoading || current.currentBlocks.isEmpty()) return
        val safeBlock = blockIndex.coerceIn(0, current.currentBlocks.lastIndex)
        if (safeBlock == current.currentBlockIndex) return
        _state.update { it.copy(currentBlockIndex = safeBlock) }
        schedulePositionSave(current.currentChapterIndex, safeBlock, current.currentBlocks.size)
    }

    fun flushProgressNow() {
        val current = _state.value
        if (current.loading || current.chapters.isEmpty()) return
        saveJob?.cancel()
        viewModelScope.launch {
            persistPositionImmediate(current.currentChapterIndex, current.currentBlockIndex, current.currentBlocks.size)
        }
    }

    fun switchTxtCharset(charset: String) {
        loadBook(charsetOverride = charset)
    }

    fun toggleBookmarkAtCurrentPosition() {
        val current = _state.value
        if (current.chapters.isEmpty() || current.currentBlocks.isEmpty()) return
        val existing = current.bookmarks.firstOrNull {
            it.chapterIndex == current.currentChapterIndex && it.blockIndex == current.currentBlockIndex
        }
        viewModelScope.launch {
            if (existing != null) {
                repository.removeBookmark(existing.id)
                _state.update { it.copy(toastMessage = "已移除当前位置书签") }
            } else {
                val block = current.currentBlocks.getOrNull(current.currentBlockIndex)
                val snippet = when (block) {
                    is EbookBlock.Paragraph -> block.text
                    is EbookBlock.Illustration -> block.altText ?: "[插图]"
                    null -> current.currentChapterTitle
                }
                repository.addBookmark(
                    animeId = animeId,
                    chapterIndex = current.currentChapterIndex,
                    chapterTitle = current.currentChapterTitle,
                    blockIndex = current.currentBlockIndex,
                    charOffset = 0,
                    snippet = snippet,
                )
                _state.update { it.copy(toastMessage = "已添加书签") }
            }
        }
    }

    fun removeBookmark(id: Long) {
        viewModelScope.launch { repository.removeBookmark(id) }
    }

    fun consumeToast() {
        _state.update { it.copy(toastMessage = null) }
    }

    fun dispatchVolumePage(delta: Int) {
        _volumePageEvents.tryEmit(delta)
    }

    fun setTheme(theme: ReaderTheme) = preferences.update { it.copy(theme = theme) }
    fun setPageMode(mode: ReaderPageMode) = preferences.update { it.copy(pageMode = mode) }
    fun setFontSize(sizeSp: Int) = preferences.update { it.copy(fontSizeSp = sizeSp) }
    fun setLineHeight(multiplier: Float) = preferences.update { it.copy(lineHeightMultiplier = multiplier) }
    fun setParagraphSpacing(dp: Int) = preferences.update { it.copy(paragraphSpacingDp = dp) }
    fun setHorizontalMargin(dp: Int) = preferences.update { it.copy(horizontalMarginDp = dp) }
    fun setFirstLineIndent(enabled: Boolean) = preferences.update { it.copy(firstLineIndent = enabled) }
    fun setVolumeKeyPaging(enabled: Boolean) = preferences.update { it.copy(volumeKeyPaging = enabled) }
    fun setKeepScreenOn(enabled: Boolean) = preferences.update { it.copy(keepScreenOn = enabled) }
    fun setAutoSyncProgress(enabled: Boolean) = preferences.update { it.copy(autoSyncChapterProgress = enabled) }
    fun setAutoCompleteLast(enabled: Boolean) = preferences.update { it.copy(autoCompleteOnLastChapter = enabled) }

    private fun schedulePositionSave(chapterIndex: Int, blockIndex: Int, totalBlocks: Int) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(450L)
            persistPositionImmediate(chapterIndex, blockIndex, totalBlocks)
        }
    }

    private suspend fun persistPositionImmediate(chapterIndex: Int, blockIndex: Int, totalBlocks: Int) {
        val chapters = _state.value.chapters
        if (chapters.isEmpty()) return
        val cfg = settings.value
        val title = chapters.getOrNull(chapterIndex)?.title ?: "第 ${chapterIndex + 1} 章"
        runCatching {
            repository.saveReadingPosition(
                animeId = animeId,
                chapterIndex = chapterIndex,
                chapterTitle = title,
                totalChapters = chapters.size,
                blockIndex = blockIndex,
                charOffset = 0,
                totalBlocksInChapter = totalBlocks,
                autoSyncMainProgress = cfg.autoSyncChapterProgress,
                autoCompleteLastChapter = cfg.autoCompleteOnLastChapter,
            )
        }
    }
}
