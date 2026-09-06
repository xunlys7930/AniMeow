package com.animeow.app.ui.preferences

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.branding.BrandingSettings
import com.animeow.app.data.branding.LauncherIcon
import com.animeow.app.data.branding.SplashBackgroundMode
import com.animeow.app.data.branding.SplashScaleMode
import com.animeow.app.data.media.copyUriToCacheFile
import com.animeow.app.ui.components.motionAnimateContentSize
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrandingScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrandingViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.settings
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val cached = runCatching { copyUriToCacheFile(context, uri, "splash_img") }.getOrNull() ?: uri
                viewModel.importSplash(cached)
            }
        }
    }
    var pendingIcon by remember { mutableStateOf<LauncherIcon?>(null) }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHost.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("品牌与启动体验") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (settings == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            BrandingContent(
                settings = settings,
                splashFile = viewModel.splashFile(settings),
                busy = uiState.busy,
                onSelectImage = { imagePicker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) },
                onRemoveImage = viewModel::removeSplash,
                onSplashEnabledChanged = viewModel::setCustomSplashEnabled,
                onDurationChanged = viewModel::setSplashDurationMillis,
                onScaleModeChanged = viewModel::setSplashScaleMode,
                onBackgroundModeChanged = viewModel::setSplashBackgroundMode,
                onFocalPointChanged = viewModel::setSplashFocalPoint,
                onResetFraming = viewModel::resetSplashFraming,
                onTapToSkipChanged = viewModel::setTapToSkip,
                onIconRequested = { icon ->
                    if (icon != settings.launcherIcon) pendingIcon = icon
                },
                modifier = Modifier.padding(padding),
            )
        }
    }

    pendingIcon?.let { icon ->
        AlertDialog(
            onDismissRequest = { pendingIcon = null },
            title = { Text("切换桌面图标") },
            text = {
                Text("将切换为“${icon.displayName}”。应用内会立即记录，部分桌面启动器需要几秒后才会刷新。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingIcon = null
                        viewModel.setLauncherIcon(icon)
                    },
                ) { Text("切换") }
            },
            dismissButton = {
                TextButton(onClick = { pendingIcon = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrandingContent(
    settings: BrandingSettings,
    splashFile: File?,
    busy: Boolean,
    onSelectImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onSplashEnabledChanged: (Boolean) -> Unit,
    onDurationChanged: (Int) -> Unit,
    onScaleModeChanged: (SplashScaleMode) -> Unit,
    onBackgroundModeChanged: (SplashBackgroundMode) -> Unit,
    onFocalPointChanged: (Float, Float) -> Unit,
    onResetFraming: () -> Unit,
    onTapToSkipChanged: (Boolean) -> Unit,
    onIconRequested: (LauncherIcon) -> Unit,
    modifier: Modifier = Modifier,
) {
    var durationDraft by remember(settings.splashDurationMillis) {
        mutableFloatStateOf(settings.splashDurationMillis.toFloat())
    }
    var focalXDraft by remember(settings.splashFocalX) { mutableFloatStateOf(settings.splashFocalX) }
    var focalYDraft by remember(settings.splashFocalY) { mutableFloatStateOf(settings.splashFocalY) }
    val hasImage = splashFile != null

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("属于你的第一眼", fontWeight = FontWeight.Bold)
                        Text(
                            "系统启动页结束后展示自定义封面；构图只记录参数，不会反复压缩原图。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            BrandSettingSection("启动封面", "支持 PNG、JPEG、WebP，图片会复制到应用私有目录并进入完整备份") {
                SplashPreview(settings, splashFile)
                BrandToggleRow(
                    title = "启用自定义启动封面",
                    subtitle = if (hasImage) "下次冷启动时生效" else "先从相册选择一张图片",
                    checked = settings.customSplashEnabled && hasImage,
                    enabled = hasImage && !busy,
                    onCheckedChange = onSplashEnabledChanged,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = onSelectImage, enabled = !busy) {
                        Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                        Text(if (hasImage) "更换图片" else "选择图片", Modifier.padding(start = 6.dp))
                    }
                    if (hasImage) {
                        OutlinedButton(onClick = onRemoveImage, enabled = !busy) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                            Text("清除", Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }

        if (hasImage) item {
            BrandSettingSection("展示与构图", "为不同尺寸屏幕保存非破坏式构图") {
                Text("适配方式", style = MaterialTheme.typography.labelLarge)
                BrandChoiceFlow(
                    values = SplashScaleMode.entries,
                    selected = settings.splashScaleMode,
                    label = SplashScaleMode::displayName,
                    onSelected = onScaleModeChanged,
                )
                Text("留白背景", style = MaterialTheme.typography.labelLarge)
                BrandChoiceFlow(
                    values = SplashBackgroundMode.entries,
                    selected = settings.splashBackgroundMode,
                    label = SplashBackgroundMode::displayName,
                    onSelected = onBackgroundModeChanged,
                )
                Text("横向焦点 ${(focalXDraft * 100).roundToInt()}%")
                Slider(
                    value = focalXDraft,
                    onValueChange = { focalXDraft = it },
                    onValueChangeFinished = { onFocalPointChanged(focalXDraft, focalYDraft) },
                    valueRange = 0f..1f,
                )
                Text("纵向焦点 ${(focalYDraft * 100).roundToInt()}%")
                Slider(
                    value = focalYDraft,
                    onValueChange = { focalYDraft = it },
                    onValueChangeFinished = { onFocalPointChanged(focalXDraft, focalYDraft) },
                    valueRange = 0f..1f,
                )
                TextButton(onClick = onResetFraming) {
                    Icon(Icons.Outlined.Restore, contentDescription = null)
                    Text("恢复居中铺满", Modifier.padding(start = 6.dp))
                }
            }
        }

        item {
            BrandSettingSection("时长与跳过", "等待从图片完成解码后开始计时，避免慢设备只看到空白") {
                Text("展示时长 ${(durationDraft / 1_000f).toString().take(3)} 秒")
                Slider(
                    value = durationDraft,
                    onValueChange = { durationDraft = it },
                    onValueChangeFinished = { onDurationChanged(durationDraft.roundToInt()) },
                    valueRange = 500f..5_000f,
                    steps = 8,
                )
                BrandToggleRow(
                    title = "显示跳过按钮",
                    subtitle = "右下角提供易触达的跳过入口",
                    checked = settings.tapToSkip,
                    onCheckedChange = onTapToSkipChanged,
                )
            }
        }

        item {
            BrandSettingSection("桌面图标", "保留原版提供的 12 套备用图标；切换不会重启当前页面") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(LauncherIcon.entries, key = LauncherIcon::storageKey) { icon ->
                        LauncherIconTile(
                            icon = icon,
                            selected = icon == settings.launcherIcon,
                            enabled = !busy,
                            onClick = { onIconRequested(icon) },
                        )
                    }
                }
                Text(
                    "某些桌面会保留旧图标缓存；返回桌面停留几秒或锁屏再解锁即可刷新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SplashPreview(settings: BrandingSettings, imageFile: File?) {
    val background = when (settings.splashBackgroundMode) {
        SplashBackgroundMode.THEME -> MaterialTheme.colorScheme.background
        SplashBackgroundMode.BLACK -> Color.Black
        SplashBackgroundMode.WHITE -> Color.White
        SplashBackgroundMode.ACCENT -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentScale = when (settings.splashScaleMode) {
        SplashScaleMode.COVER -> ContentScale.Crop
        SplashScaleMode.CONTAIN -> ContentScale.Fit
        SplashScaleMode.STRETCH -> ContentScale.FillBounds
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(background)
            .motionAnimateContentSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (imageFile != null) {
            AsyncImage(
                model = imageFile,
                contentDescription = "启动封面预览",
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                alignment = BiasAlignment(
                    settings.splashFocalX * 2f - 1f,
                    settings.splashFocalY * 2f - 1f,
                ),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.Image,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Text(
                    "尚未选择图片",
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LauncherIconTile(
    icon: LauncherIcon,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    ElevatedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.width(108.dp).heightIn(min = 132.dp).border(
            width = if (selected) 2.dp else 1.dp,
            color = borderColor,
            shape = RoundedCornerShape(20.dp),
        ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box {
                Image(
                    painter = painterResource(icon.previewResource),
                    contentDescription = icon.displayName,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)),
                )
                if (selected) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = "已选择",
                        modifier = Modifier.align(Alignment.TopEnd).size(22.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                icon.displayName,
                textAlign = TextAlign.Center,
                maxLines = 2,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun BrandSettingSection(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> BrandChoiceFlow(
    values: Iterable<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelected(value) },
                label = { Text(label(value)) },
            )
        }
    }
}

@Composable
private fun BrandToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
