package com.animeow.app.ui.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.reader.ReaderPageMode
import com.animeow.app.data.reader.ReaderSettings
import com.animeow.app.data.reader.ReaderTheme
import com.animeow.app.data.reader.ebook.EbookBlock
import com.animeow.app.data.reader.ebook.EbookFormat
import com.animeow.app.data.reader.ebook.TxtBookParser
import com.animeow.app.data.reader.legado.LegadoLauncher
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.components.ZoomableImageViewer
import com.animeow.app.ui.icons.AppIcon as Icon
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.preferences.AppearanceViewModel
import com.animeow.app.ui.theme.AniMeowTheme
import java.io.File
import kotlinx.coroutines.launch

class BookReaderActivity : ComponentActivity() {
    private var activeViewModel: BookReaderViewModel? = null
    private var volumePagingEnabled: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val animeId = intent.getLongExtra(EXTRA_ANIME_ID, 0L)
        if (animeId <= 0L) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            val appearance: AppearanceViewModel = viewModel()
            val appearanceSettings by appearance.settings.collectAsStateWithLifecycle()
            AniMeowTheme(appearanceSettings) {
                val readerModel: BookReaderViewModel = viewModel {
                    BookReaderViewModel(application, animeId)
                }
                val readerSettings by readerModel.settings.collectAsStateWithLifecycle()
                DisposableEffect(readerModel) {
                    activeViewModel = readerModel
                    onDispose { activeViewModel = null }
                }
                LaunchedEffect(readerSettings.volumeKeyPaging, readerSettings.keepScreenOn) {
                    volumePagingEnabled = readerSettings.volumeKeyPaging
                    if (readerSettings.keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                BookReaderScreen(
                    model = readerModel,
                    onBack = {
                        readerModel.flushProgressNow()
                        finish()
                    },
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumePagingEnabled) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    activeViewModel?.dispatchVolumePage(1)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    activeViewModel?.dispatchVolumePage(-1)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val EXTRA_ANIME_ID = "animeId"

        fun intent(context: Context, animeId: Long): Intent =
            Intent(context, BookReaderActivity::class.java).putExtra(EXTRA_ANIME_ID, animeId)
    }
}

internal data class ReaderPageSlice(
    val startBlockIndex: Int,
    val blocks: List<Pair<Int, EbookBlock>>,
)

internal fun paginateReaderBlocks(
    blocks: List<EbookBlock>,
    fontSizeSp: Int,
): List<ReaderPageSlice> {
    if (blocks.isEmpty()) return listOf(ReaderPageSlice(0, emptyList()))
    val budgetCharsPerPage = ((22f / fontSizeSp.coerceAtLeast(14).toFloat()) * 420f).toInt().coerceIn(220, 680)
    val pages = ArrayList<ReaderPageSlice>()
    var current = ArrayList<Pair<Int, EbookBlock>>()
    var usedBudget = 0

    fun flush() {
        if (current.isNotEmpty()) {
            pages += ReaderPageSlice(startBlockIndex = current.first().first, blocks = current.toList())
            current = ArrayList()
            usedBudget = 0
        }
    }

    blocks.forEachIndexed { index, block ->
        when (block) {
            is EbookBlock.Illustration -> {
                flush()
                pages += ReaderPageSlice(startBlockIndex = index, blocks = listOf(index to block))
            }
            is EbookBlock.Paragraph -> {
                val cost = block.text.length.coerceAtLeast(36) + if (block.isHeading) 80 else 28
                if (current.isNotEmpty() && usedBudget + cost > budgetCharsPerPage) {
                    flush()
                }
                current += index to block
                usedBudget += cost
            }
        }
    }
    flush()
    return pages.ifEmpty { listOf(ReaderPageSlice(0, emptyList())) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BookReaderScreen(
    model: BookReaderViewModel,
    onBack: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val theme = settings.theme
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var chromeVisible by rememberSaveable { mutableStateOf(false) }
    var activeSheet by rememberSaveable { mutableStateOf<String?>(null) }
    var viewingIllustrationPath by remember { mutableStateOf<Pair<String, String>?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.rebindLocalFile(uri)
    }

    LaunchedEffect(state.toastMessage) {
        val msg = state.toastMessage ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        model.consumeToast()
    }

    DisposableEffect(activity, theme.lightStatusBars) {
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = theme.lightStatusBars
        onDispose {
            if (previous != null) controller.isAppearanceLightStatusBars = previous
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                model.flushProgressNow()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.backgroundColor),
    ) {
        when {
            state.loading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = theme.accentColor)
                    Text("正在解析电子书章节…", color = theme.secondaryTextColor)
                }
            }
            state.errorMessage != null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(AppIcons.Book, contentDescription = null, tint = theme.accentColor, modifier = Modifier.size(44.dp))
                    Text(
                        state.errorMessage.orEmpty(),
                        color = theme.textColor,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onBack) { Text("返回详情") }
                        if (state.requiresFileRebind) {
                            Button(onClick = { filePicker.launch(arrayOf("application/epub+zip", "text/plain", "*/*")) }) {
                                Text("选择电子书文件")
                            }
                        } else {
                            Button(onClick = { model.loadBook() }) { Text("重试") }
                        }
                    }
                }
            }
            settings.pageMode == ReaderPageMode.VERTICAL_SCROLL -> {
                VerticalScrollReaderBody(
                    state = state,
                    settings = settings,
                    model = model,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onOpenIllustration = { path, label -> viewingIllustrationPath = path to label },
                )
            }
            else -> {
                HorizontalPagedReaderBody(
                    state = state,
                    settings = settings,
                    model = model,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onOpenIllustration = { path, label -> viewingIllustrationPath = path to label },
                )
            }
        }

        // 顶部与底部常驻微弱状态栏（阅读沉浸态下保留章节标题与进度千分比）
        if (!state.loading && state.errorMessage == null && !chromeVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = settings.horizontalMarginDp.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = state.currentChapterTitle,
                    color = theme.secondaryTextColor.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val totalChapters = state.chapters.size.coerceAtLeast(1)
                Text(
                    text = "${state.currentChapterIndex + 1} / $totalChapters 章",
                    color = theme.secondaryTextColor.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // 唤出式顶栏与底栏菜单
        AnimatedVisibility(
            visible = chromeVisible && !state.loading && state.errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopChromeBar(
                state = state,
                theme = theme,
                onBack = onBack,
                onToggleBookmark = model::toggleBookmarkAtCurrentPosition,
                onOpenCharsetSheet = { activeSheet = "charset" },
                onOpenInLegado = {
                    LegadoLauncher.openLegado(context, state.legadoBookUrl.orEmpty()).feedback?.let { message ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible && !state.loading && state.errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomChromeBar(
                state = state,
                theme = theme,
                onPrevChapter = {
                    if (state.currentChapterIndex > 0) {
                        model.openChapter(state.currentChapterIndex - 1, targetBlockIndex = 0, recordUndo = true)
                    }
                },
                onNextChapter = {
                    if (state.currentChapterIndex < state.chapters.lastIndex) {
                        model.openChapter(state.currentChapterIndex + 1, targetBlockIndex = 0, recordUndo = true)
                    }
                },
                onSeekBlock = model::jumpToBlockInCurrentChapter,
                onUndoJump = model::undoJump,
                onOpenToc = { activeSheet = "toc" },
                onOpenSettings = { activeSheet = "settings" },
                onPickAnotherFile = {
                    chromeVisible = false
                    filePicker.launch(arrayOf("application/epub+zip", "text/plain", "*/*"))
                },
            )
        }
    }

    if (activeSheet == "toc") {
        ReaderTocAndBookmarksSheet(
            state = state,
            onDismiss = { activeSheet = null },
            onSelectChapter = { idx ->
                activeSheet = null
                chromeVisible = false
                model.openChapter(idx, targetBlockIndex = 0, recordUndo = true)
            },
            onSelectBookmark = { bm ->
                activeSheet = null
                chromeVisible = false
                model.openChapter(bm.chapterIndex, targetBlockIndex = bm.blockIndex, recordUndo = true)
            },
            onDeleteBookmark = model::removeBookmark,
        )
    }

    if (activeSheet == "settings") {
        ReaderAppearanceSheet(
            settings = settings,
            model = model,
            onDismiss = { activeSheet = null },
        )
    }

    if (activeSheet == "charset") {
        ReaderCharsetSheet(
            currentCharset = state.detectedCharset,
            onSelect = { chosen ->
                activeSheet = null
                model.switchTxtCharset(chosen)
            },
            onDismiss = { activeSheet = null },
        )
    }

    viewingIllustrationPath?.let { (filePath, title) ->
        ZoomableImageViewer(
            imageUrl = File(filePath).toURI().toString(),
            title = title,
            onDismiss = { viewingIllustrationPath = null },
        )
    }
}

@Composable
private fun VerticalScrollReaderBody(
    state: BookReaderUiState,
    settings: ReaderSettings,
    model: BookReaderViewModel,
    onToggleChrome: () -> Unit,
    onOpenIllustration: (String, String) -> Unit,
) {
    val theme = settings.theme
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.scrollRequestToken, state.currentChapterIndex) {
        // item 0 是章节标题头部，正文段落从 index + 1 开始
        val targetItem = (state.requestedScrollBlockIndex + 1).coerceAtLeast(0)
        runCatching { listState.scrollToItem(targetItem) }
    }

    val firstVisibleBlock by remember {
        derivedStateOf { (listState.firstVisibleItemIndex - 1).coerceAtLeast(0) }
    }
    LaunchedEffect(firstVisibleBlock) {
        model.onViewportBlockChanged(firstVisibleBlock)
    }

    LaunchedEffect(model) {
        model.volumePageEvents.collect { delta ->
            val viewport = listState.layoutInfo.viewportSize.height.coerceAtLeast(400)
            val step = (viewport * 0.85f).toInt() * delta
            scope.launch {
                listState.animateScrollByCompat(step.toFloat(), listState)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onToggleChrome() })
            },
        contentPadding = PaddingValues(
            start = settings.horizontalMarginDp.dp,
            end = settings.horizontalMarginDp.dp,
            top = 56.dp,
            bottom = 72.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(settings.paragraphSpacingDp.dp),
    ) {
        item(key = "chapter_header_${state.currentChapterIndex}") {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(
                    text = state.currentChapterTitle,
                    color = theme.textColor,
                    fontSize = (settings.fontSizeSp + 4).sp,
                    lineHeight = ((settings.fontSizeSp + 4) * 1.35f).sp,
                    fontWeight = FontWeight.Bold,
                )
                if (state.currentChapterIndex > 0) {
                    TextButton(
                        onClick = { model.openChapter(state.currentChapterIndex - 1, 0, recordUndo = true) },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("← 上一章", color = theme.accentColor)
                    }
                }
            }
        }

        itemsIndexed(
            items = state.currentBlocks,
            key = { index, _ -> "${state.currentChapterIndex}_$index" },
        ) { _, block ->
            ReaderBlockItem(
                block = block,
                settings = settings,
                chapterTitle = state.currentChapterTitle,
                onOpenIllustration = onOpenIllustration,
            )
        }

        item(key = "chapter_footer_${state.currentChapterIndex}") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.currentChapterIndex > 0) {
                    OutlinedButton(
                        onClick = { model.openChapter(state.currentChapterIndex - 1, 0, recordUndo = true) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("上一章")
                    }
                }
                if (state.currentChapterIndex < state.chapters.lastIndex) {
                    Button(
                        onClick = { model.openChapter(state.currentChapterIndex + 1, 0, recordUndo = true) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("下一章")
                    }
                } else {
                    Surface(
                        color = theme.surfaceColor,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "已读至本书最后一章",
                            color = theme.secondaryTextColor,
                            modifier = Modifier.padding(vertical = 12.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.animateScrollByCompat(
    pixels: Float,
    state: androidx.compose.foundation.lazy.LazyListState,
) {
    val nextIdx = if (pixels > 0) {
        (state.firstVisibleItemIndex + 4).coerceAtMost((state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    } else {
        (state.firstVisibleItemIndex - 4).coerceAtLeast(0)
    }
    state.animateScrollToItem(nextIdx)
}

@Composable
private fun HorizontalPagedReaderBody(
    state: BookReaderUiState,
    settings: ReaderSettings,
    model: BookReaderViewModel,
    onToggleChrome: () -> Unit,
    onOpenIllustration: (String, String) -> Unit,
) {
    val pages = remember(state.currentBlocks, settings.fontSizeSp) {
        paginateReaderBlocks(state.currentBlocks, settings.fontSizeSp)
    }
    val initialPage = remember(pages, state.requestedScrollBlockIndex, state.scrollRequestToken) {
        pages.indexOfLast { it.startBlockIndex <= state.requestedScrollBlockIndex }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialPage) { pages.size }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.scrollRequestToken, pages.size) {
        val target = pages.indexOfLast { it.startBlockIndex <= state.requestedScrollBlockIndex }.coerceAtLeast(0)
        if (target in 0 until pages.size) {
            runCatching { pagerState.scrollToPage(target) }
        }
    }

    LaunchedEffect(pagerState.currentPage, pages) {
        pages.getOrNull(pagerState.currentPage)?.let { slice ->
            model.onViewportBlockChanged(slice.startBlockIndex)
        }
    }

    fun stepPage(delta: Int) {
        val next = pagerState.currentPage + delta
        when {
            next in 0 until pages.size -> scope.launch { pagerState.animateScrollToPage(next) }
            next < 0 && state.currentChapterIndex > 0 ->
                model.openChapter(state.currentChapterIndex - 1, targetBlockIndex = Int.MAX_VALUE, recordUndo = false)
            next >= pages.size && state.currentChapterIndex < state.chapters.lastIndex ->
                model.openChapter(state.currentChapterIndex + 1, targetBlockIndex = 0, recordUndo = false)
        }
    }

    LaunchedEffect(model, pages.size, state.currentChapterIndex) {
        model.volumePageEvents.collect { delta -> stepPage(delta) }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(pages.size, state.currentChapterIndex) {
                detectTapGestures { offset ->
                    val width = size.width.coerceAtLeast(1)
                    when {
                        offset.x < width * 0.28f -> stepPage(-1)
                        offset.x > width * 0.72f -> stepPage(1)
                        else -> onToggleChrome()
                    }
                }
            },
    ) { pageIdx ->
        val slice = pages[pageIdx]
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(
                    start = settings.horizontalMarginDp.dp,
                    end = settings.horizontalMarginDp.dp,
                    top = 20.dp,
                    bottom = 32.dp,
                )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(settings.paragraphSpacingDp.dp),
        ) {
            if (pageIdx == 0) {
                Text(
                    text = state.currentChapterTitle,
                    color = settings.theme.textColor,
                    fontSize = (settings.fontSizeSp + 3).sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            slice.blocks.forEach { (_, block) ->
                ReaderBlockItem(
                    block = block,
                    settings = settings,
                    chapterTitle = state.currentChapterTitle,
                    onOpenIllustration = onOpenIllustration,
                )
            }
            Spacer(Modifier.weight(1f, fill = false))
            Text(
                text = "第 ${pageIdx + 1} / ${pages.size} 页",
                color = settings.theme.secondaryTextColor.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun ReaderBlockItem(
    block: EbookBlock,
    settings: ReaderSettings,
    chapterTitle: String,
    onOpenIllustration: (String, String) -> Unit,
) {
    val theme = settings.theme
    when (block) {
        is EbookBlock.Paragraph -> {
            if (block.isHeading && block.text == chapterTitle.trim()) return
            val displayText = if (settings.firstLineIndent && !block.isHeading) {
                "\u3000\u3000" + block.text
            } else {
                block.text
            }
            Text(
                text = displayText,
                color = theme.textColor,
                fontSize = if (block.isHeading) (settings.fontSizeSp + 2).sp else settings.fontSizeSp.sp,
                lineHeight = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
                fontWeight = if (block.isHeading) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        is EbookBlock.Illustration -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(theme.surfaceColor)
                    .clickable {
                        onOpenIllustration(block.cacheFilePath, block.altText ?: chapterTitle)
                    }
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AsyncImage(
                    model = File(block.cacheFilePath),
                    contentDescription = block.altText ?: "章节插图",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 520.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Text(
                    text = block.altText?.let { "$it · 点按放大查看" } ?: "点按插图全屏双指缩放",
                    color = theme.secondaryTextColor,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ReaderTopChromeBar(
    state: BookReaderUiState,
    theme: ReaderTheme,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenCharsetSheet: () -> Unit,
    onOpenInLegado: () -> Unit,
) {
    val hasBookmarkHere = state.bookmarks.any {
        it.chapterIndex == state.currentChapterIndex && it.blockIndex == state.currentBlockIndex
    }
    Surface(
        color = theme.surfaceColor.copy(alpha = 0.97f),
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(AppIcons.Back, contentDescription = "返回", tint = theme.textColor)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    text = state.bookTitle,
                    color = theme.textColor,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.currentChapterTitle,
                    color = theme.secondaryTextColor,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.format == EbookFormat.TXT) {
                TextButton(onClick = onOpenCharsetSheet) {
                    Text(state.detectedCharset ?: "编码", color = theme.accentColor, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!state.legadoBookUrl.isNullOrBlank()) {
                IconButton(onClick = onOpenInLegado) {
                    Icon(AppIcons.OpenExternal, contentDescription = "在《开源阅读》中打开", tint = theme.textColor)
                }
            }
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    if (hasBookmarkHere) AppIcons.BookmarkFilled else AppIcons.Bookmark,
                    contentDescription = if (hasBookmarkHere) "移除书签" else "添加书签",
                    tint = if (hasBookmarkHere) theme.accentColor else theme.textColor,
                )
            }
        }
    }
}

@Composable
private fun ReaderBottomChromeBar(
    state: BookReaderUiState,
    theme: ReaderTheme,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekBlock: (Int) -> Unit,
    onUndoJump: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickAnotherFile: () -> Unit,
) {
    val maxBlock = (state.currentBlocks.size - 1).coerceAtLeast(0)
    var sliderValue by remember(state.currentChapterIndex, state.currentBlockIndex) {
        mutableFloatStateOf(state.currentBlockIndex.toFloat())
    }

    Surface(
        color = theme.surfaceColor.copy(alpha = 0.97f),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.undoJumpPoint != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    AssistChip(
                        onClick = onUndoJump,
                        leadingIcon = { Icon(AppIcons.Undo, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("撤回刚才的位置跳转") },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onPrevChapter, enabled = state.currentChapterIndex > 0) {
                    Text("上一章", color = if (state.currentChapterIndex > 0) theme.textColor else theme.secondaryTextColor.copy(alpha = 0.4f))
                }
                if (maxBlock > 0) {
                    Slider(
                        value = sliderValue.coerceIn(0f, maxBlock.toFloat()),
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = { onSeekBlock(sliderValue.toInt()) },
                        valueRange = 0f..maxBlock.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onNextChapter, enabled = state.currentChapterIndex < state.chapters.lastIndex) {
                    Text("下一章", color = if (state.currentChapterIndex < state.chapters.lastIndex) theme.textColor else theme.secondaryTextColor.copy(alpha = 0.4f))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = onOpenToc) {
                    Icon(AppIcons.List, contentDescription = null, tint = theme.textColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("目录与书签", color = theme.textColor)
                }
                TextButton(onClick = onOpenSettings) {
                    Icon(AppIcons.Palette, contentDescription = null, tint = theme.textColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("排版与配色", color = theme.textColor)
                }
                TextButton(onClick = onPickAnotherFile) {
                    Icon(AppIcons.FileOpen, contentDescription = null, tint = theme.textColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("换文件", color = theme.textColor)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTocAndBookmarksSheet(
    state: BookReaderUiState,
    onDismiss: () -> Unit,
    onSelectChapter: (Int) -> Unit,
    onSelectBookmark: (com.animeow.app.data.reader.BookBookmarkEntity) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tocListState = rememberLazyListState(initialFirstVisibleItemIndex = (state.currentChapterIndex - 2).coerceAtLeast(0))

    StableModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .navigationBarsPadding(),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("目录（${state.chapters.size}）") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("书签（${state.bookmarks.size}）") })
            }
            if (tab == 0) {
                LazyColumn(
                    state = tocListState,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                ) {
                    itemsIndexed(state.chapters, key = { idx, _ -> idx }) { idx, chapter ->
                        val active = idx == state.currentChapterIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectChapter(idx) }
                                .padding(horizontal = 20.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = chapter.title,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (active) {
                                Text("当前", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            } else {
                if (state.bookmarks.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(36.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("还没有添加书签，点按屏幕中央可在右上角添加书签", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        itemsIndexed(state.bookmarks, key = { _, bm -> bm.id }) { _, bm ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectBookmark(bm) }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(bm.chapterTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(bm.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                }
                                IconButton(onClick = { onDeleteBookmark(bm.id) }) {
                                    Icon(AppIcons.Delete, contentDescription = "删除书签")
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReaderAppearanceSheet(
    settings: ReaderSettings,
    model: BookReaderViewModel,
    onDismiss: () -> Unit,
) {
    StableModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("阅读排版与配色", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Text("阅读背景", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReaderTheme.entries.forEach { candidate ->
                    val selected = candidate == settings.theme
                    Surface(
                        onClick = { model.setTheme(candidate) },
                        color = candidate.backgroundColor,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(candidate.accentColor),
                            )
                            Text(candidate.label, color = candidate.textColor, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            Text("翻页方式", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReaderPageMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.pageMode == mode,
                        onClick = { model.setPageMode(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }

            StepperRow(
                label = "字号大小",
                valueText = "${settings.fontSizeSp} sp",
                onMinus = { model.setFontSize(settings.fontSizeSp - 1) },
                onPlus = { model.setFontSize(settings.fontSizeSp + 1) },
            )
            StepperRow(
                label = "行距倍率",
                valueText = String.format("%.2f×", settings.lineHeightMultiplier),
                onMinus = { model.setLineHeight(settings.lineHeightMultiplier - 0.1f) },
                onPlus = { model.setLineHeight(settings.lineHeightMultiplier + 0.1f) },
            )
            StepperRow(
                label = "段间距",
                valueText = "${settings.paragraphSpacingDp} dp",
                onMinus = { model.setParagraphSpacing(settings.paragraphSpacingDp - 2) },
                onPlus = { model.setParagraphSpacing(settings.paragraphSpacingDp + 2) },
            )
            StepperRow(
                label = "左右边距",
                valueText = "${settings.horizontalMarginDp} dp",
                onMinus = { model.setHorizontalMargin(settings.horizontalMarginDp - 4) },
                onPlus = { model.setHorizontalMargin(settings.horizontalMarginDp + 4) },
            )

            SwitchSettingRow("段首自动缩进两格", settings.firstLineIndent, model::setFirstLineIndent)
            SwitchSettingRow("音量键翻页", settings.volumeKeyPaging, model::setVolumeKeyPaging)
            SwitchSettingRow("阅读时屏幕常亮", settings.keepScreenOn, model::setKeepScreenOn)
            SwitchSettingRow("读完章节自动推进收藏进度与日历", settings.autoSyncChapterProgress, model::setAutoSyncProgress)
            SwitchSettingRow("读至末章自动标记为「看完」", settings.autoCompleteOnLastChapter, model::setAutoCompleteLast)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMinus, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("−") }
            Text(valueText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = onPlus, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("+") }
        }
    }
}

@Composable
private fun SwitchSettingRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderCharsetSheet(
    currentCharset: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    StableModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("切换 TXT 文本编码", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "若正文出现乱码，可手动指定原文件编码（中文网文常见为 UTF-8 或 GB18030 / GBK）：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TxtBookParser.SUPPORTED_CHARSETS.forEach { charset ->
                val label = if (charset == "AUTO") "自动识别（推荐）" else charset
                val selected = charset.equals(currentCharset, ignoreCase = true)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(charset) }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    if (selected) Text("当前使用", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
