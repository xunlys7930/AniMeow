package com.animeow.app.ui.components

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.animeow.app.util.runCatchingCancellable
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ZoomableImageViewer(
    imageUrl: String,
    title: String,
    onDismiss: () -> Unit,
    onModify: (() -> Unit)? = null,
    authToken: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var scale by remember(imageUrl) { mutableStateOf(1f) }
    var offset by remember(imageUrl) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    val displayModel = remember(imageUrl, authToken) {
        if (authToken != null) {
            ImageRequest.Builder(context)
                .data(imageUrl)
                .addHeader("Authorization", "Bearer $authToken")
                .crossfade(true)
                .build()
        } else {
            imageUrl
        }
    }

    fun clampOffset(candidate: Offset, nextScale: Float = scale): Offset {
        val maxX = viewport.width * (nextScale - 1f) / 2f
        val maxY = viewport.height * (nextScale - 1f) / 2f
        return Offset(
            x = candidate.x.coerceIn(-maxX, maxX),
            y = candidate.y.coerceIn(-maxY, maxY),
        )
    }

    val transformState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 4f)
        val viewportCenter = Offset(viewport.width / 2f, viewport.height / 2f)
        val centroidFromCenter = if (centroid.x.isFinite() && centroid.y.isFinite()) {
            centroid - viewportCenter
        } else {
            Offset.Zero
        }
        scale = nextScale
        offset = clampOffset(
            offset * zoomChange + centroidFromCenter * (1f - zoomChange) + panChange,
            nextScale,
        )
        if (nextScale == 1f) offset = Offset.Zero
    }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/jpeg"),
    ) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        saving = true
        scope.launch {
            runCatchingCancellable { saveImage(context, imageUrl, target, authToken) }
                .onSuccess { Toast.makeText(context, "图片已保存", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message ?: "图片保存失败", Toast.LENGTH_LONG).show() }
            saving = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black)
                .onSizeChanged { viewport = it }
                .pointerInput(imageUrl) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2f
                            offset = Offset.Zero
                        },
                    )
                }
                .transformable(transformState),
        ) {
            AsyncImage(
                model = displayModel,
                contentDescription = title,
                modifier = Modifier.fillMaxSize().graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
                contentScale = ContentScale.Fit,
            )
            Row(
                modifier = Modifier.fillMaxSize().statusBarsPadding().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                ViewerActionButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onModify?.let { modify ->
                        ViewerActionButton(
                            onClick = {
                                onDismiss()
                                modify()
                            },
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = "修改封面", tint = Color.White)
                        }
                    }
                    ViewerActionButton(
                        onClick = {
                            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            saveLauncher.launch("AniMeow_cover_$stamp.jpg")
                        },
                        enabled = !saving,
                    ) {
                        if (saving) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Download, contentDescription = "保存图片", tint = Color.White)
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    title,
                    color = Color.White.copy(alpha = 0.88f),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "双指缩放与拖动 · 双击${if (scale > 1f) "复位" else "放大"}",
                    color = Color.White.copy(alpha = 0.58f),
                )
            }
        }
    }
}

@Composable
private fun ViewerActionButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
    ) {
        IconButton(onClick = onClick, enabled = enabled, content = content)
    }
}

private suspend fun saveImage(
    context: android.content.Context,
    source: String,
    target: Uri,
    authToken: String? = null,
) = withContext(Dispatchers.IO) {
    val request = ImageRequest.Builder(context)
        .data(source)
        .apply {
            authToken?.takeIf(String::isNotBlank)?.let { addHeader("Authorization", "Bearer $it") }
        }
        .allowHardware(false)
        .build()
    val result = ImageLoader(context).execute(request) as? SuccessResult
        ?: error("无法读取当前图片")
    val bitmap = result.drawable.toBitmap(config = Bitmap.Config.ARGB_8888)
    try {
        context.contentResolver.openOutputStream(target, "w")?.use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output)) { "图片编码失败" }
        } ?: error("无法写入所选位置")
    } finally {
        bitmap.recycle()
    }
}
