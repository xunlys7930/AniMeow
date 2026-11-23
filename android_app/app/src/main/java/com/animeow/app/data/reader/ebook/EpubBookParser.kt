package com.animeow.app.data.reader.ebook

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.StringReader
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

object EpubBookParser {
    private const val MAX_XML_BYTES = 8 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
    )

    fun parseCatalog(
        epubFile: File,
        fallbackTitle: String,
    ): ParsedEbookCatalog {
        ZipFile(epubFile).use { zip ->
            validateZipSafety(zip)
            val opfPath = findOpfPath(zip)
                ?: throw IllegalArgumentException("无法在该 EPUB 中定位作品清单（container.xml 缺失或损坏）")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opfXml = readEntryText(zip, opfPath)
                ?: throw IllegalArgumentException("无法读取 EPUB 作品清单（$opfPath）")

            var title = ""
            var author = ""
            var description = ""
            var coverMetaId = ""
            var tocId = ""
            val manifest = LinkedHashMap<String, ManifestItem>()
            val spineIds = ArrayList<String>()

            val parser = newXmlParser(sanitizeXmlEntities(opfXml))
            var currentTag = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.localTagName()
                        currentTag = name
                        when (name) {
                            "meta" -> {
                                val metaName = parser.attr("name")?.lowercase(Locale.ROOT)
                                val content = parser.attr("content")
                                if (metaName == "cover" && !content.isNullOrBlank()) {
                                    coverMetaId = content.trim()
                                }
                            }
                            "item" -> {
                                val id = parser.attr("id")?.trim().orEmpty()
                                val href = parser.attr("href")?.trim().orEmpty()
                                val mediaType = parser.attr("media-type")?.trim().orEmpty()
                                val properties = parser.attr("properties")?.trim().orEmpty()
                                if (id.isNotEmpty() && href.isNotEmpty()) {
                                    manifest[id] = ManifestItem(
                                        id = id,
                                        href = resolveRelativePath(opfDir, decodeHrefPath(href)),
                                        mediaType = mediaType,
                                        properties = properties,
                                    )
                                }
                            }
                            "spine" -> {
                                tocId = parser.attr("toc")?.trim().orEmpty()
                            }
                            "itemref" -> {
                                val idref = parser.attr("idref")?.trim().orEmpty()
                                val linear = parser.attr("linear")?.trim()?.lowercase(Locale.ROOT)
                                if (idref.isNotEmpty() && linear != "no") {
                                    spineIds += idref
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            when (currentTag) {
                                "title" -> if (title.isEmpty()) title = text
                                "creator" -> if (author.isEmpty()) author = text
                                "description" -> if (description.isEmpty()) description = stripHtmlTags(text)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> currentTag = ""
                }
                event = parser.next()
            }

            // 解析目录标题映射（支持 EPUB3 nav 与 EPUB2 toc.ncx）
            val tocTitleByPath = LinkedHashMap<String, String>()
            val navItem = manifest.values.firstOrNull { item ->
                item.properties.split(Regex("\\s+")).any { it.equals("nav", ignoreCase = true) }
            }
            if (navItem != null) {
                readEntryText(zip, navItem.href)?.let { xml ->
                    parseNavTitles(xml, navItem.href.substringBeforeLast('/', ""), tocTitleByPath)
                }
            }
            val ncxItem = (if (tocId.isNotEmpty()) manifest[tocId] else null)
                ?: manifest.values.firstOrNull {
                    it.mediaType.contains("dtbncx", ignoreCase = true) || it.href.lowercase(Locale.ROOT).endsWith(".ncx")
                }
            if (ncxItem != null) {
                readEntryText(zip, ncxItem.href)?.let { xml ->
                    parseNcxTitles(xml, ncxItem.href.substringBeforeLast('/', ""), tocTitleByPath)
                }
            }

            // 构建线性阅读章节表
            val spineItems = if (spineIds.isNotEmpty()) {
                spineIds.mapNotNull(manifest::get)
            } else {
                manifest.values.filter {
                    it.mediaType.contains("html", ignoreCase = true) &&
                        !it.properties.contains("nav", ignoreCase = true)
                }
            }
            require(spineItems.isNotEmpty()) { "该 EPUB 未包含可阅读的正文章节" }

            val chapters = spineItems.mapIndexed { idx, item ->
                val rawPath = item.href.substringBefore('#')
                val mappedTitle = tocTitleByPath[rawPath]
                    ?: sniffChapterTitle(zip, rawPath)
                    ?: "第 ${idx + 1} 节"
                EbookChapterRef(
                    index = idx,
                    title = mappedTitle,
                    entryPath = rawPath,
                )
            }

            // 提取内封封面（若有）
            val coverItem = manifest.values.firstOrNull {
                it.properties.split(Regex("\\s+")).any { p -> p.equals("cover-image", ignoreCase = true) }
            } ?: coverMetaId.takeIf(String::isNotEmpty)?.let(manifest::get)
                ?: manifest.values.firstOrNull {
                    it.mediaType.startsWith("image/", ignoreCase = true) &&
                        (it.id.contains("cover", ignoreCase = true) || it.href.contains("cover", ignoreCase = true))
                }
            val coverBytes = coverItem?.let { item ->
                findEntry(zip, item.href)?.let { entry ->
                    readEntryBytes(zip, entry, MAX_IMAGE_BYTES)
                }
            }
            val coverExt = coverItem?.href?.substringAfterLast('.', "jpg")?.lowercase(Locale.ROOT)
                ?.takeIf { it in setOf("jpg", "jpeg", "png", "webp") }

            return ParsedEbookCatalog(
                format = EbookFormat.EPUB,
                title = title.ifBlank { fallbackTitle.trim().ifEmpty { "未命名电子书" } },
                author = author.takeIf(String::isNotBlank),
                synopsis = description.takeIf(String::isNotBlank),
                coverBytes = coverBytes,
                coverExtension = coverExt,
                chapters = chapters,
                workingFile = epubFile,
            )
        }
    }

    fun loadChapterBlocks(
        epubFile: File,
        fingerprint: String,
        chapter: EbookChapterRef,
        cacheDir: File,
    ): List<EbookBlock> {
        ZipFile(epubFile).use { zip ->
            val html = readEntryText(zip, chapter.entryPath)
                ?: return listOf(EbookBlock.Paragraph("无法读取章节内容（${chapter.title}）"))
            val chapterDir = chapter.entryPath.substringBeforeLast('/', "")
            val imageRoot = File(cacheDir, "ebook_cache/images/$fingerprint").apply { mkdirs() }
            val parsed = runCatching {
                parseXhtmlBlocksStrict(zip, html, chapterDir, imageRoot)
            }.getOrElse {
                parseXhtmlBlocksTolerant(zip, html, chapterDir, imageRoot)
            }
            return parsed.ifEmpty {
                listOf(EbookBlock.Paragraph(chapter.title.ifBlank { "本章无文字内容" }, isHeading = true))
            }
        }
    }

    private fun parseXhtmlBlocksStrict(
        zip: ZipFile,
        rawHtml: String,
        chapterDir: String,
        imageRoot: File,
    ): List<EbookBlock> {
        val blocks = ArrayList<EbookBlock>()
        val parser = newXmlParser(sanitizeXmlEntities(rawHtml))
        val textBuffer = StringBuilder()
        var ignoredDepth = 0
        var inBody = false
        var currentHeadingDepth = 0

        fun flushParagraph() {
            val cleaned = normalizeParagraphText(textBuffer.toString())
            textBuffer.setLength(0)
            if (cleaned.isNotEmpty()) {
                blocks += EbookBlock.Paragraph(text = cleaned, isHeading = currentHeadingDepth > 0)
            }
        }

        val blockTags = setOf(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
            "li", "blockquote", "section", "article", "tr", "dt", "dd",
        )
        val headingTags = setOf("h1", "h2", "h3", "h4", "h5", "h6")
        val ignoredTags = setOf("head", "script", "style", "title", "rt", "rp")

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.localTagName()
                    if (tag == "body") inBody = true
                    if (tag in ignoredTags) {
                        ignoredDepth++
                    } else if (ignoredDepth == 0) {
                        if (tag in blockTags) flushParagraph()
                        if (tag in headingTags) currentHeadingDepth++
                        if (tag == "br") textBuffer.append('\n')
                        if (tag == "img" || tag == "image") {
                            flushParagraph()
                            val rawSrc = parser.attr("src")
                                ?: parser.attr("href")
                                ?: parser.attr("xlink:href")
                            val alt = parser.attr("alt")?.trim()?.takeIf(String::isNotEmpty)
                            extractIllustrationBlock(zip, chapterDir, rawSrc, alt, imageRoot)?.let(blocks::add)
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (ignoredDepth == 0 && (inBody || blocks.isEmpty())) {
                        val t = parser.text
                        if (!t.isNullOrEmpty()) textBuffer.append(t)
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.localTagName()
                    if (tag in ignoredTags && ignoredDepth > 0) {
                        ignoredDepth--
                    } else if (ignoredDepth == 0) {
                        if (tag in blockTags) flushParagraph()
                        if (tag in headingTags && currentHeadingDepth > 0) currentHeadingDepth--
                    }
                }
            }
            event = parser.next()
        }
        flushParagraph()
        return blocks
    }

    /**
     * 容错回退解析：应对部分民间制作 EPUB 存在未闭合 `<br>` / `<img ...>` 标签导致严格 XML 解析中断的情况。
     */
    private fun parseXhtmlBlocksTolerant(
        zip: ZipFile,
        rawHtml: String,
        chapterDir: String,
        imageRoot: File,
    ): List<EbookBlock> {
        val bodyContent = Regex("<body[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
            .find(rawHtml)?.groupValues?.get(1) ?: rawHtml
        val withoutScripts = bodyContent
            .replace(Regex("<(script|style|head|rt|rp)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
        val blocks = ArrayList<EbookBlock>()
        val tokenRegex = Regex(
            "<(img|image)\\b([^>]*?)>|<(/?(?:p|div|h[1-6]|li|blockquote|br))\\b[^>]*>",
            RegexOption.IGNORE_CASE,
        )
        var cursor = 0
        val paragraphBuf = StringBuilder()
        var isHeading = false

        fun flushText() {
            val text = normalizeParagraphText(decodeHtmlEntities(paragraphBuf.toString().replace(Regex("<[^>]+>"), "")))
            paragraphBuf.setLength(0)
            if (text.isNotEmpty()) {
                blocks += EbookBlock.Paragraph(text = text, isHeading = isHeading)
            }
            isHeading = false
        }

        for (match in tokenRegex.findAll(withoutScripts)) {
            if (match.range.first > cursor) {
                paragraphBuf.append(withoutScripts.substring(cursor, match.range.first))
            }
            cursor = match.range.last + 1
            val imgTag = match.groupValues[1]
            if (imgTag.isNotEmpty()) {
                flushText()
                val attrs = match.groupValues[2]
                val src = Regex("""(?:src|xlink:href|href)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1)
                val alt = Regex("""alt\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1)
                extractIllustrationBlock(zip, chapterDir, src, alt, imageRoot)?.let(blocks::add)
            } else {
                val blockTag = match.groupValues[3].lowercase(Locale.ROOT)
                if (blockTag == "br") {
                    paragraphBuf.append('\n')
                } else {
                    flushText()
                    if (blockTag.startsWith("h")) isHeading = true
                }
            }
        }
        if (cursor < withoutScripts.length) {
            paragraphBuf.append(withoutScripts.substring(cursor))
        }
        flushText()
        return blocks
    }

    private fun extractIllustrationBlock(
        zip: ZipFile,
        chapterDir: String,
        rawSrc: String?,
        altText: String?,
        imageRoot: File,
    ): EbookBlock.Illustration? {
        val trimmed = rawSrc?.trim()?.takeIf(String::isNotEmpty) ?: return null
        // 严禁发起外部网络图片请求，防止阅读器内嵌远程追踪像素
        if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true)
        ) {
            return null
        }
        val entryPath = resolveRelativePath(chapterDir, decodeHrefPath(trimmed.substringBefore('#')))
        if (entryPath.isEmpty()) return null
        val entry = findEntry(zip, entryPath) ?: return null
        val safeRelative = entryPath.replace(Regex("[^A-Za-z0-9._/-]"), "_").trimStart('/')
        val target = File(imageRoot, safeRelative)
        val canonicalRoot = imageRoot.canonicalFile.path + File.separator
        val canonicalTarget = target.canonicalFile
        if (!canonicalTarget.path.startsWith(canonicalRoot)) return null
        if (!canonicalTarget.isFile || canonicalTarget.length() == 0L) {
            val bytes = readEntryBytes(zip, entry, MAX_IMAGE_BYTES)
            if (bytes.isEmpty()) return null
            canonicalTarget.parentFile?.mkdirs()
            FileOutputStream(canonicalTarget).use { it.write(bytes) }
        }
        return EbookBlock.Illustration(cacheFilePath = canonicalTarget.absolutePath, altText = altText)
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val containerXml = readEntryText(zip, "META-INF/container.xml") ?: return null
        val parser = newXmlParser(sanitizeXmlEntities(containerXml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.localTagName() == "rootfile") {
                val path = parser.attr("full-path")?.trim()
                if (!path.isNullOrEmpty()) return normalizeZipPath(path)
            }
            event = parser.next()
        }
        return null
    }

    private fun parseNcxTitles(xml: String, ncxDir: String, out: MutableMap<String, String>) {
        runCatching {
            val parser = newXmlParser(sanitizeXmlEntities(xml))
            var inText = false
            var latestLabel = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.localTagName()) {
                        "navpoint" -> latestLabel = ""
                        "text" -> inText = true
                        "content" -> {
                            val src = parser.attr("src")?.trim().orEmpty()
                            if (src.isNotEmpty() && latestLabel.isNotEmpty()) {
                                val resolved = resolveRelativePath(ncxDir, decodeHrefPath(src.substringBefore('#')))
                                out.putIfAbsent(resolved, latestLabel)
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (inText) {
                        val t = parser.text?.trim().orEmpty()
                        if (t.isNotEmpty()) latestLabel = t
                    }
                    XmlPullParser.END_TAG -> if (parser.localTagName() == "text") inText = false
                }
                event = parser.next()
            }
        }
    }

    private fun parseNavTitles(xml: String, navDir: String, out: MutableMap<String, String>) {
        runCatching {
            val parser = newXmlParser(sanitizeXmlEntities(xml))
            var currentHref: String? = null
            val labelBuf = StringBuilder()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> if (parser.localTagName() == "a") {
                        currentHref = parser.attr("href")
                        labelBuf.setLength(0)
                    }
                    XmlPullParser.TEXT -> if (currentHref != null) {
                        labelBuf.append(parser.text.orEmpty())
                    }
                    XmlPullParser.END_TAG -> if (parser.localTagName() == "a") {
                        val href = currentHref?.trim().orEmpty()
                        val label = labelBuf.toString().replace(Regex("\\s+"), " ").trim()
                        if (href.isNotEmpty() && label.isNotEmpty() && !href.startsWith("#")) {
                            val resolved = resolveRelativePath(navDir, decodeHrefPath(href.substringBefore('#')))
                            out.putIfAbsent(resolved, label)
                        }
                        currentHref = null
                    }
                }
                event = parser.next()
            }
        }
    }

    private fun sniffChapterTitle(zip: ZipFile, entryPath: String): String? {
        val text = readEntryText(zip, entryPath, maxBytes = 64 * 1024) ?: return null
        val hMatch = Regex("<h[1-3][^>]*>([\\s\\S]{1,120}?)</h[1-3]>", RegexOption.IGNORE_CASE).find(text)
        if (hMatch != null) {
            val clean = stripHtmlTags(hMatch.groupValues[1])
            if (clean.isNotEmpty()) return clean
        }
        val titleMatch = Regex("<title[^>]*>([\\s\\S]{1,120}?)</title>", RegexOption.IGNORE_CASE).find(text)
        if (titleMatch != null) {
            val clean = stripHtmlTags(titleMatch.groupValues[1])
            if (clean.isNotEmpty() && !clean.equals("untitled", ignoreCase = true)) return clean
        }
        if (Regex("<(img|image)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "插图页"
        }
        return null
    }

    private fun validateZipSafety(zip: ZipFile) {
        val entries = zip.entries()
        var count = 0
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            count++
            require(count <= 20_000) { "EPUB 内文件数量异常过多，已停止解析" }
            val normalized = entry.name.replace('\\', '/')
            require(!normalized.startsWith("/") && !normalized.split('/').contains("..")) {
                "EPUB 压缩包包含不安全的路径穿越条目，已拒绝打开"
            }
        }
    }

    private fun findEntry(zip: ZipFile, path: String): ZipEntry? {
        val clean = normalizeZipPath(path)
        zip.getEntry(clean)?.let { return it }
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val candidate = entries.nextElement()
            if (candidate.name.replace('\\', '/').equals(clean, ignoreCase = true)) {
                return candidate
            }
        }
        return null
    }

    private fun readEntryText(zip: ZipFile, path: String, maxBytes: Int = MAX_XML_BYTES): String? {
        val entry = findEntry(zip, path) ?: return null
        val bytes = readEntryBytes(zip, entry, maxBytes)
        return bytes.toString(Charsets.UTF_8).trimStart('\uFEFF')
    }

    private fun readEntryBytes(zip: ZipFile, entry: ZipEntry, maxBytes: Int): ByteArray {
        zip.getInputStream(entry).use { input ->
            return readStreamLimited(input, maxBytes)
        }
    }

    private fun readStreamLimited(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            require(total <= maxBytes) { "EPUB 单章节或资源超过安全限制" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun resolveRelativePath(baseDir: String, relativeHref: String): String {
        val combined = if (baseDir.isEmpty() || relativeHref.startsWith("/")) {
            relativeHref.trimStart('/')
        } else {
            "$baseDir/$relativeHref"
        }
        return normalizeZipPath(combined)
    }

    private fun normalizeZipPath(rawPath: String): String {
        val segments = ArrayDeque<String>()
        for (part in rawPath.replace('\\', '/').split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> {
                    require(segments.isNotEmpty()) { "EPUB 内部引用越过根目录" }
                    segments.removeLast()
                }
                else -> segments.addLast(part)
            }
        }
        return segments.joinToString("/")
    }

    private fun decodeHrefPath(raw: String): String =
        runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

    private fun newXmlParser(xmlText: String): XmlPullParser =
        XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = false
        }.newPullParser().apply {
            setInput(StringReader(xmlText))
        }

    private fun XmlPullParser.localTagName(): String =
        name.orEmpty().substringAfter(':').lowercase(Locale.ROOT)

    private fun XmlPullParser.attr(targetName: String): String? {
        val targetLower = targetName.lowercase(Locale.ROOT)
        for (i in 0 until attributeCount) {
            val fullName = getAttributeName(i).orEmpty().lowercase(Locale.ROOT)
            val local = fullName.substringAfter(':')
            if (fullName == targetLower || local == targetLower) {
                return getAttributeValue(i)
            }
        }
        return null
    }

    /**
     * 将 HTML 常见命名实体转为 Unicode 字符，保留 XML 五大基础实体，避免 KXmlParser 因 `&nbsp;` 等抛错。
     */
    internal fun sanitizeXmlEntities(raw: String): String {
        val withoutDoctype = raw.replace(Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE), "")
        return ENTITY_REGEX.replace(withoutDoctype) { match ->
            val name = match.groupValues[1]
            when (name) {
                "lt", "gt", "amp", "apos", "quot" -> match.value
                else -> HTML_ENTITIES[name.lowercase(Locale.ROOT)] ?: ""
            }
        }
    }

    private fun decodeHtmlEntities(raw: String): String =
        ENTITY_REGEX.replace(raw) { match ->
            when (val name = match.groupValues[1].lowercase(Locale.ROOT)) {
                "lt" -> "<"
                "gt" -> ">"
                "amp" -> "&"
                "apos" -> "'"
                "quot" -> "\""
                else -> HTML_ENTITIES[name] ?: ""
            }
        }

    private fun stripHtmlTags(raw: String): String =
        decodeHtmlEntities(
            raw.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), ""),
        ).lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()

    private fun normalizeParagraphText(raw: String): String =
        raw.replace('\u00A0', ' ')
            .lines()
            .map { it.trim(' ', '\t', '\r', '\u3000') }
            .filter(String::isNotEmpty)
            .joinToString("\n")

    private val ENTITY_REGEX = Regex("&([A-Za-z][A-Za-z0-9]{1,15});")
    private val HTML_ENTITIES = mapOf(
        "nbsp" to "\u00A0",
        "ensp" to "\u2002",
        "emsp" to "\u2003",
        "thinsp" to "\u2009",
        "ldquo" to "“",
        "rdquo" to "”",
        "lsquo" to "‘",
        "rsquo" to "’",
        "mdash" to "—",
        "ndash" to "–",
        "hellip" to "…",
        "middot" to "·",
        "bull" to "•",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "times" to "×",
        "divide" to "÷",
        "laquo" to "«",
        "raquo" to "»",
    )
}
