package com.animeow.app.data.importer

internal object LegacyCoverPathMapper {
    fun rewrite(
        original: String?,
        exactPaths: Map<String, String>,
        uniqueBaseNames: Map<String, String>,
    ): String? {
        if (original.isNullOrBlank() || isPortableUri(original)) return original

        val keys = candidateKeys(original)
        keys.forEach { key ->
            exactPaths[key]?.let { return it }
        }
        keys.lastOrNull()?.let { baseName ->
            uniqueBaseNames[baseName]?.let { return it }
        }
        return original
    }

    fun normalizedRelativePath(path: String): String =
        path.replace('\\', '/').trimStart('/').lowercase(java.util.Locale.ROOT)

    private fun candidateKeys(original: String): List<String> {
        val normalized = original
            .removePrefix("file://")
            .replace('\\', '/')
            .trim()
        val lower = normalized.lowercase(java.util.Locale.ROOT)
        val markerIndex = lower.lastIndexOf("/covers/")
        val relative = when {
            markerIndex >= 0 -> normalized.substring(markerIndex + "/covers/".length)
            lower.startsWith("covers/") -> normalized.substring("covers/".length)
            else -> normalized.substringAfterLast('/')
        }.trimStart('/')
        val normalizedRelative = normalizedRelativePath(relative)
        val baseName = normalizedRelative.substringAfterLast('/')
        return listOf(normalizedRelative, baseName).filter { it.isNotBlank() }.distinct()
    }

    private fun isPortableUri(value: String): Boolean {
        val lower = value.lowercase(java.util.Locale.ROOT)
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("content://") ||
            lower.startsWith("android.resource://") ||
            lower.startsWith("data:")
    }
}
