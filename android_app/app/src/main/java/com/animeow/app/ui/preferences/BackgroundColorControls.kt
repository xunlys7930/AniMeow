package com.animeow.app.ui.preferences

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.animeow.app.data.background.BackgroundScaleMode
import com.animeow.app.data.background.DEFAULT_BACKGROUND_COLOR_PRESETS
import com.animeow.app.ui.components.CustomizableColorSelector
import com.animeow.app.ui.theme.AppearanceSettings
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun BackgroundColorControls(
    settings: AppearanceSettings,
    wallpaperResolver: (String?) -> File?,
    onBackgroundColor: (Long?) -> Unit,
    onImportWallpaper: (Uri) -> Unit,
    onRemoveWallpaper: () -> Unit,
    onScaleMode: (BackgroundScaleMode) -> Unit,
    onOpacity: (Float) -> Unit,
    onBlur: (Int) -> Unit,
    onDimOverlay: (Float) -> Unit,
) {
    val independentBg = settings.backgroundColor != null
    val currentBgColor = settings.backgroundColor ?: 0xFF000000L

    val pendingWallpaper = remember { androidx.compose.runtime.mutableStateOf<Uri?>(null) }
    val wallpaperLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // 先进入裁剪，而不是直接把原图当壁纸：全屏底图必须先选好可见区域，
        // 否则竖屏会看到横图被压扁、或主体被裁在画面之外。
        if (uri != null) pendingWallpaper.value = uri
    }
    val wallpaperFile = remember(settings.backgroundImagePath) { wallpaperResolver(settings.backgroundImagePath) }
    val hasWallpaper = wallpaperFile != null && wallpaperFile.exists()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pendingWallpaper.value?.let { picked ->
            // 按当前窗口比例裁剪，与"铺满"的显示方式一致。
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val aspect = if (configuration.screenHeightDp > 0) {
                configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.toFloat()
            } else 1f
            com.animeow.app.ui.community.ImageCropDialog(
                imageUrl = picked.toString(),
                cropShape = com.animeow.app.ui.community.CropShape.Rectangle,
                title = "裁剪背景壁纸",
                cropAspectRatio = aspect,
                onConfirm = { cropped ->
                    pendingWallpaper.value = null
                    onImportWallpaper(cropped)
                },
                onDismiss = { pendingWallpaper.value = null },
            )
        }

        Text("背景颜色", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().toggleable(independentBg, role = Role.Switch,
                onValueChange = { onBackgroundColor(if (it) currentBgColor else null) }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("独立设置背景色", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (independentBg) "用于全局页面底色，支持 OLED 纯黑、护眼色及自由取色。"
                    else "当前跟随所选风格与深浅外观；开启后可自由指定底色。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = independentBg, onCheckedChange = null)
        }

        if (independentBg) {
            CustomizableColorSelector(
                color = currentBgColor,
                presets = DEFAULT_BACKGROUND_COLOR_PRESETS,
                onColorChanged = { onBackgroundColor(it) },
                modifier = Modifier.fillMaxWidth(),
                dialogTitle = "自定义 AniMeow 背景色",
            )
        }

        Text("自定义背景壁纸", style = MaterialTheme.typography.labelLarge)
        Text(
            "选择相册图片作为全屏底图。开启后页面底色自动转为透光遮罩，既有氛围又不影响阅读。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (hasWallpaper) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                AsyncImage(
                    model = wallpaperFile,
                    contentDescription = "背景壁纸预览",
                    contentScale = when (settings.backgroundScaleMode) {
                        BackgroundScaleMode.COVER -> ContentScale.Crop
                        BackgroundScaleMode.CONTAIN -> ContentScale.Fit
                        BackgroundScaleMode.STRETCH -> ContentScale.FillBounds
                    },
                    modifier = Modifier.matchParentSize(),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { wallpaperLauncher.launch("image/*") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("更换壁纸")
                }
                OutlinedButton(
                    onClick = onRemoveWallpaper,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("移除壁纸")
                }
            }

            Text("壁纸缩放模式", style = MaterialTheme.typography.labelMedium)
            ChoiceFlow(
                values = BackgroundScaleMode.entries,
                selected = settings.backgroundScaleMode,
                label = { it.displayName },
                onSelected = onScaleMode,
            )

            var opacityDraft by remember(settings.backgroundImageOpacity) {
                mutableFloatStateOf(settings.backgroundImageOpacity)
            }
            Text("壁纸不透明度：${(opacityDraft * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = opacityDraft,
                onValueChange = { opacityDraft = it },
                onValueChangeFinished = { onOpacity(opacityDraft) },
                valueRange = 0.05f..1f,
            )

            var blurDraft by remember(settings.backgroundImageBlur) {
                mutableFloatStateOf(settings.backgroundImageBlur.toFloat())
            }
            Text("壁纸高斯模糊：${blurDraft.roundToInt()} dp", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = blurDraft,
                onValueChange = { blurDraft = it },
                onValueChangeFinished = { onBlur(blurDraft.roundToInt()) },
                valueRange = 0f..30f,
                steps = 29,
            )

            var dimDraft by remember(settings.backgroundDimOverlay) {
                mutableFloatStateOf(settings.backgroundDimOverlay)
            }
            Text("页面遮罩浓度：${(dimDraft * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = dimDraft,
                onValueChange = { dimDraft = it },
                onValueChangeFinished = { onDimOverlay(dimDraft) },
                valueRange = 0f..0.95f,
            )
            Text(
                "调节遮罩浓度可平衡壁纸透出感与文字对比度，建议保持在 50%~75%。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FilledTonalButton(
                onClick = { wallpaperLauncher.launch("image/*") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("从相册选择背景壁纸")
            }
        }
    }
}
