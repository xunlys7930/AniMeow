package com.animeow.app.ui.tracker

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.animeow.app.data.reader.BookReaderRepository
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.icons.AppIcon as Icon
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.icons.IconGlyph
import com.animeow.app.ui.navigation.*
import com.animeow.app.ui.reader.BookReaderActivity
import com.animeow.app.util.userSafeMessage
import kotlinx.coroutines.launch

@Composable
internal fun rememberEbookImport(onNavigate: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ebookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { BookReaderRepository(context).importLocalEbookAsNewWork(uri) }
                    .onSuccess { newAnimeId ->
                        onNavigate("anime/$newAnimeId")
                        context.startActivity(BookReaderActivity.intent(context, newAnimeId))
                    }
                    .onFailure { error ->
                        Toast.makeText(context, error.userSafeMessage("导入电子书失败"), Toast.LENGTH_LONG).show()
                    }
            }
        }
    }
    return { ebookPicker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream", "*/*")) }
}

/** The sheet belongs to this tracker entry, including while its saved navigation state is restored. */
@Composable
internal fun QuickAddHost(requests: PageActionRequests, onNavigate: (String) -> Unit) {
    val importEbook = rememberEbookImport(onNavigate)
    val pending by requests.pending.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (pending != PageAction.QUICK_ADD.name || lifecycle != Lifecycle.State.RESUMED) return
    fun open(route: String) {
        if (requests.consume(PageAction.QUICK_ADD)) onNavigate(route)
    }
    QuickAddSheet(
        onDismissRequest = { requests.consume(PageAction.QUICK_ADD) },
        onOnlineSearch = { open(AppDestination.DISCOVERY.pageRoute) },
        onImageSearch = { open(IMAGE_SEARCH_ROUTE) },
        onManualCreate = { open(EDITOR_ROUTE) },
        onManualCreateBook = { open(BOOK_EDITOR_ROUTE) },
        onImportLocalEbook = {
            if (requests.consume(PageAction.QUICK_ADD)) {
                importEbook()
            }
        },
        onLegadoSync = { open(LEGADO_SYNC_ROUTE) },
        onBangumiImport = { open(BANGUMI_IMPORT_ROUTE) },
        onSpreadsheetImport = { open(SPREADSHEET_TRANSFER_ROUTE) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickAddSheet(
    onDismissRequest: () -> Unit,
    onOnlineSearch: () -> Unit,
    onImageSearch: () -> Unit,
    onManualCreate: () -> Unit,
    onManualCreateBook: () -> Unit,
    onBangumiImport: () -> Unit,
    onSpreadsheetImport: () -> Unit,
    onImportLocalEbook: () -> Unit = {},
    onLegadoSync: () -> Unit = {},
) {
    StableModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text(
                "快速添加",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "选择最适合当前资料来源的入口，之后仍可继续完整编辑。",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            QuickAddAction(AppIcons.EditNote, "手动创建", "从空白表单创建动画，之后仍可修改作品类型", onManualCreate)
            QuickAddAction(AppIcons.Book, "创建书籍", "直接以书籍类型创建作品", onManualCreateBook)
            QuickAddAction(AppIcons.FileOpen, "导入本地电子书（TXT / EPUB）", "自动识别章节、封面与编码并打开阅读器", onImportLocalEbook)
            QuickAddAction(AppIcons.Sync, "从《开源阅读》同步", "通过本地备份、WebDAV 或局域网服务同步书架进度", onLegadoSync)
            QuickAddAction(AppIcons.Search, "从发现中在线搜索", "前往发现，从 Bangumi、AniList 或资料库查找", onOnlineSearch)
            QuickAddAction(AppIcons.ImageSearch, "以图搜番", "上传截图识别动画、集数和时间点", onImageSearch)
            QuickAddAction(AppIcons.CloudDownload, "导入 Bangumi 收藏", "按账号预检并合并公开收藏", onBangumiImport)
            QuickAddAction(AppIcons.Table, "导入 Excel / CSV", "自定义列映射并预检批量数据", onSpreadsheetImport)
        }
    }
}

@Composable
private fun QuickAddAction(
    icon: IconGlyph,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier
            .fillMaxWidth()
            .animatedPressClick(
                role = Role.Button,
                onClickLabel = "打开$title",
                onClick = onClick,
            ),
    )
}
