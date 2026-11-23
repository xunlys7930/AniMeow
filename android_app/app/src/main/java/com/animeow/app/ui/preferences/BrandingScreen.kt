package com.animeow.app.ui.preferences

import com.animeow.app.ui.icons.AppIcons

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import com.animeow.app.ui.icons.AppIcon as Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.community.CropShape
import com.animeow.app.ui.community.ImageCropDialog
import java.io.File
import kotlin.math.roundToInt

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
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importSplash(uri)
    }
    var pendingIcon by remember { mutableStateOf<LauncherIcon?>(null) }
    var previewSplash by remember { mutableStateOf(false) }
    var pendingCustomIcon by rememberSaveable { mutableStateOf<String?>(null) }
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingCustomIcon = uri?.toString()
    }

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
                        Icon(AppIcons.Back, contentDescription = "返回")
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
                customIconFile = viewModel.customIconFile(settings),
                customIconSupported = viewModel.customIconSupported,
                onSelectCustomIcon = { iconPicker.launch(arrayOf("image/*")) },
                onPinCustomIcon = viewModel::addCustomIconToDesktop,
                onRemoveCustomIcon = viewModel::removeCustomIcon,
                busy = uiState.busy,
                onSelectImage = { imagePicker.launch(arrayOf("image/*")) },
                onPreview = { previewSplash = true },
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

    if (previewSplash && settings != null) viewModel.splashFile(settings)?.let { file ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { previewSplash = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            com.animeow.app.ui.branding.BrandingSplash(settings.copy(tapToSkip = true), file,
                com.animeow.app.ui.theme.LocalMotionLevel.current, onFinished = { previewSplash = false })
        }
    }

    pendingCustomIcon?.let { image ->
        ImageCropDialog(
            imageUrl = image,
            cropShape = CropShape.Rectangle,
            title = "裁剪桌面图标",
            squarePngSize = 512,
            onConfirm = { uri ->
                pendingCustomIcon = null
                viewModel.importCustomIcon(uri)
            },
            onDismiss = { pendingCustomIcon = null },
        )
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
    customIconFile: File?,
    customIconSupported: Boolean,
    onSelectCustomIcon: () -> Unit,
    onPinCustomIcon: () -> Unit,
    onRemoveCustomIcon: () -> Unit,
    busy: Boolean,
    onSelectImage: () -> Unit,
    onPreview: () -> Unit,
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
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Touch, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("属于你的第一眼", fontWeight = FontWeight.Bold)
                        Text(
                            "桌面图标也可以换。选一款喜欢的图标，再搭配自己的启动封面。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            BrandSettingSection("桌面图标", "默认使用原版图标，另有十二款猫咪图标可选。") {
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
                    "当前：${settings.launcherIcon.displayName} · 左右滑动查看全部 ${LauncherIcon.entries.size} 款",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.launcherIcon != LauncherIcon.DEFAULT) {
                    TextButton(onClick = { onIconRequested(LauncherIcon.DEFAULT) }, enabled = !busy) {
                        Text("恢复原版默认图标")
                    }
                }
                Text(
                    "切换后，桌面可能需要几秒刷新图标。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            BrandSettingSection("自定义图标", "从相册选择图片，裁剪后添加专属桌面入口。图片保存在本机，并随完整备份保留。") {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(76.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        if (customIconFile != null) {
                            AsyncImage(customIconFile, "自定义图标预览", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(AppIcons.Image, null, Modifier.size(32.dp))
                        }
                    }
                    Text(
                        if (customIconFile != null) "图片已保存。桌面会按系统图标形状裁切边缘。" else "选择方形图片效果更好，主体尽量放在中央。",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSelectCustomIcon, enabled = !busy) { Text(if (customIconFile == null) "选择图片" else "重新选择") }
                    FilledTonalButton(onClick = onPinCustomIcon, enabled = !busy && customIconFile != null && customIconSupported) { Text("添加到桌面") }
                    if (customIconFile != null) TextButton(onClick = onRemoveCustomIcon, enabled = !busy) { Text("清除图片") }
                }
                Text(
                    if (customIconSupported) "已有专属入口时会更新它的图片。应用列表继续使用上方所选图标；桌面可能显示应用角标。"
                    else "当前桌面不支持添加自定义入口，图片仍可保存。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("长按应用图标，可直接添加番剧、以图搜番或搜索资料库。", style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            BrandSettingSection("启动封面", "支持 PNG、JPEG、WebP，图片会复制到应用私有目录并进入完整备份") {
                SplashPreview(settings, splashFile)
                BrandToggleRow(
                    title = "启用自定义启动封面",
                    subtitle = if (hasImage) "重新启动 App 时展示，也可以先全屏预览" else "先从相册选择一张图片",
                    checked = settings.customSplashEnabled && hasImage,
                    enabled = hasImage && !busy,
                    onCheckedChange = onSplashEnabledChanged,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = onSelectImage, enabled = !busy) {
                        Icon(AppIcons.AddPhoto, contentDescription = null)
                        Text(if (hasImage) "更换图片" else "选择图片", Modifier.padding(start = 6.dp))
                    }
                    if (hasImage) {
                        OutlinedButton(onClick = onPreview, enabled = !busy) {
                            Icon(AppIcons.Play, contentDescription = null)
                            Text("全屏预览", Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(onClick = onRemoveImage, enabled = !busy) {
                            Icon(AppIcons.Delete, contentDescription = null)
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
                    Icon(AppIcons.History, contentDescription = null)
                    Text("恢复居中铺满", Modifier.padding(start = 6.dp))
                }
            }
        }

        item {
            BrandSettingSection("时长与跳过", "选择封面的停留时间，也可以随时跳过") {
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
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Box(
        modifier = Modifier
            .height(320.dp)
            .aspectRatio(9f / 19.5f)
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
                    AppIcons.Image,
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
        modifier = Modifier.width(108.dp).heightIn(min = 132.dp).semantics {
            this.selected = selected
        }.border(
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
                        AppIcons.CheckCircle,
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
