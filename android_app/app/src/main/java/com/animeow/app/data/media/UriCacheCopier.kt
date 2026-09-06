package com.animeow.app.data.media

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 将 [source] URI 的内容复制到应用缓存目录下的临时文件，返回 `file://` URI。
 *
 * 解决 Photo Picker / OpenDocument 返回的 `content://` URI 在 Activity 重建
 * 或延迟读取时失效导致 `openInputStream` 返回 null 的问题。
 *
 * 调用方应在选取图片的回调中立即调用此函数，将结果 URI 存入状态，
 * 后续所有 BitmapFactory / Coil 操作均使用返回的 file URI。
 */
suspend fun copyUriToCacheFile(
    context: Context,
    source: Uri,
    prefix: String = "img",
): Uri = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val tempDir = File(context.cacheDir, "picked_images").apply { mkdirs() }
    val extension = guessExtension(source, resolver)
    val tempFile = File.createTempFile(prefix, extension, tempDir)

    try {
        val stream = openInputStreamCompat(context, source)
        stream.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
    } catch (e: SecurityException) {
        error(
            "无法读取所选图片（权限被拒绝）\n" +
                "URI: ${source}\n" +
                "scheme=${source.scheme}, authority=${source.authority}\n" +
                "原因: ${e.message}",
        )
    } catch (e: IOException) {
        error(
            "无法读取所选图片（IO 错误）\n" +
                "URI: ${source}\n" +
                "原因: ${e.javaClass.simpleName}: ${e.message}",
        )
    } catch (e: Exception) {
        if (e is IllegalStateException) throw e
        error(
            "无法读取所选图片（未知错误）\n" +
                "URI: ${source}\n" +
                "原因: ${e.javaClass.simpleName}: ${e.message}",
        )
    }

    Uri.fromFile(tempFile)
}

/** 删除指定 URI 对应的缓存文件（如果是 file URI 且在 picked_images 目录下）。 */
fun cleanupCacheUri(context: Context, uri: Uri) {
    if (uri.scheme != "file") return
    val file = File(uri.path ?: return)
    val pickedDir = File(context.cacheDir, "picked_images").canonicalPath
    runCatching {
        if (file.canonicalPath.startsWith(pickedDir) && file.isFile) {
            file.delete()
        }
    }
}

private fun guessExtension(uri: Uri, resolver: android.content.ContentResolver): String {
    // 优先从 URI 路径推断
    val pathExt = uri.lastPathSegment
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()
        .orEmpty()
    if (pathExt.isNotEmpty() && pathExt.length <= 5) return ".$pathExt"

    // 其次从 MIME 类型推断
    val mimeType = runCatching { resolver.getType(uri) }.getOrNull()
    return when (mimeType) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        "image/bmp" -> ".bmp"
        else -> ".jpg"
    }
}
