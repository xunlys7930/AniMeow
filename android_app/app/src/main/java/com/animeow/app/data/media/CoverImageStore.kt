package com.animeow.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CoverImageStore(private val context: Context) {
    suspend fun importCroppedCover(
        source: Uri,
        focusX: Float,
        focusY: Float,
        zoom: Float,
        previousUrl: String?,
    ): String = withContext(Dispatchers.IO) {
        val bitmap = decodeSampled(source)
        val cropped = cropToPoster(
            source = bitmap,
            focusX = focusX.coerceIn(0f, 1f),
            focusY = focusY.coerceIn(0f, 1f),
            zoom = zoom.coerceIn(1f, 3f),
        )
        val directory = File(context.filesDir, "covers/native").apply { mkdirs() }
        val destination = File(directory, "cover_${System.currentTimeMillis()}.jpg")
        FileOutputStream(destination).use { output ->
            check(cropped.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                "无法写入裁剪后的封面"
            }
        }
        if (cropped !== bitmap) cropped.recycle()
        bitmap.recycle()
        deletePreviousNativeCover(previousUrl, destination)
        destination.toUri().toString()
    }

    private fun decodeSampled(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            openInputStreamCompat(context, uri).use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
        } catch (e: SecurityException) {
            error("无法读取所选图片（权限被拒绝）\nURI: $uri\n原因: ${e.message}")
        } catch (e: java.io.FileNotFoundException) {
            error("无法读取所选图片\nURI: $uri\n原因: ${e.message}")
        } catch (e: java.io.IOException) {
            error("无法读取所选图片（IO 错误）\nURI: $uri\n原因: ${e.javaClass.simpleName}: ${e.message}")
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "图片格式不受支持\nURI: $uri\n解码后尺寸: ${bounds.outWidth}x${bounds.outHeight}, mime=${bounds.outMimeType}"
        }

        var sample = 1
        while (bounds.outWidth / sample > MAX_DECODE_SIDE || bounds.outHeight / sample > MAX_DECODE_SIDE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            openInputStreamCompat(context, uri).use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: error("无法解码所选图片（decodeStream 返回 null）\nURI: $uri")
        } catch (e: SecurityException) {
            error("无法解码所选图片（权限被拒绝）\nURI: $uri\n原因: ${e.message}")
        } catch (e: java.io.FileNotFoundException) {
            error("无法解码所选图片\nURI: $uri\n原因: ${e.message}")
        } catch (e: java.io.IOException) {
            error("无法解码所选图片（IO 错误）\nURI: $uri\n原因: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun cropToPoster(
        source: Bitmap,
        focusX: Float,
        focusY: Float,
        zoom: Float,
    ): Bitmap {
        val sourceRatio = source.width.toFloat() / source.height
        var cropWidth: Float
        var cropHeight: Float
        if (sourceRatio > POSTER_RATIO) {
            cropHeight = source.height.toFloat()
            cropWidth = cropHeight * POSTER_RATIO
        } else {
            cropWidth = source.width.toFloat()
            cropHeight = cropWidth / POSTER_RATIO
        }
        cropWidth /= zoom
        cropHeight /= zoom

        val width = cropWidth.roundToInt().coerceIn(1, source.width)
        val height = cropHeight.roundToInt().coerceIn(1, source.height)
        val left = ((source.width - width) * focusX).roundToInt().coerceIn(0, source.width - width)
        val top = ((source.height - height) * focusY).roundToInt().coerceIn(0, source.height - height)
        return Bitmap.createBitmap(source, left, top, width, height)
    }

    private fun deletePreviousNativeCover(previousUrl: String?, newFile: File) {
        val previous = previousUrl?.takeIf { it.startsWith("file:") }?.toUri()?.path?.let(::File) ?: return
        val nativeRoot = File(context.filesDir, "covers/native").canonicalFile
        val candidate = runCatching { previous.canonicalFile }.getOrNull() ?: return
        if (candidate != newFile && candidate.parentFile == nativeRoot && candidate.isFile) {
            candidate.delete()
        }
    }

    private companion object {
        const val POSTER_RATIO = 2f / 3f
        const val MAX_DECODE_SIDE = 4096
    }
}
