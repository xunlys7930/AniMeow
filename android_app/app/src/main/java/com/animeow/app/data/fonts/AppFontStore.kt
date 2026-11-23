package com.animeow.app.data.fonts

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.animeow.app.util.withDataTransferLock

data class ImportedAppFont(val id: String, val name: String, val byteSize: Long)

internal object AppFontValidation {
    const val MAX_BYTES = 24 * 1024 * 1024
    fun validId(value: String?): String? = value?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
    fun displayName(value: String): String = value.filterNot { it.isISOControl() || it in "/\\" }.trim().take(80).ifBlank { "导入字体" }

    /** Bounds-check the SFNT directory before handing user bytes to Android's font parser. */
    fun validate(bytes: ByteArray) {
        require(bytes.size in 12..MAX_BYTES) { "字体需小于 24 MiB" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val signature = buffer.getInt(0)
        require(signature == 0x00010000 || signature == 0x4F54544F) { "请选择 TTF 或 OTF 字体；不支持压缩包、TTC、WOFF" }
        val count = buffer.getShort(4).toInt() and 0xFFFF
        require(count in 1..512 && 12 + count * 16 <= bytes.size) { "字体表损坏" }
        val tags = mutableSetOf<String>()
        repeat(count) { index ->
            val entry = 12 + index * 16
            val tag = String(bytes, entry, 4, Charsets.US_ASCII)
            val offset = buffer.getInt(entry + 8).toLong() and 0xFFFFFFFFL
            val size = buffer.getInt(entry + 12).toLong() and 0xFFFFFFFFL
            require(tags.add(tag) && offset >= 12 + count * 16 && offset + size <= bytes.size) { "字体表越界或重复" }
            if (tag == "head") require(size >= 54 && buffer.getInt(offset.toInt() + 12) == 0x5F0F3CF5) { "字体头损坏" }
        }
        require(tags.containsAll(setOf("cmap", "head", "maxp", "name", "hhea", "hmtx"))) { "字体缺少必需的字符或度量表" }
        require(if (signature == 0x00010000) tags.containsAll(setOf("glyf", "loca")) else "CFF " in tags || "CFF2" in tags) { "字体缺少字形" }
    }
}

internal class AppFontStore(context: Context) {
    private val appContext = context.applicationContext
    // NativeBackupService already snapshots and rolls back the entire branding directory.
    private val directory = File(appContext.filesDir, "branding/fonts")

    suspend fun import(uri: Uri): ImportedAppFont = withContext(Dispatchers.IO) {
        withDataTransferLock {
        mutationMutex.withLock {
            directory.mkdirs()
            val temporary = File.createTempFile("import_", ".tmp", directory)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= AppFontValidation.MAX_BYTES) { "字体需小于 24 MiB，请选择较小的字体文件" }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: error("无法读取所选字体")
                AppFontValidation.validate(temporary.readBytes())
                Typeface.createFromFile(temporary) // Reject fonts Android cannot load before changing settings.
                val id = digest.digest().joinToString("") { "%02x".format(it) }
                val destination = fontFile(id)
                val fonts = listFiles()
                require(destination.exists() || (fonts.size < 8 && fonts.sumOf { it.byteSize } + temporary.length() <= 96L * 1024 * 1024)) {
                    "最多保留 8 款字体、合计 96 MiB；请先删除不再使用的字体"
                }
                val name = AppFontValidation.displayName(readDisplayName(uri))
                if (!destination.exists()) check(temporary.renameTo(destination)) { "保存字体失败，请重试" }
                File(directory, "$id.json").writeText(JSONObject().put("name", name).toString())
                ImportedAppFont(id, name, destination.length())
            } finally { temporary.delete() }
        }
        }
    }

    suspend fun list(): List<ImportedAppFont> = withContext(Dispatchers.IO) { listFiles() }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        withDataTransferLock {
        mutationMutex.withLock {
            require(AppFontValidation.validId(id) != null)
            check(!fontFile(id).exists() || fontFile(id).delete()) { "无法删除字体" }
            File(directory, "$id.json").delete()
        }
        }
    }

    suspend fun load(id: String?): Typeface? = withContext(Dispatchers.IO) {
        val validId = AppFontValidation.validId(id) ?: return@withContext null
        val file = fontFile(validId)
        if (!file.isFile || file.length() !in 12..AppFontValidation.MAX_BYTES.toLong()) return@withContext null
        runCatching {
            val bytes = file.readBytes()
            AppFontValidation.validate(bytes)
            val actualId = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(actualId == validId)
            Typeface.createFromFile(file)
        }.getOrNull()
    }

    private fun fontFile(id: String) = File(directory, "$id.font")
    private fun listFiles(): List<ImportedAppFont> = directory.listFiles().orEmpty().mapNotNull { file ->
        val id = AppFontValidation.validId(file.name.removeSuffix(".font"))?.takeIf { file.extension == "font" } ?: return@mapNotNull null
        val metadata = File(directory, "$id.json")
        val name = runCatching { if (metadata.length() in 1..4096) JSONObject(metadata.readText()).optString("name") else "" }.getOrDefault("")
        ImportedAppFont(id, AppFontValidation.displayName(name), file.length())
    }.sortedBy { it.name }

    private fun readDisplayName(uri: Uri): String = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: "导入字体"

    private companion object { val mutationMutex = Mutex() }
}
