package com.animeow.app.ui.community

import com.animeow.app.ui.icons.AppIcons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class CropShape { Circle, Rectangle }

/**
 * 图片裁剪对话框：支持平移/缩放调整，裁剪后输出为缓存文件 Uri。
 *
 * 用于预设头像（圆形裁剪）和预设贴纸（方形裁剪）的二次编辑。
 * 裁剪结果保存为 JPEG 文件并返回 [Uri]，后续可作为普通图片上传。
 *
 * @param imageUrl   原始图片 URL。
 * @param cropShape  裁剪形状：圆形（头像）或方形（贴纸）。
 * @param onConfirm  裁剪完成回调，参数为裁剪后图片的缓存文件 Uri。
 * @param onDismiss  关闭对话框回调。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ImageCropDialog(
    imageUrl: String,
    cropShape: CropShape = CropShape.Circle,
    onConfirm: (Uri) -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
    squarePngSize: Int? = null,
    /** 裁剪框宽高比（宽/高）。默认 1f 即方形，头像与图标走这条路径；背景壁纸按屏幕比例传入。 */
    cropAspectRatio: Float = 1f,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var processingError by remember { mutableStateOf<String?>(null) }

    // 手势状态
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // 布局尺寸
    var boxWidth by remember { mutableFloatStateOf(0f) }
    var boxHeight by remember { mutableFloatStateOf(0f) }
    val minimumScale = if (squarePngSize != null && bitmap != null && boxWidth > 0f && boxHeight > 0f) {
        val image = bitmap!!
        val fit = min(boxWidth / image.width, boxHeight / image.height)
        min(boxWidth, boxHeight) * 0.86f / (min(image.width, image.height) * fit)
    } else 0.3f
    LaunchedEffect(bitmap, boxWidth, boxHeight) {
        // 非方形裁剪（壁纸）要让图片先铺满裁剪框，否则框会超出图片、裁出空白边。
        // 方形路径保持原值，头像与图标的行为不变。
        val coverScale = if (cropAspectRatio != 1f && bitmap != null && boxWidth > 0f && boxHeight > 0f) {
            val image = bitmap!!
            val cs = min(boxWidth / image.width, boxHeight / image.height).coerceAtLeast(0.01f)
            max(boxWidth / image.width, boxHeight / image.height) / cs
        } else 1f
        scale = max(max(1f, minimumScale), coverScale)
        offsetX = 0f
        offsetY = 0f
    }

    // 加载图片
    LaunchedEffect(imageUrl) {
        loading = true
        loadError = false
        try {
            val loader = context.imageLoader
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .size(2_048)
                .precision(Precision.INEXACT)
                .allowHardware(false)
                .build()
            val result = loader.execute(request)
            if (result is SuccessResult) {
                val src = (result.drawable as? BitmapDrawable)?.bitmap
                bitmap = src?.copy(Bitmap.Config.ARGB_8888, true)
                if (bitmap == null) loadError = true
            } else {
                loadError = true
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            loadError = true
        } finally {
            loading = false
        }
    }

    // 计算裁剪区域并保存
    fun performCrop() {
        val bmp = bitmap ?: return
        val bw = boxWidth
        val bh = boxHeight
        if (bw <= 0f || bh <= 0f) return

        scope.launch {
            processing = true
            processingError = null
            try {
                val uri = withContext(Dispatchers.IO) {
                    cropBitmapToFile(context, bmp, bw, bh, scale, offsetX, offsetY, cropShape, squarePngSize, cropAspectRatio)
                }
                onConfirm(uri)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                processingError = "图片暂时无法保存，请重试或换一张图片"
            } finally {
                processing = false
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!processing) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title ?: if (cropShape == CropShape.Circle) "裁剪头像" else "裁剪图片",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, enabled = !processing) {
                        Icon(AppIcons.Close, contentDescription = "关闭")
                    }
                }

                Text("双指缩放，拖动画面调整位置", modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)

                // 裁剪区域
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                        .aspectRatio(cropAspectRatio)
                        .clipToBounds()
                        .testTag("image-crop-viewport")
                        .onSizeChanged { size ->
                            boxWidth = size.width.toFloat()
                            boxHeight = size.height.toFloat()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        loading -> CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        loadError -> Text(
                            "图片加载失败",
                            color = MaterialTheme.colorScheme.error,
                        )
                        bitmap != null -> {
                            val bmp = bitmap!!

                            // ContentScale.Fit 的基准缩放：让图片在 Box 内等比缩放
                            val csScale = if (bmp.width > 0 && bmp.height > 0) {
                                min(boxWidth / bmp.width, boxHeight / bmp.height).coerceAtLeast(0.01f)
                            } else 1f

                            // 渲染图片（ContentScale.Fit 处理基础适配，graphicsLayer 处理用户缩放/平移）
                            androidx.compose.foundation.Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offsetX,
                                        translationY = offsetY,
                                    )
                                    .pointerInput(boxWidth, boxHeight, minimumScale) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            val newScale = (scale * zoom).coerceIn(minimumScale, max(8f, minimumScale * 4f))
                                            // 限制平移范围，防止图片移出裁剪框
                                            val imgW = bmp.width * csScale * newScale
                                            val imgH = bmp.height * csScale * newScale
                                            val frame = cropFrameRect(boxWidth, boxHeight, cropAspectRatio)
                                            val maxX = max(0f, (imgW - frame.width) / 2f)
                                            val maxY = max(0f, (imgH - frame.height) / 2f)
                                            scale = newScale
                                            offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                            offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                                        }
                                    },
                            )

                            // 裁剪框遮罩
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                // 与拖动限制、实际裁剪共用同一份裁剪框计算，
                                // 否则预览框会和最终裁出来的图不一致。
                                val frame = cropFrameRect(size.width, size.height, cropAspectRatio)

                                // 半透明遮罩（EvenOdd 填充实现镂空效果）
                                val overlayPath = Path().apply {
                                    fillType = PathFillType.EvenOdd
                                    addRect(
                                        androidx.compose.ui.geometry.Rect(
                                            0f, 0f, size.width, size.height,
                                        ),
                                    )
                                    if (cropShape == CropShape.Circle) {
                                        addOval(frame)
                                    } else {
                                        addRect(frame)
                                    }
                                }
                                drawPath(
                                    path = overlayPath,
                                    color = Color.Black.copy(alpha = 0.55f),
                                    style = Fill,
                                )

                                // 裁剪框边线
                                if (cropShape == CropShape.Circle) {
                                    drawCircle(
                                        color = Color.White.copy(alpha = 0.8f),
                                        radius = min(frame.width, frame.height) / 2f,
                                        center = frame.center,
                                        style = Stroke(width = 2.dp.toPx()),
                                    )
                                } else {
                                    drawRect(
                                        color = Color.White.copy(alpha = 0.8f),
                                        topLeft = Offset(frame.left, frame.top),
                                        size = Size(frame.width, frame.height),
                                        style = Stroke(width = 2.dp.toPx()),
                                    )
                                }
                            }
                        }
                    }
                }

                processingError?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }

                // 底部操作栏
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = { scale = max(1f, minimumScale); offsetX = 0f; offsetY = 0f },
                        enabled = !processing && bitmap != null) { Text("重置位置") }
                    Button(
                        onClick = { performCrop() },
                        enabled = !loading && !loadError && !processing && bitmap != null,
                    ) {
                        if (processing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("确认裁剪")
                    }
                }
            }
        }
    }
}

