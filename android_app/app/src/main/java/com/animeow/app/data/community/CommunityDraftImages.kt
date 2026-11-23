package com.animeow.app.data.community

import android.content.Context
import android.net.Uri
import com.animeow.app.data.media.openInputStreamCompat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Owns draft attachments independently of picker grants and the disposable image cache. */
internal class CommunityDraftImages(
    context: Context,
    private val directory: File = File(context.noBackupFilesDir, "community_draft_images"),
) {
    private val appContext = context.applicationContext

    suspend fun retain(source: Uri): Uri {
        var created: File? = null
        try {
            return withContext(Dispatchers.IO) {
                ownedFile(source)?.takeIf(File::isFile)?.let { return@withContext source }
                check(directory.isDirectory || directory.mkdirs()) { "无法保存草稿图片，请检查存储空间" }
                val target = File.createTempFile("draft_", ".image", directory)
                created = target
                openInputStreamCompat(appContext, source).use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_IMAGE_BYTES) { "单张图片不能超过 20 MB，请选择较小的图片" }
                            output.write(buffer, 0, count)
                        }
                        require(total > 0) { "所选图片为空，请重新选择" }
                    }
                }
                Uri.fromFile(target)
            }
        } catch (error: Throwable) {
            // Also handles prompt cancellation while returning from the IO dispatcher.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { created?.delete() }
            throw error
        }
    }

    suspend fun release(uris: Collection<Uri>) = withContext(Dispatchers.IO) {
        uris.forEach { uri -> ownedFile(uri)?.delete() }
    }

    private fun ownedFile(uri: Uri): File? {
        if (uri.scheme != "file") return null
        val path = uri.path ?: return null
        return File(path).canonicalFile.takeIf { it.parentFile == directory.canonicalFile }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    }
}
