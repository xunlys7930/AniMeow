package com.animeow.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CoverImageStore(private val context: Context) {
    suspend fun importCharacterImage(source: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = decodeCharacterImage(source)
        var destination: File? = null
        try {
            val directory = File(context.filesDir, "covers/characters").apply { check(isDirectory || mkdirs()) }
            val file = File(directory, "${java.util.UUID.randomUUID()}.png")
            destination = file
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "图片保存失败" }
            }
            file.toUri().toString()
        } catch (error: Throwable) {
            destination?.delete()
            throw error
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeCharacterImage(source: Uri): Bitmap {
        val bitmap = decodeSampled(source)
        val orientation = runCatching {
            openInputStreamCompat(context, source).use { input ->
                android.media.ExifInterface(input).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
            }
        }.getOrDefault(1)
        val matrix = android.graphics.Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f, 1f) }
                8 -> setRotate(-90f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } finally { bitmap.recycle() }
    }

    suspend fun importCroppedCover(
        source: Uri,
        focusX: Float,
        focusY: Float,
        zoom: Float,
        aspectRatio: Float = POSTER_RATIO,
    ): String = withContext(Dispatchers.IO) {
        val bitmap = decodeSampled(source)
        val cropped = cropToPoster(
            source = bitmap,
            focusX = focusX.coerceIn(0f, 1f),
            focusY = focusY.coerceIn(0f, 1f),
            zoom = zoom.coerceIn(1f, 3f),
            aspectRatio = aspectRatio,
        )
        val directory = File(context.filesDir, "covers/native").apply { mkdirs() }
        val destination = File(directory, "cover_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.jpg")
        FileOutputStream(destination).use { output ->
            check(cropped.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                "无法写入裁剪后的封面"
            }
        }
        if (cropped !== bitmap) cropped.recycle()
        bitmap.recycle()
        // Keep previous covers: the user may cancel the draft, or another work may reference them.
        destination.toUri().toString()
    }

    private fun decodeSampled(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            openInputStreamCompat(context, uri).use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "Permission denied reading image bounds: uri=$uri", e)
            error("无法读取所选图片，请检查相册权限或换一张图片")
        } catch (e: java.io.FileNotFoundException) {
            Log.w(LOG_TAG, "Image file not found while reading bounds: uri=$uri", e)
            error("找不到所选图片文件，请重新选择")
        } catch (e: java.io.IOException) {
            Log.w(LOG_TAG, "IO failure reading image bounds: uri=$uri", e)
            error("图片读取失败，请重新选择或换一张图片")
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            Log.w(
                LOG_TAG,
                "Unsupported image format: uri=$uri, bounds=${bounds.outWidth}x${bounds.outHeight}, mime=${bounds.outMimeType}",
            )
            "暂不支持该图片格式，请选择 JPG、PNG 或 WebP 图片"
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
            } ?: run {
                Log.w(LOG_TAG, "decodeStream returned null: uri=$uri")
                error("图片解码失败，文件可能已损坏，请换一张图片")
            }
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "Permission denied decoding image: uri=$uri", e)
            error("无法读取所选图片，请检查相册权限或换一张图片")
        } catch (e: java.io.FileNotFoundException) {
            Log.w(LOG_TAG, "Image file not found while decoding: uri=$uri", e)
            error("找不到所选图片文件，请重新选择")
        } catch (e: java.io.IOException) {
            Log.w(LOG_TAG, "IO failure decoding image: uri=$uri", e)
            error("图片解码失败，请重新选择或换一张图片")
        }
    }

    private fun cropToPoster(
        source: Bitmap,
        focusX: Float,
        focusY: Float,
        zoom: Float,
        aspectRatio: Float,
    ): Bitmap {
        require(aspectRatio.isFinite() && aspectRatio > 0f) { "封面比例无效" }
        val sourceRatio = source.width.toFloat() / source.height
        var cropWidth: Float
        var cropHeight: Float
        if (sourceRatio > aspectRatio) {
            cropHeight = source.height.toFloat()
            cropWidth = cropHeight * aspectRatio
        } else {
            cropWidth = source.width.toFloat()
            cropHeight = cropWidth / aspectRatio
        }
        cropWidth /= zoom
        cropHeight /= zoom

        val width = cropWidth.roundToInt().coerceIn(1, source.width)
        val height = cropHeight.roundToInt().coerceIn(1, source.height)
        val left = ((source.width - width) * focusX).roundToInt().coerceIn(0, source.width - width)
        val top = ((source.height - height) * focusY).roundToInt().coerceIn(0, source.height - height)
        return Bitmap.createBitmap(source, left, top, width, height)
    }

    private companion object {
        const val LOG_TAG = "CoverImageStore"
        const val POSTER_RATIO = 2f / 3f
        const val MAX_DECODE_SIDE = 4096
    }
}