/**
 * 根据当前缩放和平移状态，从原始 Bitmap 中裁剪出目标区域并保存为缓存文件。
 *
 * 核心算法：
 * 1. csScale = ContentScale.Fit 的基准缩放 = min(boxW/bmpW, boxH/bmpH)；
 * 2. effectiveScale = csScale * userScale —— 图片在屏幕上的实际缩放；
 * 3. 图片中心默认对齐 Box 中心，offset 为用户拖拽偏移；
 * 4. 裁剪框中心 = Box 中心，裁剪框边长 = min(boxW, boxH) * 0.86；
 * 5. 反推裁剪区域在原始 Bitmap 上的坐标。
 */
/**
 * 裁剪框在视口内的矩形（视口坐标）。
 *
 * 预览遮罩、拖动范围限制与真正的位图裁剪**必须共用这一份计算**：
 * 之前三处各算各的，把视口比例参数化之后预览框仍是方形、裁出来的却是矩形，
 * 用户看到的框和结果对不上。默认 1f 时与旧的 `min(w, h) * 0.86f` 方形结果一致。
 */
private fun cropFrameRect(
    boxWidth: Float,
    boxHeight: Float,
    cropAspectRatio: Float,
): androidx.compose.ui.geometry.Rect {
    val ratio = cropAspectRatio.takeIf { it > 0f && it.isFinite() } ?: 1f
    val width = min(boxWidth, boxHeight * ratio) * 0.86f
    val height = width / ratio
    val left = (boxWidth - width) / 2f
    val top = (boxHeight - height) / 2f
    return androidx.compose.ui.geometry.Rect(left, top, left + width, top + height)
}

