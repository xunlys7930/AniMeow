package com.animeow.app.data.community

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.animeow.app.data.media.openInputStreamCompat
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.core.graphics.scale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object CommunityImageSizing {
    fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
        var sample = 1
        while (max(width / (sample * 2), height / (sample * 2)) >= maxDimension) sample *= 2
        return sample
    }

    fun targetSize(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || max(width, height) <= maxDimension) {
            return width.coerceAtLeast(1) to height.coerceAtLeast(1)
        }
        val scale = maxDimension.toDouble() / max(width, height).toDouble()
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }
}

internal class CommunityImageCompressor(context: Context) {
    private val appContext = context.applicationContext

    suspend fun compressPostImage(
        uri: Uri,
        maxBytes: Int = 768 * 1024,
        maxDimension: Int = 1_600,
    ): CommunityUploadImage = compress(uri, maxBytes, maxDimension)

    suspend fun compressAvatar(
        uri: Uri,
        maxBytes: Int = 384 * 1024,
        maxDimension: Int = 512,
    ): CommunityUploadImage = compress(uri, maxBytes, maxDimension)

    private suspend fun compress(
        uri: Uri,
        maxBytes: Int,
        maxDimension: Int,
    ): CommunityUploadImage = withContext(Dispatchers.IO) {
        require(maxBytes >= 32 * 1024) { "图片大小限制无效" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            openInputStreamCompat(appContext, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: SecurityException) {
            throw IllegalStateException("图片读取权限已失效，请重新选择图片", e)
        } catch (e: java.io.FileNotFoundException) {
            throw IllegalStateException("找不到所选图片，请确认图片仍在设备上并重新选择", e)
        } catch (e: java.io.IOException) {
            throw IllegalStateException("暂时无法读取图片，请将图片保存到设备后重试", e)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "无法识别这张图片，请选择完整的 JPG、PNG 或 WebP 图片"
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = CommunityImageSizing.sampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap: Bitmap = try {
            openInputStreamCompat(appContext, uri).use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: error("图片可能已损坏或格式不受支持，请换一张图片")
        } catch (e: SecurityException) {
            throw IllegalStateException("图片读取权限已失效，请重新选择图片", e)
        } catch (e: java.io.FileNotFoundException) {
            throw IllegalStateException("找不到所选图片，请重新选择", e)
        } catch (e: java.io.IOException) {
            throw IllegalStateException("图片暂时无法打开，请将图片保存到设备后重试", e)
        }
        try {
            currentCoroutineContext().ensureActive()
            bitmap = rotateIfNeeded(
                bitmap,
                try {
                    openInputStreamCompat(appContext, uri).use(::readOrientation)
                } catch (e: Exception) {
                    ExifInterface.ORIENTATION_NORMAL
                },
            )
            bitmap = scaleDown(bitmap, maxDimension)

            val useWebp = bitmap.hasAlpha()
            var quality = if (useWebp) 88 else 90
            while (true) {
                currentCoroutineContext().ensureActive()
                val bytes = ByteArrayOutputStream().use { output ->
                    val compressed = bitmap.compress(compressFormat(useWebp), quality, output)
                    check(compressed) { "图片压缩失败" }
                    output.toByteArray()
                }
                if (bytes.size <= maxBytes) {
                    val result = CommunityUploadImage(
                        mimeType = if (useWebp) "image/webp" else "image/jpeg",
                        bytes = bytes,
                        width = bitmap.width,
                        height = bitmap.height,
                    )
                    return@withContext result
                }

                if (quality > 48) {
                    quality -= 9
                } else {
                    val nextWidth = (bitmap.width * 0.82f).roundToInt().coerceAtLeast(1)
                    val nextHeight = (bitmap.height * 0.82f).roundToInt().coerceAtLeast(1)
                    if (max(nextWidth, nextHeight) < 240) {
                        error("图片内容过于复杂，压缩后仍超过大小限制")
                    }
                    val resized = bitmap.scale(nextWidth, nextHeight)
                    if (resized !== bitmap) bitmap.recycle()
                    bitmap = resized
                    quality = 82
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("图片压缩失败")
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun scaleDown(source: Bitmap, maxDimension: Int): Bitmap {
        val (width, height) = CommunityImageSizing.targetSize(source.width, source.height, maxDimension)
        if (width == source.width && height == source.height) return source
        val scaled = source.scale(width, height)
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun rotateIfNeeded(source: Bitmap, orientation: Int): Bitmap {
        val transform = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> transform.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> transform.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> transform.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                transform.preScale(-1f, 1f)
                transform.postRotate(270f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                transform.preScale(-1f, 1f)
                transform.postRotate(90f)
            }
            else -> return source
        }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    private fun readOrientation(input: java.io.InputStream): Int = runCatching {
        ExifInterface(input).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    @Suppress("DEPRECATION")
    private fun compressFormat(webp: Boolean): Bitmap.CompressFormat = when {
        !webp -> Bitmap.CompressFormat.JPEG
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSY
        else -> Bitmap.CompressFormat.WEBP
    }
}
