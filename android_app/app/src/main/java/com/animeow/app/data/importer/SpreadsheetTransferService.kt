package com.animeow.app.data.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.animeow.app.data.normalizeCompletionProgress
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AnimeTagEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.WatchStatusEntity
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

enum class SpreadsheetField(
    val displayName: String,
    val required: Boolean = false,
    val aliases: Set<String>,
) {
    TITLE("番剧标题", true, setOf("番剧标题", "标题", "名称", "title", "name")),
    STATUS("状态", aliases = setOf("状态", "观看状态", "status")),
    WATCHED("已看集数", aliases = setOf("已看集数", "已看", "进度", "watched", "progress")),
    TOTAL("总集数", aliases = setOf("总集数", "集数", "total", "episodes")),
    REVIEW("评价/备注", aliases = setOf("评价/备注", "评价", "备注", "评论", "review", "comment")),
    TAGS("标签", aliases = setOf("标签", "分类", "tags", "tag")),
    STUDIO("制作公司", aliases = setOf("制作公司", "动画制作", "公司", "studio")),
}

enum class SpreadsheetConflictStrategy(
    val displayName: String,
    val description: String,
) {
    MERGE("安全合并", "保留封面和在线 ID，采用更完整的进度并追加标签与备注"),
    REPLACE("覆盖表格字段", "只覆盖表格中映射的字段，其他本地资料保持不变"),
    SKIP("跳过同名", "只新增本地不存在的作品"),
}

data class SpreadsheetPreview(
    val sourceName: String,
    val headers: List<String>,
    val rows: List<List<String>>,
    val suggestedMapping: Map<SpreadsheetField, Int>,
) {
    val dataRowCount: Int get() = rows.count { row -> row.any(String::isNotBlank) }
}

data class SpreadsheetImportSummary(
    val addedCount: Int,
    val mergedCount: Int,
    val replacedCount: Int,
    val skippedCount: Int,
    val errorCount: Int,
    val createdTagCount: Int,
)

data class SpreadsheetExportSummary(
    val animeCount: Int,
    val tagLinkCount: Int,
)

internal data class SpreadsheetAnimePatch(
    val mappedFields: Set<SpreadsheetField>,
    val status: String = "未看",
    val watchedEpisodes: Int = 0,
    val totalEpisodes: Int = 0,
    val review: String? = null,
    val studio: String? = null,
)

private data class ParsedSpreadsheetRow(
    val title: String,
    val patch: SpreadsheetAnimePatch,
    val tags: List<String>?,
)

