package com.animeow.app.ui.community

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
internal fun ImageCropDialog(
    imageUrl: String,
    cropShape: CropShape = CropShape.Circle,
    onConfirm: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }

    // 手势状态
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // 布局尺寸
    var boxWidth by remember { mutableFloatStateOf(0f) }
    var boxHeight by remember { mutableFloatStateOf(0f) }

    // 加载图片
    LaunchedEffect(imageUrl) {
        loading = true
        loadError = false
        try {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
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
        } catch (_: Throwable) {
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
            try {
                val uri = withContext(Dispatchers.IO) {
                    cropBitmapToFile(context, bmp, bw, bh, scale, offsetX, offsetY, cropShape)
                }
                onConfirm(uri)
            } catch (_: Throwable) {
                // 裁剪失败时不阻塞用户操作
            } finally {
                processing = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxSize().padding(12.dp),
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
                        text = if (cropShape == CropShape.Circle) "裁剪头像" else "裁剪图片",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, enabled = !processing) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }

                // 裁剪区域
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                        .aspectRatio(1f)
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
                                    .pointerInput(boxWidth, boxHeight) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            val newScale = (scale * zoom).coerceIn(0.3f, 8f)
                                            // 限制平移范围，防止图片移出裁剪框
                                            val imgW = bmp.width * csScale * newScale
                                            val imgH = bmp.height * csScale * newScale
                                            val maxX = max(0f, (imgW - boxWidth) / 2f)
                                            val maxY = max(0f, (imgH - boxHeight) / 2f)
                                            scale = newScale
                                            offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                            offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                                        }
                                    },
                            )

                            // 裁剪框遮罩
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val frameSize = min(size.width, size.height) * 0.86f
                                val frameLeft = (size.width - frameSize) / 2f
                                val frameTop = (size.height - frameSize) / 2f

                                // 半透明遮罩（EvenOdd 填充实现镂空效果）
                                val overlayPath = Path().apply {
                                    fillType = PathFillType.EvenOdd
                                    addRect(
                                        androidx.compose.ui.geometry.Rect(
                                            0f, 0f, size.width, size.height,
                                        ),
                                    )
                                    if (cropShape == CropShape.Circle) {
                                        addOval(
                                            androidx.compose.ui.geometry.Rect(
                                                frameLeft, frameTop,
                                                frameLeft + frameSize, frameTop + frameSize,
                                            ),
                                        )
                                    } else {
                                        addRect(
                                            androidx.compose.ui.geometry.Rect(
                                                frameLeft, frameTop,
                                                frameLeft + frameSize, frameTop + frameSize,
                                            ),
                                        )
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
                                        radius = frameSize / 2f,
                                        center = Offset(size.width / 2f, size.height / 2f),
                                        style = Stroke(width = 2.dp.toPx()),
                                    )
                                } else {
                                    drawRect(
                                        color = Color.White.copy(alpha = 0.8f),
                                        topLeft = Offset(frameLeft, frameTop),
                                        size = Size(frameSize, frameSize),
                                        style = Stroke(width = 2.dp.toPx()),
                                    )
                                }
                            }
                        }
                    }
                }

                // 底部操作栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !processing) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
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
private fun cropBitmapToFile(
    context: Context,
    bitmap: Bitmap,
    boxWidth: Float,
    boxHeight: Float,
    userScale: Float,
    offsetX: Float,
    offsetY: Float,
    cropShape: CropShape,
): Uri {
    val bmpW = bitmap.width.toFloat()
    val bmpH = bitmap.height.toFloat()

    val cropFrameSize = min(boxWidth, boxHeight) * 0.86f

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
    val frameLeft = (boxWidth - cropFrameSize) / 2f
    val frameTop = (boxHeight - cropFrameSize) / 2f

    // 裁剪区域在图片显示坐标系中的偏移
    val cropDispX = frameLeft - imgLeft
    val cropDispY = frameTop - imgTop

    // 转换到原始 Bitmap 坐标
    var cropBmpX = (cropDispX / effectiveScale).roundToInt()
    var cropBmpY = (cropDispY / effectiveScale).roundToInt()
    var cropBmpSize = (cropFrameSize / effectiveScale).roundToInt()

    // 边界保护
    cropBmpX = cropBmpX.coerceIn(0, bitmap.width - 1)
    cropBmpY = cropBmpY.coerceIn(0, bitmap.height - 1)
    cropBmpSize = cropBmpSize.coerceIn(1, min(bitmap.width - cropBmpX, bitmap.height - cropBmpY))

    // 裁剪
    val cropped = Bitmap.createBitmap(bitmap, cropBmpX, cropBmpY, cropBmpSize, cropBmpSize)

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
