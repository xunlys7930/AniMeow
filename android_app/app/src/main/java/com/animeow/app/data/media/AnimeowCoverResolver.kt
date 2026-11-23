package com.animeow.app.data.media

import android.content.Context
import coil.map.Mapper
import coil.request.Options
import java.io.File

internal const val ANIMEOW_COVER_MARKER = "animeow-cover://"

private val RESTORED_COVER_PREFIX_REGEX = Regex("^restored_[^/]+/")

/**
 * 去掉封面相对路径前导的 `restored_<id>/` 恢复批次前缀，
 * 避免跨端反复备份与恢复时路径无限嵌套并保持云端指纹稳定。
 */
internal fun stripRestoredCoverPrefixes(relativePath: String): String {
    var current = relativePath.replace('\\', '/').trimStart('/')
    while (true) {
        val stripped = current.replaceFirst(RESTORED_COVER_PREFIX_REGEX, "")
        if (stripped == current || stripped.isEmpty()) break
        current = stripped
    }
    return current
}

/** 判断封面地址是否为用户本机自定义封面（`file:` 或 `animeow-cover://` 或绝对文件路径）。 */
internal fun isLocalCustomCoverUrl(url: String?): Boolean {
    val trimmed = url?.trim().orEmpty()
    if (trimmed.isEmpty()) return false
    return trimmed.startsWith("file:", ignoreCase = true) ||
        trimmed.startsWith(ANIMEOW_COVER_MARKER) ||
        File(trimmed).isAbsolute
}

/**
 * 在本机 `covers/` 目录下解析 `animeow-cover://` 相对路径：
 * 1. 优先命中本次恢复解压目录 [preferredRoot]；
 * 2. 回退到现有 [coversRoot] 下的原路径或去前缀后的规范路径；
 * 3. 最后搜索现有 `restored_*` 目录中的同名规范路径。
 */
internal fun resolveExistingLocalCoverFile(
    coversRoot: File,
    markerRelativePath: String,
    preferredRoot: File? = null,
): File? {
    val normalized = markerRelativePath.replace('\\', '/').trimStart('/')
    if (normalized.isEmpty()) return null
    val canonicalRel = stripRestoredCoverPrefixes(normalized)

    fun safeExistingFile(root: File, relative: String): File? {
        if (!root.isDirectory || relative.isEmpty()) return null
        val target = File(root, relative)
        val rootPath = runCatching { root.canonicalFile.path + File.separator }.getOrNull() ?: return null
        val targetCanonical = runCatching { target.canonicalFile }.getOrNull() ?: return null
        return targetCanonical.takeIf { it.path.startsWith(rootPath) && it.isFile }
    }

    preferredRoot?.let { root ->
        safeExistingFile(root, normalized)?.let { return it }
        if (canonicalRel != normalized) safeExistingFile(root, canonicalRel)?.let { return it }
    }

    safeExistingFile(coversRoot, normalized)?.let { return it }
    if (canonicalRel != normalized) safeExistingFile(coversRoot, canonicalRel)?.let { return it }

    val restoredDirs = coversRoot.listFiles { file ->
        file.isDirectory && file.name.startsWith("restored_") && file != preferredRoot
    }?.sortedByDescending { it.lastModified() }.orEmpty()
    for (dir in restoredDirs) {
        safeExistingFile(dir, canonicalRel)?.let { return it }
        if (canonicalRel != normalized) safeExistingFile(dir, normalized)?.let { return it }
    }
    return null
}

/** 让 Coil 直接识别数据库里可能残留的 `animeow-cover://` 自定义封面标记。 */
internal class AnimeowCoverMapper(context: Context) : Mapper<String, File> {
    private val coversRoot = File(context.applicationContext.filesDir, "covers")

    override fun map(data: String, options: Options): File? {
        if (!data.startsWith(ANIMEOW_COVER_MARKER)) return null
        return resolveExistingLocalCoverFile(coversRoot, data.removePrefix(ANIMEOW_COVER_MARKER))
    }
}
