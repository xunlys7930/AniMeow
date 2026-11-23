package com.animeow.app.data.media

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

private const val URI_STREAM_LOG_TAG = "UriInputStream"

/**
 * 打开 [uri] 的输入流，兼容 `file://` 和 `content://` 两种 scheme。
 *
 * **问题背景**：`ContentResolver.openInputStream()` 在某些设备/ROM 上对 `file://` URI
 * 会返回 null（尽管 Android 文档说应该返回 FileInputStream 或抛出 FileNotFoundException）。
 * 此函数对 `file://` URI 直接使用 [FileInputStream]，绕过 ContentResolver。
 *
 * @return 输入流，调用方负责 close
 * @throws java.io.FileNotFoundException 文件不存在或 URI 无法打开
 */
fun openInputStreamCompat(context: Context, uri: Uri): InputStream {
    return when (uri.scheme?.lowercase()) {
        "file" -> {
            val path = uri.path
            if (path == null) {
                Log.w(URI_STREAM_LOG_TAG, "file URI missing path: $uri")
                throw java.io.FileNotFoundException("找不到所选图片文件，请重新选择")
            }
            val file = File(path)
            if (!file.exists()) {
                Log.w(URI_STREAM_LOG_TAG, "file does not exist: path=$path, canRead=${file.canRead()}")
                throw java.io.FileNotFoundException("找不到所选图片文件，请重新选择")
            }
            FileInputStream(file)
        }
        else -> {
            context.contentResolver.openInputStream(uri) ?: run {
                Log.w(
                    URI_STREAM_LOG_TAG,
                    "ContentResolver.openInputStream returned null: uri=$uri, scheme=${uri.scheme}, authority=${uri.authority}",
                )
                throw java.io.FileNotFoundException("找不到所选图片文件，请重新选择")
            }
        }
    }
}
