package com.animeow.app.data.branding

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.animeow.app.data.media.openInputStreamCompat
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BrandingAssetStore(context: Context) {
    private val appContext = context.applicationContext
    val rootDirectory: File = File(appContext.filesDir, DIRECTORY_NAME)

    suspend fun importSplash(uri: Uri): String = withContext(Dispatchers.IO) {
        rootDirectory.mkdirs()
        val extension = preferredExtension(uri)
        val temporary = File(rootDirectory, "splash_import_${System.currentTimeMillis()}.tmp")
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
                error("无法读取所选图片（权限被拒绝）\nURI: $uri\n原因: ${e.message}")
            } catch (e: java.io.FileNotFoundException) {
                error("无法读取所选图片\nURI: $uri\n原因: ${e.message}")
            } catch (e: java.io.IOException) {
                error("无法读取所选图片（IO 错误）\nURI: $uri\n原因: ${e.javaClass.simpleName}: ${e.message}")
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "所选文件不是受支持的图片" }
            require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_SPLASH_PIXELS) {
                "图片分辨率过高，请选择 5000 万像素以内的图片"
            }

            val destination = File(rootDirectory, "splash_${System.currentTimeMillis()}.$extension")
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            destination.relativeTo(appContext.filesDir).invariantSeparatorsPath
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

    suspend fun cleanupSplashFiles(keepRelativePath: String?) = withContext(Dispatchers.IO) {
        val keep = resolve(keepRelativePath)
        runCatching {
            rootDirectory.listFiles { file -> file.isFile && file.name.startsWith(SPLASH_PREFIX) }
                .orEmpty()
                .filterNot { it == keep }
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

    private fun preferredExtension(uri: Uri): String {
        return when (appContext.contentResolver.getType(uri)?.lowercase(java.util.Locale.ROOT)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/jpeg", "image/jpg" -> "jpg"
            else -> displayName(uri)
                ?.substringAfterLast('.', "jpg")
                ?.lowercase(java.util.Locale.ROOT)
                ?.takeIf { it in SUPPORTED_EXTENSIONS }
                ?: "jpg"
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    companion object {
        const val DIRECTORY_NAME = "branding"
        private const val SPLASH_PREFIX = "splash_"
        private const val MAX_SPLASH_BYTES = 25L * 1024 * 1024
        private const val MAX_SPLASH_PIXELS = 50_000_000L
        private val SUPPORTED_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}
