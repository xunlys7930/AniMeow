package com.animeow.app.data.background

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.animeow.app.data.media.openInputStreamCompat
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WallpaperAssetStore(context: Context) {
    private val appContext = context.applicationContext
    val rootDirectory: File = File(appContext.filesDir, DIRECTORY_NAME).canonicalFile

    suspend fun importWallpaper(uri: Uri): String = withContext(Dispatchers.IO) {
        rootDirectory.mkdirs()
        val token = java.util.UUID.randomUUID().toString()
        val temporary = File(rootDirectory, "wallpaper_import_$token.tmp")
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
                            require(total <= MAX_WALLPAPER_BYTES) { "图片不能超过 25 MB" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } catch (e: SecurityException) {
                error("无法读取所选壁纸，请重新从相册选择")
            } catch (e: java.io.FileNotFoundException) {
                error("所选图片已不可用，请重新选择或先下载到本机")
            } catch (e: java.io.IOException) {
                error("读取图片失败，请重试或选择另一张图片")
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "所选文件不是受支持的图片" }
            require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_WALLPAPER_PIXELS) {
                "图片分辨率过高，请选择 5000 万像素以内的图片"
            }

            val extension = when (bounds.outMimeType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/heif", "image/heic" -> "heic"
                else -> "jpg"
            }
            val destination = File(rootDirectory, "wallpaper_$token.$extension")
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

    fun discard(relativePath: String?) {
        val file = resolve(relativePath) ?: return
        if (file.parentFile == rootDirectory && file.name.startsWith("wallpaper_")) {
            file.delete()
        }
    }

    fun cleanupOldWallpapers(keepRelativePath: String?) {
        val keepFile = resolve(keepRelativePath)
        val files = rootDirectory.listFiles { candidate ->
            candidate.isFile && candidate.name.startsWith("wallpaper_") && candidate != keepFile
        } ?: return
        for (file in files) {
            file.delete()
        }
    }

    companion object {
        const val DIRECTORY_NAME = "wallpapers"
        const val MAX_WALLPAPER_BYTES = 25 * 1024 * 1024L
        const val MAX_WALLPAPER_PIXELS = 50_000_000L
    }
}
