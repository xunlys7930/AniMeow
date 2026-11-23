package com.animeow.app.ui.maintenance

import android.content.Intent
import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.animeow.app.R
import com.animeow.app.data.remote.SponsorService
import com.animeow.app.data.remote.SupportLinks
import com.animeow.app.ui.icons.AppIcon as Icon
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SponsorPageState(val loading: Boolean = true, val page: String? = null)

@Composable
internal fun rememberSponsorPage(): State<SponsorPageState> = produceState(SponsorPageState()) {
    value = SponsorPageState(loading = false, page = SponsorService().publicPage())
}

/** Compact entry shared by My page and credits; the support page explains the action. */
@Composable
internal fun SupportDevelopmentCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)) {
                Icon(AppIcons.HeartFilled, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("支持 AniMeow", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("爱发电赞助 · 夸克转存支持开发", style = MaterialTheme.typography.bodySmall)
                Text("自愿支持，现有功能免费使用。", style = MaterialTheme.typography.labelSmall)
            }
            Icon(AppIcons.Forward, null, Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportDevelopmentScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val sponsor by rememberSponsorPage()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var savingQr by remember { mutableStateOf(false) }
    var openingQuark by remember { mutableStateOf(false) }
    val saveQr = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) {
            savingQr = true
            scope.launch {
                try {
                    val result = runCatchingCancellable {
                        withContext(Dispatchers.IO) {
                            context.resources.openRawResource(R.raw.afdian_support).use { input ->
                                requireNotNull(context.contentResolver.openOutputStream(uri)) { "Cannot open output" }.use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                    snackbar.showSnackbar(if (result.isSuccess) "爱发电二维码已保存" else "保存失败，请重新选择保存位置")
                } finally {
                    savingQr = false
                }
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("支持 AniMeow") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(AppIcons.Back, "返回") }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(AppIcons.HeartFilled, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text("让小喵继续成长", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("如果 AniMeow 帮你记住了喜欢的作品，欢迎选择适合自己的方式，支持服务器维护与后续开发。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("自愿支持，现有功能免费使用。", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("夸克网盘 · 转存支持开发", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("需要安装包时，可以通过夸克网盘按需转存。符合平台规则的转存可为开发者带来推广收益，助力后续开发。",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("不想使用网盘，也可以在更新页选择服务器直链等其他渠道。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(enabled = !openingQuark, modifier = Modifier.fillMaxWidth(), onClick = {
                            openingQuark = true
                            scope.launch {
                                try {
                                    val copied = runCatchingCancellable {
                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("夸克网盘口令", SupportLinks.QUARK_SHARE_TOKEN)))
                                    }.isSuccess
                                    val opened = runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SupportLinks.QUARK_SHARE_URL)))
                                    }.isSuccess
                                    snackbar.showSnackbar(when {
                                        !opened -> "无法打开链接，请检查是否已安装浏览器或夸克"
                                        copied -> "夸克口令已复制，可在夸克中粘贴打开"
                                        else -> "已打开分享链接，口令未能复制"
                                    })
                                } finally { openingQuark = false }
                            }
                        }) { Text(if (openingQuark) "正在打开…" else "复制口令并打开夸克") }
                    }
                }
                if (sponsor.loading) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text("正在加载爱发电入口…", style = MaterialTheme.typography.bodySmall)
                } else if (sponsor.page == null) {
                    Text("爱发电入口暂未开放，感谢你的使用与建议。", style = MaterialTheme.typography.bodySmall)
                } else {
                Text("爱发电 · 自愿赞助", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                FilledTonalButton(
                    onClick = {
                        val page = sponsor.page ?: return@FilledTonalButton
                        val opened = runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)))
                        }.isSuccess
                        if (!opened) scope.launch { snackbar.showSnackbar("无法打开浏览器，请检查是否已安装浏览器") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("前往爱发电") }
                }
                // The bundled QR belongs to the default creator only, not any remotely configured replacement.
                if (sponsor.page?.trimEnd('/') == SponsorService.DEFAULT_PAGE) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("XunLys 的爱发电二维码", fontWeight = FontWeight.SemiBold)
                            Image(
                                painterResource(R.drawable.afdian_support_qr),
                                contentDescription = "XunLys 的爱发电主页二维码",
                                modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth().background(Color.White).padding(12.dp),
                            )
                            Text("可用另一台设备扫码，或保存原图后在支持相册识码的应用中打开。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { saveQr.launch("AniMeow-爱发电二维码.jpg") }, enabled = !savingQr,
                                modifier = Modifier.fillMaxWidth()) {
                                Text(if (savingQr) "正在保存…" else "保存二维码原图")
                            }
                        }
                    }
                }
                SponsorThanksCard()
                Text("谢谢你的使用、反馈和支持。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
