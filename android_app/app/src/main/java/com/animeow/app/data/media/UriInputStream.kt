package com.animeow.app.data.media

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

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
                ?: throw java.io.FileNotFoundException("file URI 缺少路径: $uri")
            val file = File(path)
            if (!file.exists()) {
                throw java.io.FileNotFoundException("缓存文件不存在: $path\n" +
                    "文件大小: ${if (file.exists()) file.length() else "不存在"}, " +
                    "可读: ${file.canRead()}")
            }
            FileInputStream(file)
        }
        else -> {
            context.contentResolver.openInputStream(uri)
                ?: throw java.io.FileNotFoundException(
                    "ContentResolver.openInputStream() 返回 null\n" +
                        "URI: $uri\n" +
                        "scheme=${uri.scheme}, authority=${uri.authority}, path=${uri.path}",
                )
        }
    }
}