private fun cropBitmapToFile(
    context: Context,
    bitmap: Bitmap,
    boxWidth: Float,
    boxHeight: Float,
    userScale: Float,
    offsetX: Float,
    offsetY: Float,
    cropShape: CropShape,
    squarePngSize: Int? = null,
    cropAspectRatio: Float = 1f,
): Uri {
    val bmpW = bitmap.width.toFloat()
    val bmpH = bitmap.height.toFloat()

    // 裁剪框：按传入的宽高比在视口内取最大矩形，再留 14% 边距。
    // 与预览遮罩、拖动限制共用 cropFrameRect，保证"看到的就是裁出来的"。
    // cropAspectRatio = 1f 时结果与原来的 min(boxWidth, boxHeight) * 0.86f 完全一致。
    val cropFrame = cropFrameRect(boxWidth, boxHeight, cropAspectRatio)
    val cropFrameWidth = cropFrame.width
    val cropFrameHeight = cropFrame.height

    // csScale：与 ContentScale.Fit 一致的基准缩放
    val csScale = if (bmpW > 0 && bmpH > 0) {
        min(boxWidth / bmpW, boxHeight / bmpH).coerceAtLeast(0.01f)
    } else 1f
    val effectiveScale = csScale * userScale

    // 图片在屏幕上的实际显示尺寸
    val displayW = bmpW * effectiveScale
    val displayH = bmpH * effectiveScale

    // 图片左上角在 Box 坐标系中的位置（默认居中 + 用户偏移）
    val imgLeft = (boxWidth - displayW) / 2f + offsetX
    val imgTop = (boxHeight - displayH) / 2f + offsetY

    // 裁剪框在 Box 坐标系中的位置
    val frameLeft = cropFrame.left
    val frameTop = cropFrame.top

    if (squarePngSize != null) {
        val side = squarePngSize.coerceIn(128, 1_024)
        val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        try {
            val ratioScale = side / cropFrameWidth
            android.graphics.Canvas(output).drawBitmap(bitmap, null, android.graphics.RectF(
                (imgLeft - frameLeft) * ratioScale,
                (imgTop - frameTop) * ratioScale,
                (imgLeft + displayW - frameLeft) * ratioScale,
                (imgTop + displayH - frameTop) * ratioScale,
            ), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
            val directory = File(context.cacheDir, "cropped_images").apply { mkdirs() }
            val file = File(directory, "icon_crop_${java.util.UUID.randomUUID()}.png")
            FileOutputStream(file).use { check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return Uri.fromFile(file)
        } finally {
            output.recycle()
        }
    }

    // 裁剪区域在图片显示坐标系中的偏移
    val cropDispX = frameLeft - imgLeft
    val cropDispY = frameTop - imgTop

    // 转换到原始 Bitmap 坐标（宽高各自换算，方形时两者相等）
    var cropBmpX = (cropDispX / effectiveScale).roundToInt()
    var cropBmpY = (cropDispY / effectiveScale).roundToInt()
    var cropBmpWidth = (cropFrameWidth / effectiveScale).roundToInt()
    var cropBmpHeight = (cropFrameHeight / effectiveScale).roundToInt()

    // 边界保护
    cropBmpX = cropBmpX.coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
    cropBmpY = cropBmpY.coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
    cropBmpWidth = cropBmpWidth.coerceIn(1, bitmap.width - cropBmpX)
    cropBmpHeight = cropBmpHeight.coerceIn(1, bitmap.height - cropBmpY)

    // 裁剪
    val cropped = Bitmap.createBitmap(bitmap, cropBmpX, cropBmpY, cropBmpWidth, cropBmpHeight)

    // 如果是圆形裁剪，应用圆形遮罩
    val finalBitmap = if (cropShape == CropShape.Circle) {
        applyCircleMask(cropped)
    } else {
        cropped
    }

    // 保存到缓存文件
    val outputDir = File(context.cacheDir, "cropped_images")
    if (!outputDir.exists()) outputDir.mkdirs()
    val outputFile = File(outputDir, "crop_${System.currentTimeMillis()}.jpeg")
    FileOutputStream(outputFile).use { out ->
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    }
    if (finalBitmap != cropped) cropped.recycle()

    return Uri.fromFile(outputFile)
}

private fun applyCircleMask(source: Bitmap): Bitmap {
    val size = min(source.width, source.height)
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    canvas.drawARGB(0, 0, 0, 0)
    paint.color = android.graphics.Color.BLACK
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(source, 0f, 0f, paint)
    return output
}