class SpreadsheetTransferService(
    context: Context,
    private val database: AniMeowDatabase,
) {
    private val appContext = context.applicationContext

    suspend fun inspect(uri: Uri): SpreadsheetPreview {
        val sourceName = appContext.contentResolver.displayName(uri) ?: "导入表格"
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { input -> readLimited(input) }
            ?: error("无法读取所选表格")
        val rows = when {
            bytes.hasZipSignature() -> parseXlsx(bytes)
            bytes.hasOleSignature() -> error("旧版 .xls 会显著增加安装包体积，请先在表格软件中另存为 .xlsx 或 .csv")
            else -> parseCsv(decodeDelimitedText(bytes).removePrefix("\uFEFF"))
        }
        require(rows.isNotEmpty()) { "表格中没有可读取的工作表" }
        val headers = rows.first().map { it.trim().ifBlank { "未命名列" } }
        require(headers.isNotEmpty()) { "表格缺少表头" }
        val dataRows = rows.drop(1).filter { row -> row.any(String::isNotBlank) }
        require(dataRows.isNotEmpty()) { "表格中没有数据行" }
        return SpreadsheetPreview(
            sourceName = sourceName,
            headers = headers,
            rows = dataRows,
            suggestedMapping = suggestMapping(headers),
        )
    }

    suspend fun importRows(
        preview: SpreadsheetPreview,
        mapping: Map<SpreadsheetField, Int>,
        strategy: SpreadsheetConflictStrategy,
    ): SpreadsheetImportSummary {
        require(mapping.containsKey(SpreadsheetField.TITLE)) { "必须指定番剧标题列" }
        require(mapping.values.all { it in preview.headers.indices }) { "列映射已失效，请重新选择表格" }
        val parsedRows = preview.rows.mapNotNull { row ->
            runCatching { parseSpreadsheetRow(row, mapping) }.getOrNull()
        }
        val parseErrorCount = preview.rows.size - parsedRows.size
        return database.withTransaction {
            val dao = database.libraryDao()
            val activeByTitle = dao.getActiveAnimes().associateBy { normalizedTitle(it.title) }.toMutableMap()
            val tagsByName = dao.getTags().associateBy { it.name.trim().lowercase(java.util.Locale.ROOT) }.toMutableMap()
            val statusesByName = dao.getWatchStatuses().associateBy { it.name }.toMutableMap()
            var nextStatusOrder = statusesByName.values.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
            var added = 0
            var merged = 0
            var replaced = 0
            var skipped = 0
            var createdTags = 0
            val now = Instant.now().toString()

            suspend fun ensureStatus(status: String) {
                if (status in statusesByName) return
                val entity = WatchStatusEntity(
                    name = status,
                    color = DEFAULT_STATUS_COLOR,
                    sortOrder = nextStatusOrder++,
                )
                val id = dao.insertWatchStatus(entity)
                statusesByName[status] = entity.copy(id = id)
            }

            parsedRows.forEach { parsed ->
                val key = normalizedTitle(parsed.title)
                val existing = activeByTitle[key]
                val animeId = when {
                    existing == null -> {
                        val newAnime = newAnimeFromSpreadsheet(parsed.title, parsed.patch, now)
                        ensureStatus(newAnime.status)
                        val id = dao.insertAnime(newAnime)
                        activeByTitle[key] = newAnime.copy(id = id)
                        added += 1
                        id
                    }

                    strategy == SpreadsheetConflictStrategy.SKIP -> {
                        skipped += 1
                        null
                    }

                    strategy == SpreadsheetConflictStrategy.REPLACE -> {
                        val updated = replaceAnimeFromSpreadsheet(existing, parsed.title, parsed.patch)
                        ensureStatus(updated.status)
                        dao.updateAnime(updated)
                        activeByTitle[key] = updated
                        replaced += 1
                        existing.id
                    }

                    else -> {
                        val updated = mergeAnimeFromSpreadsheet(existing, parsed.patch)
                        ensureStatus(updated.status)
                        dao.updateAnime(updated)
                        activeByTitle[key] = updated
                        merged += 1
                        existing.id
                    }
                }

                if (animeId != null && parsed.tags != null) {
                    if (strategy == SpreadsheetConflictStrategy.REPLACE && existing != null) {
                        dao.clearAnimeTagsForAnime(animeId)
                    }
                    val tagIds = parsed.tags.map { name ->
                        val keyName = name.lowercase(java.util.Locale.ROOT)
                        tagsByName[keyName]?.id ?: dao.insertTag(TagEntity(name = name)).also { id ->
                            tagsByName[keyName] = TagEntity(id = id, name = name)
                            createdTags += 1
                        }
                    }.distinct()
                    if (tagIds.isNotEmpty()) {
                        dao.insertAnimeTags(tagIds.map { tagId -> AnimeTagEntity(animeId, tagId) })
                    }
                }
            }

            SpreadsheetImportSummary(
                addedCount = added,
                mergedCount = merged,
                replacedCount = replaced,
                skippedCount = skipped,
                errorCount = parseErrorCount,
                createdTagCount = createdTags,
            )
        }
    }

    suspend fun export(uri: Uri): SpreadsheetExportSummary {
        val dao = database.libraryDao()
        val animes = dao.getActiveAnimes()
        require(animes.isNotEmpty()) { "资料库中没有可导出的作品" }
        val tags = dao.getTags().associateBy(TagEntity::id)
        val tagLinks = dao.getAnimeTags().groupBy(AnimeTagEntity::animeId)
        val rows = buildList {
            add(EXPORT_HEADERS)
            animes.forEach { anime ->
                add(
                    listOf(
                        anime.title,
                        anime.status,
                        anime.watchedEpisodes.toString(),
                        anime.totalEpisodes.toString(),
                        anime.review.orEmpty(),
                        tagLinks[anime.id].orEmpty().mapNotNull { tags[it.tagId]?.name }.joinToString(","),
                        anime.studio.orEmpty(),
                    ),
                )
            }
        }
        appContext.contentResolver.openOutputStream(uri, "w")?.use { output ->
            ZipOutputStream(BufferedOutputStream(output)).use { zip -> writeXlsx(zip, rows) }
        } ?: error("无法写入所选位置")
        return SpreadsheetExportSummary(animeCount = animes.size, tagLinkCount = tagLinks.values.sumOf { it.size })
    }

    private fun suggestMapping(headers: List<String>): Map<SpreadsheetField, Int> = buildMap {
        SpreadsheetField.entries.forEach { field ->
            val index = headers.indexOfFirst { header ->
                val cleanHeader = header.trim().lowercase(java.util.Locale.ROOT)
                field.aliases.any { alias ->
                    cleanHeader == alias.lowercase(java.util.Locale.ROOT) ||
                        cleanHeader.contains(alias.lowercase(java.util.Locale.ROOT))
                }
            }
            if (index >= 0) put(field, index)
        }
    }

    private fun parseXlsx(bytes: ByteArray): List<List<String>> {
        var sharedStringsBytes: ByteArray? = null
        var firstWorksheetBytes: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                count += 1
                require(count <= MAX_ZIP_ENTRIES) { "表格压缩包条目过多" }
                if (!entry.isDirectory) {
                    when {
                        entry.name == "xl/sharedStrings.xml" && sharedStringsBytes == null ->
                            sharedStringsBytes = readLimited(zip, MAX_XML_BYTES)

                        firstWorksheetBytes == null && entry.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) ->
                            firstWorksheetBytes = readLimited(zip, MAX_XML_BYTES)
                    }
                }
                zip.closeEntry()
            }
        }
        val sharedStrings = sharedStringsBytes?.toString(Charsets.UTF_8)?.let(::parseSharedStrings).orEmpty()
        val sheet = firstWorksheetBytes?.toString(Charsets.UTF_8) ?: error("未找到工作表")
        return parseWorksheet(sheet, sharedStrings)
    }

    private fun parseSharedStrings(xml: String): List<String> {
        val parser = xmlParser(xml)
        val values = mutableListOf<String>()
        var insideItem = false
        var insideText = false
        var current = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { insideItem = true; current = StringBuilder() }
                    "t" -> if (insideItem) insideText = true
                }
                XmlPullParser.TEXT -> if (insideItem && insideText) current.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> insideText = false
                    "si" -> { values += current.toString(); insideItem = false }
                }
            }
            parser.next()
        }
        return values
    }

    private fun parseWorksheet(xml: String, sharedStrings: List<String>): List<List<String>> {
        val parser = xmlParser(xml)
        val rows = mutableListOf<List<String>>()
        var cells = mutableMapOf<Int, String>()
        var cellIndex = 0
        var cellType = ""
        var readingValue = false
        var value = StringBuilder()
        var inCell = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> cells = mutableMapOf()
                    "c" -> {
                        inCell = true
                        val reference = parser.getAttributeValue(null, "r").orEmpty()
                        cellIndex = reference.takeWhile(Char::isLetter).columnIndex().takeIf { it >= 0 }
                            ?: (cells.keys.maxOrNull()?.plus(1) ?: 0)
                        cellType = parser.getAttributeValue(null, "t").orEmpty()
                        value = StringBuilder()
                    }
                    "v", "t" -> if (inCell) readingValue = true
                }
                XmlPullParser.TEXT -> if (readingValue) value.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> readingValue = false
                    "c" -> {
                        val raw = value.toString()
                        cells[cellIndex] = if (cellType == "s") {
                            sharedStrings.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                        } else raw
                        inCell = false
                    }
                    "row" -> {
                        val width = (cells.keys.maxOrNull() ?: -1) + 1
                        rows += List(width) { index -> cells[index].orEmpty() }
                    }
                }
            }
            parser.next()
        }
        return rows
    }

    private fun parseCsv(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        val delimiter = detectDelimiter(csv)
        var quoted = false
        var index = 0
        while (index < csv.length) {
            val char = csv[index]
            when {
                char == '"' && quoted && index + 1 < csv.length && csv[index + 1] == '"' -> {
                    cell.append('"'); index += 1
                }
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> {
                    require(row.size < MAX_COLUMNS) { "表格列数过多" }
                    row += cell.toString()
                    cell.clear()
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < csv.length && csv[index + 1] == '\n') index += 1
                    row += cell.toString(); cell.clear()
                    if (row.any(String::isNotEmpty)) {
                        require(rows.size < MAX_CSV_ROWS) { "表格行数过多" }
                        rows += row.toList()
                    }
                    row.clear()
                }
                else -> {
                    require(cell.length < MAX_CELL_CHARS) { "表格单元格内容过长" }
                    cell.append(char)
                }
            }
            index += 1
        }
        require(!quoted) { "CSV 中存在未闭合的引号" }
        row += cell.toString()
        if (row.any(String::isNotEmpty)) rows += row
        return rows
    }

    private fun decodeDelimitedText(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        Charset.forName("GB18030").decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun detectDelimiter(csv: String): Char {
        val counts = linkedMapOf(',' to 0, '\t' to 0, ';' to 0)
        var quoted = false
        var index = 0
        while (index < csv.length) {
            val char = csv[index]
            if (char == '"') {
                if (quoted && index + 1 < csv.length && csv[index + 1] == '"') index += 1
                else quoted = !quoted
            } else if (!quoted && (char == '\r' || char == '\n')) {
                break
            } else if (!quoted && char in counts) {
                counts[char] = counts.getValue(char) + 1
            }
            index += 1
        }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    private fun writeXlsx(zip: ZipOutputStream, rows: List<List<String>>) {
        zip.writeText("[Content_Types].xml", CONTENT_TYPES)
        zip.writeText("_rels/.rels", ROOT_RELATIONSHIPS)
        zip.writeText("xl/workbook.xml", WORKBOOK)
        zip.writeText("xl/_rels/workbook.xml.rels", WORKBOOK_RELATIONSHIPS)
        zip.writeText("xl/styles.xml", STYLES)
        val sheet = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            rows.forEachIndexed { rowIndex, row ->
                append("<row r=\"").append(rowIndex + 1).append("\">")
                row.forEachIndexed { columnIndex, value ->
                    val reference = columnName(columnIndex) + (rowIndex + 1)
                    val numeric = rowIndex > 0 && columnIndex in 2..3 && value.toIntOrNull() != null
                    if (numeric) {
                        append("<c r=\"").append(reference).append("\"><v>").append(value).append("</v></c>")
                    } else {
                        append("<c r=\"").append(reference).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        append(xmlEscape(value)).append("</t></is></c>")
                    }
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
        zip.writeText("xl/worksheets/sheet1.xml", sheet)
    }

    private fun readLimited(input: java.io.InputStream, limit: Int = MAX_FILE_BYTES): ByteArray {
        val buffer = ByteArray(16 * 1024)
        val output = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "表格文件过大" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun xmlParser(xml: String): XmlPullParser = XmlPullParserFactory.newInstance().newPullParser().apply {
        setInput(StringReader(xml))
    }

    private fun android.content.ContentResolver.displayName(uri: Uri): String? =
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.getString(0)
        }

    private fun ByteArray.hasZipSignature(): Boolean = size >= 4 && this[0] == 0x50.toByte() && this[1] == 0x4B.toByte()

    private fun ByteArray.hasOleSignature(): Boolean = size >= 8 && copyOfRange(0, 8).contentEquals(
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()),
    )

    private fun String.columnIndex(): Int {
        if (isEmpty()) return -1
        var result = 0
        uppercase().forEach { result = result * 26 + (it - 'A' + 1) }
        return result - 1
    }

    private fun columnName(index: Int): String {
        var value = index + 1
        val result = StringBuilder()
        while (value > 0) {
            val remainder = (value - 1) % 26
            result.append(('A'.code + remainder).toChar())
            value = (value - 1) / 26
        }
        return result.reverse().toString()
    }

    private fun ZipOutputStream.writeText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private companion object {
        const val MAX_FILE_BYTES = 25 * 1024 * 1024
        const val MAX_XML_BYTES = 20 * 1024 * 1024
        const val MAX_ZIP_ENTRIES = 200
        const val MAX_CSV_ROWS = 100_000
        const val MAX_COLUMNS = 512
        const val MAX_CELL_CHARS = 100_000
        const val DEFAULT_STATUS_COLOR = 0xFF9E9E9E
        val EXPORT_HEADERS = listOf("番剧标题", "状态", "已看集数", "总集数", "评价/备注", "标签", "制作公司")
        const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""
        const val ROOT_RELATIONSHIPS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
        const val WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="AniMeow" sheetId="1" r:id="rId1"/></sheets></workbook>"""
        const val WORKBOOK_RELATIONSHIPS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
        const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="1"><font><sz val="11"/><name val="Arial"/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="1"><xf xfId="0"/></cellXfs></styleSheet>"""
    }
}

private fun parseSpreadsheetRow(
    row: List<String>,
    mapping: Map<SpreadsheetField, Int>,
): ParsedSpreadsheetRow {
    val title = row.value(mapping, SpreadsheetField.TITLE).trim()
    require(title.isNotEmpty()) { "标题为空" }
    val mappedFields = mapping.keys.toSet()
    val patch = SpreadsheetAnimePatch(
        mappedFields = mappedFields,
        status = if (SpreadsheetField.STATUS in mappedFields) {
            normalizeSpreadsheetStatus(row.value(mapping, SpreadsheetField.STATUS))
        } else {
            "未看"
        },
        watchedEpisodes = row.mappedSpreadsheetInt(mapping, SpreadsheetField.WATCHED),
        totalEpisodes = row.mappedSpreadsheetInt(mapping, SpreadsheetField.TOTAL),
        review = row.value(mapping, SpreadsheetField.REVIEW).trim().takeIf(String::isNotEmpty),
        studio = row.value(mapping, SpreadsheetField.STUDIO).trim().takeIf(String::isNotEmpty),
    )
    return ParsedSpreadsheetRow(
        title = title,
        patch = patch,
        tags = if (SpreadsheetField.TAGS in mappedFields) {
            parseTags(row.value(mapping, SpreadsheetField.TAGS))
        } else {
            null
        },
    )
}

internal fun newAnimeFromSpreadsheet(
    title: String,
    patch: SpreadsheetAnimePatch,
    createdAt: String,
): AnimeEntity {
    val total = patch.totalEpisodes.takeIf { SpreadsheetField.TOTAL in patch.mappedFields } ?: 0
    val watched = patch.watchedEpisodes
        .takeIf { SpreadsheetField.WATCHED in patch.mappedFields }
        .orZero()
        .let { if (total > 0) it.coerceAtMost(total) else it }
    return normalizeCompletionProgress(AnimeEntity(
        title = title,
        status = patch.status.takeIf { SpreadsheetField.STATUS in patch.mappedFields } ?: "未看",
        watchedEpisodes = watched,
        totalEpisodes = total,
        tvEpisodes = total,
        review = patch.review.takeIf { SpreadsheetField.REVIEW in patch.mappedFields },
        studio = patch.studio.takeIf { SpreadsheetField.STUDIO in patch.mappedFields },
        createdAt = createdAt,
    ))
}

internal fun replaceAnimeFromSpreadsheet(
    existing: AnimeEntity,
    title: String,
    patch: SpreadsheetAnimePatch,
): AnimeEntity {
    val total = if (SpreadsheetField.TOTAL in patch.mappedFields) patch.totalEpisodes else existing.totalEpisodes
    val watched = if (SpreadsheetField.WATCHED in patch.mappedFields) {
        if (total > 0) patch.watchedEpisodes.coerceAtMost(total) else patch.watchedEpisodes
    } else {
        if (SpreadsheetField.TOTAL in patch.mappedFields && total > 0) {
            existing.watchedEpisodes.coerceAtMost(total)
        } else {
            existing.watchedEpisodes
        }
    }
    return normalizeCompletionProgress(existing.copy(
        title = title,
        status = patch.status.takeIf { SpreadsheetField.STATUS in patch.mappedFields } ?: existing.status,
        watchedEpisodes = watched,
        totalEpisodes = total,
        tvEpisodes = patch.totalEpisodes.takeIf { SpreadsheetField.TOTAL in patch.mappedFields } ?: existing.tvEpisodes,
        review = if (SpreadsheetField.REVIEW in patch.mappedFields) patch.review else existing.review,
        studio = if (SpreadsheetField.STUDIO in patch.mappedFields) patch.studio else existing.studio,
    ))
}

internal fun mergeAnimeFromSpreadsheet(
    existing: AnimeEntity,
    patch: SpreadsheetAnimePatch,
): AnimeEntity {
    val total = if (SpreadsheetField.TOTAL in patch.mappedFields) {
        max(existing.totalEpisodes, patch.totalEpisodes)
    } else {
        existing.totalEpisodes
    }
    val importedWatched = if (SpreadsheetField.WATCHED in patch.mappedFields && total > 0) {
        patch.watchedEpisodes.coerceAtMost(total)
    } else {
        patch.watchedEpisodes
    }
    val watched = if (SpreadsheetField.WATCHED in patch.mappedFields) {
        max(existing.watchedEpisodes, importedWatched)
    } else {
        existing.watchedEpisodes
    }
    val status = when {
        SpreadsheetField.STATUS !in patch.mappedFields -> existing.status
        SpreadsheetField.WATCHED in patch.mappedFields && importedWatched > existing.watchedEpisodes -> patch.status
        SpreadsheetField.WATCHED in patch.mappedFields && importedWatched < existing.watchedEpisodes -> existing.status
        spreadsheetStatusPriority(patch.status) > spreadsheetStatusPriority(existing.status) -> patch.status
        else -> existing.status
    }
    return normalizeCompletionProgress(existing.copy(
        status = status,
        watchedEpisodes = watched,
        totalEpisodes = total,
        tvEpisodes = if (SpreadsheetField.TOTAL in patch.mappedFields) {
            max(existing.tvEpisodes, patch.totalEpisodes)
        } else {
            existing.tvEpisodes
        },
        review = if (SpreadsheetField.REVIEW in patch.mappedFields) {
            mergeText(existing.review, patch.review)
        } else {
            existing.review
        },
        studio = if (SpreadsheetField.STUDIO in patch.mappedFields) {
            existing.studio?.takeIf(String::isNotBlank) ?: patch.studio
        } else {
            existing.studio
        },
    ))
}

private fun List<String>.value(mapping: Map<SpreadsheetField, Int>, field: SpreadsheetField): String =
    mapping[field]?.let(::getOrNull).orEmpty()

private fun List<String>.mappedSpreadsheetInt(
    mapping: Map<SpreadsheetField, Int>,
    field: SpreadsheetField,
): Int {
    if (field !in mapping) return 0
    val raw = value(mapping, field).trim()
    if (raw.isEmpty()) return 0
    val number = raw.toDoubleOrNull()
    require(number != null && number.isFinite() && number >= 0.0 && number <= Int.MAX_VALUE.toDouble()) {
        "${field.displayName}不是有效的非负数字"
    }
    return number.toInt()
}

private fun normalizeSpreadsheetStatus(value: String): String {
    val clean = value.trim()
    return when {
        clean.isEmpty() -> "未看"
        clean.contains("完成") || clean.contains("看完") || clean.contains("finished", true) -> "看完"
        clean.contains("在看") || clean.contains("追") || clean.contains("watching", true) -> "在看"
        clean.contains("弃") || clean.contains("drop", true) -> "弃坑"
        clean.contains("想") || clean.contains("未") || clean.contains("plan", true) -> "未看"
        else -> clean.take(24)
    }
}

private fun spreadsheetStatusPriority(status: String): Int = when (status) {
    "看完" -> 5
    "在看" -> 4
    "弃坑" -> 3
    "想看" -> 2
    "未看" -> 1
    else -> 0
}

private fun parseTags(value: String): List<String> = value
    .split(Regex("[,，/、;；\\s]+"))
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinctBy { it.lowercase(java.util.Locale.ROOT) }

private fun normalizedTitle(value: String): String = value.trim()
    .lowercase(java.util.Locale.ROOT)
    .replace(Regex("[\\s　]+"), "")

private fun mergeText(existing: String?, imported: String?): String? {
    val current = existing?.trim().orEmpty()
    val incoming = imported?.trim().orEmpty()
    return when {
        current.isEmpty() -> incoming.takeIf(String::isNotEmpty)
        incoming.isEmpty() || current.contains(incoming) -> current
        else -> "$current\n$incoming"
    }
}

private fun Int?.orZero(): Int = this ?: 0
