package com.animeow.app.data.reader

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReaderTheme(
    val storageKey: String,
    val label: String,
    val backgroundColor: Color,
    val surfaceColor: Color,
    val textColor: Color,
    val secondaryTextColor: Color,
    val accentColor: Color,
    val lightStatusBars: Boolean,
) {
    PAPER_WHITE(
        storageKey = "paper_white",
        label = "纸白",
        backgroundColor = Color(0xFFFAF9F6),
        surfaceColor = Color(0xFFF1EFEA),
        textColor = Color(0xFF1F2328),
        secondaryTextColor = Color(0xFF636C76),
        accentColor = Color(0xFF2B6CB0),
        lightStatusBars = true,
    ),
    WARM_PARCHMENT(
        storageKey = "warm_parchment",
        label = "暖羊皮",
        backgroundColor = Color(0xFFF4ECD8),
        surfaceColor = Color(0xFFEAE0C8),
        textColor = Color(0xFF362C20),
        secondaryTextColor = Color(0xFF6E5E4B),
        accentColor = Color(0xFF8C5319),
        lightStatusBars = true,
    ),
    EYE_CARE_GREEN(
        storageKey = "eye_care_green",
        label = "护眼绿",
        backgroundColor = Color(0xFFE3EDCD),
        surfaceColor = Color(0xFFD6E3BC),
        textColor = Color(0xFF233022),
        secondaryTextColor = Color(0xFF536551),
        accentColor = Color(0xFF2F6F3E),
        lightStatusBars = true,
    ),
    NIGHT_DARK(
        storageKey = "night_dark",
        label = "夜间深灰",
        backgroundColor = Color(0xFF181A1F),
        surfaceColor = Color(0xFF22252C),
        textColor = Color(0xFFD7DAE0),
        secondaryTextColor = Color(0xFF8B929E),
        accentColor = Color(0xFF7AA2F7),
        lightStatusBars = false,
    ),
    PURE_BLACK(
        storageKey = "pure_black",
        label = "纯黑 OLED",
        backgroundColor = Color(0xFF000000),
        surfaceColor = Color(0xFF121214),
        textColor = Color(0xFFC8CCD4),
        secondaryTextColor = Color(0xFF7B808A),
        accentColor = Color(0xFF89B4FA),
        lightStatusBars = false,
    ),
    ;

    companion object {
        fun fromStorage(value: String?): ReaderTheme =
            entries.firstOrNull { it.storageKey == value } ?: WARM_PARCHMENT
    }
}

enum class ReaderPageMode(
    val storageKey: String,
    val label: String,
    val subtitle: String,
) {
    VERTICAL_SCROLL("vertical_scroll", "上下平滑滚动", "适合单手连续阅读与浏览轻小说插图"),
    HORIZONTAL_PAGE("horizontal_page", "左右平移翻页", "按屏幕高度分页，点按左右侧或滑动翻页"),
    ;

    companion object {
        fun fromStorage(value: String?): ReaderPageMode =
            entries.firstOrNull { it.storageKey == value } ?: VERTICAL_SCROLL
    }
}

data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.WARM_PARCHMENT,
    val pageMode: ReaderPageMode = ReaderPageMode.VERTICAL_SCROLL,
    val fontSizeSp: Int = 18,
    val lineHeightMultiplier: Float = 1.75f,
    val paragraphSpacingDp: Int = 12,
    val horizontalMarginDp: Int = 20,
    val firstLineIndent: Boolean = true,
    val volumeKeyPaging: Boolean = false,
    val keepScreenOn: Boolean = false,
    val autoSyncChapterProgress: Boolean = true,
    val autoCompleteOnLastChapter: Boolean = false,
) {
    fun normalized(): ReaderSettings = copy(
        fontSizeSp = fontSizeSp.coerceIn(14, 30),
        lineHeightMultiplier = lineHeightMultiplier.coerceIn(1.3f, 2.3f),
        paragraphSpacingDp = paragraphSpacingDp.coerceIn(4, 28),
        horizontalMarginDp = horizontalMarginDp.coerceIn(12, 40),
    )
}

class ReaderPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    fun snapshot(): ReaderSettings = load()

    fun update(transform: (ReaderSettings) -> ReaderSettings) {
        val next = transform(load()).normalized()
        save(next)
    }

    fun save(value: ReaderSettings) {
        val clean = value.normalized()
        prefs.edit()
            .putString(KEY_THEME, clean.theme.storageKey)
            .putString(KEY_PAGE_MODE, clean.pageMode.storageKey)
            .putInt(KEY_FONT_SIZE, clean.fontSizeSp)
            .putFloat(KEY_LINE_HEIGHT, clean.lineHeightMultiplier)
            .putInt(KEY_PARAGRAPH_SPACING, clean.paragraphSpacingDp)
            .putInt(KEY_HORIZONTAL_MARGIN, clean.horizontalMarginDp)
            .putBoolean(KEY_FIRST_LINE_INDENT, clean.firstLineIndent)
            .putBoolean(KEY_VOLUME_KEY_PAGING, clean.volumeKeyPaging)
            .putBoolean(KEY_KEEP_SCREEN_ON, clean.keepScreenOn)
            .putBoolean(KEY_AUTO_SYNC_PROGRESS, clean.autoSyncChapterProgress)
            .putBoolean(KEY_AUTO_COMPLETE_LAST, clean.autoCompleteOnLastChapter)
            .apply()
        _settings.value = clean
    }

    private fun load(): ReaderSettings = ReaderSettings(
        theme = ReaderTheme.fromStorage(prefs.getString(KEY_THEME, null)),
        pageMode = ReaderPageMode.fromStorage(prefs.getString(KEY_PAGE_MODE, null)),
        fontSizeSp = prefs.getInt(KEY_FONT_SIZE, 18),
        lineHeightMultiplier = prefs.getFloat(KEY_LINE_HEIGHT, 1.75f),
        paragraphSpacingDp = prefs.getInt(KEY_PARAGRAPH_SPACING, 12),
        horizontalMarginDp = prefs.getInt(KEY_HORIZONTAL_MARGIN, 20),
        firstLineIndent = prefs.getBoolean(KEY_FIRST_LINE_INDENT, true),
        volumeKeyPaging = prefs.getBoolean(KEY_VOLUME_KEY_PAGING, false),
        keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
        autoSyncChapterProgress = prefs.getBoolean(KEY_AUTO_SYNC_PROGRESS, true),
        autoCompleteOnLastChapter = prefs.getBoolean(KEY_AUTO_COMPLETE_LAST, false),
    ).normalized()

    private companion object {
        const val PREFS_NAME = "book_reader_preferences"
        const val KEY_THEME = "theme"
        const val KEY_PAGE_MODE = "page_mode"
        const val KEY_FONT_SIZE = "font_size_sp"
        const val KEY_LINE_HEIGHT = "line_height_multiplier"
        const val KEY_PARAGRAPH_SPACING = "paragraph_spacing_dp"
        const val KEY_HORIZONTAL_MARGIN = "horizontal_margin_dp"
        const val KEY_FIRST_LINE_INDENT = "first_line_indent"
        const val KEY_VOLUME_KEY_PAGING = "volume_key_paging"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_AUTO_SYNC_PROGRESS = "auto_sync_chapter_progress"
        const val KEY_AUTO_COMPLETE_LAST = "auto_complete_last_chapter"
    }
}
