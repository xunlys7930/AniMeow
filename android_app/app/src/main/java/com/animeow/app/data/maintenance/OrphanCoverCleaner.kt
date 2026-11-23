package com.animeow.app.data.maintenance

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.animeow.app.data.local.AniMeowDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面孤儿清理（2026-10-01）：REPLACE 恢复会把全部资料行换成备份里的行，
 * 恢复前的本机封面文件从此再无任何行引用，会在 covers/ 目录里静默积累到 GB 级。
 * 这里删掉 covers/ 下没有被任何行引用的文件。
 *
 * 安全边界：
 * - 引用扫描含软删除行（deletedAt 非空的行也留在备份表里），备份-恢复循环不丢封面；
 * - restored_* 目录归 NativeBackupService 自己的清理管，这里不碰；
 * - 最近 [MIN_AGE_MS] 内新写的文件跳过（防并发下载中的封面被误删；
 *   恢复流程复制出的文件 mtime 也是新的，天然在保护期内）。
 */
class OrphanCoverCleaner(
    context: Context,
    private val database: AniMeowDatabase,
) {
    private val appContext = context.applicationContext

    data class Report(val scannedFiles: Int, val deletedFiles: Int, val freedBytes: Long)

    suspend fun cleanup(): Report = withContext(Dispatchers.IO) {
        val root = File(appContext.filesDir, COVERS_DIRECTORY)
        if (!root.isDirectory) return@withContext Report(0, 0, 0)
        val referenced = collectReferencedRelativePaths(root)
        var scanned = 0
        var deleted = 0
        var freed = 0L
        val minLastModified = System.currentTimeMillis() - MIN_AGE_MS
        root.walkTopDown().filter(File::isFile).forEach { file ->
            scanned++
            val relative = runCatching { file.relativeTo(root).invariantSeparatorsPath }.getOrNull()
                ?: return@forEach
            if (relative.startsWith(RESTORED_PREFIX)) return@forEach
            if (file.lastModified() >= minLastModified) return@forEach
            if (relative in referenced) return@forEach
            val size = file.length()
            if (file.delete()) {
                deleted++
                freed += size
            }
        }
        if (deleted > 0) {
            Log.i(TAG, "孤儿封面清理：扫描 $scanned 个文件，删除 $deleted 个，释放 $freed 字节")
        }
        Report(scanned, deleted, freed)
    }

    /** 收集所有行引用的封面相对路径（含软删除行；绝对 file URI 与 animeow-cover:// 标记都认）。 */
    internal fun collectReferencedRelativePaths(root: File): Set<String> {
        val referenced = mutableSetOf<String>()
        val sqlite = database.openHelper.readableDatabase
        listOf(
            "SELECT coverUrl FROM animes WHERE coverUrl IS NOT NULL",
            "SELECT coverUrl FROM games WHERE coverUrl != ''",
            "SELECT coverUrl FROM audio_works WHERE coverUrl != ''",
            "SELECT customCoverUrl FROM series WHERE customCoverUrl IS NOT NULL",
            "SELECT imageUrl FROM characters WHERE imageUrl IS NOT NULL",
            "SELECT coverUrl FROM character_groups WHERE coverUrl IS NOT NULL",
        ).forEach { sql ->
            sqlite.query(sql).use { cursor ->
                while (cursor.moveToNext()) {
                    collectReference(cursor.getString(0), root, referenced)
                }
            }
        }
        return referenced
    }

    private fun collectReference(raw: String?, root: File, out: MutableSet<String>) {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return
        val candidate = when {
            value.startsWith(COVER_MARKER) ->
                runCatching { safeChild(root, value.removePrefix(COVER_MARKER)) }.getOrNull()
            value.startsWith("file:") -> value.toUri().path?.let(::File)
            File(value).isAbsolute -> File(value)
            else -> null
        } ?: return
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return
        val rootPath = root.canonicalFile.path + File.separator
        if (canonical.path.startsWith(rootPath)) {
            out.add(canonical.relativeTo(root).invariantSeparatorsPath)
        }
    }

    private fun safeChild(root: File, relative: String): File {
        val target = File(root, relative.replace('\\', '/'))
        val rootPath = root.canonicalFile.path + File.separator
        require(target.canonicalFile.path.startsWith(rootPath)) { "封面引用包含不安全路径" }
        return target
    }

    private companion object {
        const val COVERS_DIRECTORY = "covers"
        const val RESTORED_PREFIX = "restored_"
        const val COVER_MARKER = "animeow-cover://"
        const val MIN_AGE_MS = 15L * 60 * 1000
        const val TAG = "OrphanCoverCleaner"
    }
}
