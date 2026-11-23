package com.animeow.app.data.branding

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.Uri
import com.animeow.app.data.media.openInputStreamCompat
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BrandingAssetStore(context: Context) {
    private val appContext = context.applicationContext
    val rootDirectory: File = File(appContext.filesDir, DIRECTORY_NAME).canonicalFile

    suspend fun importSplash(uri: Uri): String = withContext(Dispatchers.IO) {
        rootDirectory.mkdirs()
        val token = java.util.UUID.randomUUID().toString()
        val temporary = File(rootDirectory, "splash_import_$token.tmp")
        try {
            try {
                openInputStreamCompat(appContext, uri).use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_SPLASH_BYTES) { "图片不能超过 25 MB" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } catch (e: SecurityException) {
                error("无法读取所选图片，请重新从相册选择")
            } catch (e: java.io.FileNotFoundException) {
                error("所选图片已不可用，请重新选择或先下载到本机")
            } catch (e: java.io.IOException) {
                error("读取图片失败，请重试或选择另一张图片")
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "所选文件不是受支持的图片" }
            require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_SPLASH_PIXELS) {
                "图片分辨率过高，请选择 5000 万像素以内的图片"
            }

            val extension = when (bounds.outMimeType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/heif", "image/heic" -> "heic"
                else -> "jpg"
            }
            val destination = File(rootDirectory, "splash_$token.$extension")
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            "$DIRECTORY_NAME/${destination.name}"
        } finally {
            temporary.delete()
        }
    }

    fun resolve(relativePath: String?): File? {
        val path = relativePath?.takeIf(String::isNotBlank) ?: return null
        val root = appContext.filesDir.canonicalFile
        val candidate = runCatching { File(root, path).canonicalFile }.getOrNull() ?: return null
        val prefix = root.path + File.separator
        return candidate.takeIf { it.path.startsWith(prefix) && it.isFile }
    }

    /** Store a bounded, square PNG. The gallery URI is never needed after this returns. */
    suspend fun importCustomIcon(uri: Uri): String = withContext(Dispatchers.IO) {
        rootDirectory.mkdirs()
        val token = java.util.UUID.randomUUID().toString()
        val temporary = File(rootDirectory, "icon_import_$token.tmp")
        val destination = File(rootDirectory, "icon_$token.png")
        try {
            openInputStreamCompat(appContext, uri).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_SPLASH_BYTES) { "图标图片不能超过 25 MB" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "所选文件不是受支持的图片" }
            require(options.outWidth.toLong() * options.outHeight <= MAX_SPLASH_PIXELS) { "图标图片分辨率过高" }
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1_024) {
                options.inSampleSize *= 2
            }
            val decoded = requireNotNull(BitmapFactory.decodeFile(temporary.path, options)) { "图标图片解码失败" }
            try {
                val side = minOf(decoded.width, decoded.height)
                val cropped = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
                try {
                    val scaled = Bitmap.createScaledBitmap(cropped, 512, 512, true)
                    try {
                        FileOutputStream(destination).use { output ->
                            check(scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) { "图标图片保存失败" }
                        }
                    } finally {
                        if (scaled !== cropped) scaled.recycle()
                    }
                } finally {
                    if (cropped !== decoded) cropped.recycle()
                }
            } finally {
                decoded.recycle()
            }
            "$DIRECTORY_NAME/${destination.name}"
        } catch (error: Throwable) {
            destination.delete()
            throw error
        } finally {
            temporary.delete()
        }
    }

    fun resolveCustomIcon(relativePath: String?): File? = resolve(relativePath)?.takeIf {
        it.parentFile?.canonicalFile == rootDirectory && it.name.startsWith("icon_") && it.extension == "png"
    }

    suspend fun cleanupCustomIcons(keepRelativePath: String?) = withContext(Dispatchers.IO) {
        val keep = resolveCustomIcon(keepRelativePath)
        rootDirectory.listFiles { file -> file.isFile && file.name.startsWith("icon_") }
            .orEmpty().filterNot { it.canonicalFile == keep?.canonicalFile }.forEach(File::delete)
    }

    suspend fun discardCustomIcon(relativePath: String?) = withContext(Dispatchers.IO) {
        resolveCustomIcon(relativePath)?.delete()
        Unit
    }

    suspend fun cleanupSplashFiles(keepRelativePath: String?) = withContext(Dispatchers.IO) {
        val keep = resolve(keepRelativePath)
        runCatching {
            rootDirectory.listFiles { file -> file.isFile && file.name.startsWith(SPLASH_PREFIX) }
                .orEmpty()
                .filterNot { it.canonicalFile == keep?.canonicalFile }
                .forEach(File::delete)
        }
    }

    suspend fun discardSplash(relativePath: String?) = withContext(Dispatchers.IO) {
        runCatching {
            resolve(relativePath)
                ?.takeIf { it.parentFile?.canonicalFile == rootDirectory.canonicalFile }
                ?.takeIf { it.name.startsWith(SPLASH_PREFIX) }
                ?.delete()
        }
    }

    companion object {
        const val DIRECTORY_NAME = "branding"
        private const val SPLASH_PREFIX = "splash_"
        private const val MAX_SPLASH_BYTES = 25L * 1024 * 1024
        private const val MAX_SPLASH_PIXELS = 50_000_000L
    }
}
