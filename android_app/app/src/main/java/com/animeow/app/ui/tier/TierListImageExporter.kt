package com.animeow.app.ui.tier

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.ui.theme.TierListStyle
import com.animeow.app.ui.theme.normalizeTierDisplayLabels
import com.animeow.app.util.runCatchingCancellable
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

internal object TierListImageExporter {
    suspend fun export(
        context: Context,
        state: TierListUiState,
        style: TierListStyle,
        boardTitle: String,
        displayLabels: List<String>,
    ) = withContext(Dispatchers.IO) {
        val labels = TIER_CODES.zip(normalizeTierDisplayLabels(displayLabels)).toMap()
        val visibleGroups = state.groups.filter { it.code in TIER_CODES }.map { group ->
            ExportTierGroup(
                code = group.code,
                label = labels.getValue(group.code),
                animes = group.animes.take(MAX_PER_TIER),
                originalCount = group.animes.size,
            )
        }
        val columns = max(1, (WIDTH - PADDING * 2 - LABEL_WIDTH - ROW_PADDING * 2) / (POSTER_WIDTH + GAP))
        val rowHeights = visibleGroups.map { group ->
            if (group.animes.isEmpty()) EMPTY_ROW_HEIGHT
            else {
                val lines = ceil(group.animes.size / columns.toDouble()).toInt()
                ROW_PADDING * 2 + lines * (POSTER_HEIGHT + TITLE_HEIGHT + GAP) - GAP
            }
        }
        val height = HEADER_HEIGHT + rowHeights.sum() + GAP * (visibleGroups.size - 1) + FOOTER_HEIGHT
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val palette = exportPalette(style)
        canvas.drawColor(palette.background)
        drawHeader(canvas, boardTitle, state.ratedCount, style, palette)

        val images = loadCovers(
            context = context,
            animes = visibleGroups.flatMap(ExportTierGroup::animes),
        )
        var top = HEADER_HEIGHT
        visibleGroups.forEachIndexed { groupIndex, group ->
            val rowHeight = rowHeights[groupIndex]
            drawTierRow(
                canvas = canvas,
                group = group,
                originalCount = group.originalCount,
                images = images,
                top = top,
                rowHeight = rowHeight,
                columns = columns,
                palette = palette,
            )
            top += rowHeight + GAP
        }
        drawFooter(canvas, height, state.ratedCount)

        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        directory.listFiles()?.filter { it.name.startsWith("tier-list-") }
            ?.filter { System.currentTimeMillis() - it.lastModified() > ONE_DAY_MS }
            ?.forEach(File::delete)
        val file = File(directory, "tier-list-${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output)) { "Tier List 图片编码失败" }
        }
        bitmap.recycle()
        images.values.forEach { it.recycle() }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private suspend fun loadCovers(
        context: Context,
        animes: List<AnimeEntity>,
    ): Map<Long, Bitmap> = supervisorScope {
        val loader = ImageLoader(context)
        animes.distinctBy(AnimeEntity::id).map { anime ->
            async(Dispatchers.IO) {
                val data = anime.coverUrl?.takeIf(String::isNotBlank) ?: return@async null
                val request = ImageRequest.Builder(context)
                    .data(data)
                    .allowHardware(false)
                    .size(POSTER_WIDTH, POSTER_HEIGHT)
                    .build()
                val result = runCatchingCancellable { loader.execute(request) }.getOrNull() as? SuccessResult
                result?.drawable?.toBitmap(POSTER_WIDTH, POSTER_HEIGHT, Bitmap.Config.ARGB_8888)
                    ?.let { anime.id to it }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    private fun drawHeader(
        canvas: Canvas,
        boardTitle: String,
        ratedCount: Int,
        style: TierListStyle,
        palette: ExportPalette,
    ) {
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 62f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.mutedText
            textSize = 28f
        }
        val visibleTitle = TextUtils.ellipsize(
            boardTitle,
            title,
            (WIDTH - PADDING * 2).toFloat(),
            TextUtils.TruncateAt.END,
        ).toString()
        canvas.drawText(visibleTitle, PADDING.toFloat(), 86f, title)
        canvas.drawText(
            "$ratedCount 部已评级作品 · ${style.displayName}",
            PADDING.toFloat(),
            136f,
            subtitle,
        )
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent }
        canvas.drawRoundRect(
            RectF(PADDING.toFloat(), 158f, (WIDTH - PADDING).toFloat(), 166f),
            4f,
            4f,
            accent,
        )
    }

    private fun drawTierRow(
        canvas: Canvas,
        group: ExportTierGroup,
        originalCount: Int,
        images: Map<Long, Bitmap>,
        top: Int,
        rowHeight: Int,
        columns: Int,
        palette: ExportPalette,
    ) {
        val tierColor = tierExportColor(group.code)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.card }
        canvas.drawRoundRect(
            RectF(PADDING.toFloat(), top.toFloat(), (WIDTH - PADDING).toFloat(), (top + rowHeight).toFloat()),
            28f,
            28f,
            cardPaint,
        )
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tierColor }
        val labelRect = RectF(
            PADDING.toFloat(),
            top.toFloat(),
            (PADDING + LABEL_WIDTH).toFloat(),
            (top + rowHeight).toFloat(),
        )
        canvas.save()
        val labelPath = Path().apply { addRoundRect(labelRect, 28f, 28f, Path.Direction.CW) }
        canvas.clipPath(labelPath)
        canvas.drawRect(labelRect, labelPaint)
        canvas.restore()
        val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = if (group.label.length > 2) 30f else 56f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.drawText(
            group.label,
            PADDING + LABEL_WIDTH / 2f,
            top + rowHeight / 2f - (labelText.ascent() + labelText.descent()) / 2f,
            labelText,
        )

        if (group.animes.isEmpty()) {
            val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.mutedText
                textSize = 30f
            }
            canvas.drawText("暂未评级", (PADDING + LABEL_WIDTH + ROW_PADDING).toFloat(), top + rowHeight / 2f, empty)
            return
        }
        group.animes.forEachIndexed { index, anime ->
            val column = index % columns
            val row = index / columns
            val left = PADDING + LABEL_WIDTH + ROW_PADDING + column * (POSTER_WIDTH + GAP)
            val posterTop = top + ROW_PADDING + row * (POSTER_HEIGHT + TITLE_HEIGHT + GAP)
            drawPoster(canvas, anime, images[anime.id], left, posterTop, palette)
        }
        if (originalCount > group.animes.size) {
            val remaining = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.mutedText
                textSize = 24f
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(
                "另有 ${originalCount - group.animes.size} 部未展示",
                (WIDTH - PADDING - 22).toFloat(),
                (top + rowHeight - 18).toFloat(),
                remaining,
            )
        }
    }

    private fun drawPoster(
        canvas: Canvas,
        anime: AnimeEntity,
        bitmap: Bitmap?,
        left: Int,
        top: Int,
        palette: ExportPalette,
    ) {
        val rect = RectF(left.toFloat(), top.toFloat(), (left + POSTER_WIDTH).toFloat(), (top + POSTER_HEIGHT).toFloat())
        val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.placeholder }
        canvas.drawRoundRect(rect, 18f, 18f, placeholder)
        if (bitmap != null) {
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(rect, 18f, 18f, Path.Direction.CW) })
            val source = centerCropSource(bitmap, POSTER_WIDTH, POSTER_HEIGHT)
            canvas.drawBitmap(bitmap, source, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        } else {
            val initials = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 34f
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            canvas.drawText(anime.title.take(2), rect.centerX(), rect.centerY(), initials)
        }
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val layout = StaticLayout.Builder.obtain(anime.title, 0, anime.title.length, textPaint, POSTER_WIDTH)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 0.94f)
            .build()
        canvas.save()
        canvas.translate(left.toFloat(), (top + POSTER_HEIGHT + 8).toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawFooter(canvas: Canvas, height: Int, ratedCount: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(145, 151, 169)
            textSize = 24f
        }
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        canvas.drawText("AniMeow · $time · 共 $ratedCount 部已评级", PADDING.toFloat(), (height - 34).toFloat(), paint)
    }

    private fun centerCropSource(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Rect {
        val sourceRatio = bitmap.width.toFloat() / bitmap.height
        val targetRatio = targetWidth.toFloat() / targetHeight
        return if (sourceRatio > targetRatio) {
            val width = (bitmap.height * targetRatio).toInt()
            val left = (bitmap.width - width) / 2
            Rect(left, 0, left + width, bitmap.height)
        } else {
            val height = (bitmap.width / targetRatio).toInt()
            val top = (bitmap.height - height) / 2
            Rect(0, top, bitmap.width, top + height)
        }
    }

    private fun tierExportColor(label: String): Int = when (label.uppercase(java.util.Locale.ROOT)) {
        "S" -> Color.rgb(229, 84, 84)
        "A" -> Color.rgb(240, 138, 36)
        "B" -> Color.rgb(230, 185, 62)
        "C" -> Color.rgb(74, 169, 108)
        "D" -> Color.rgb(75, 132, 215)
        else -> Color.rgb(115, 120, 132)
    }

    private fun exportPalette(style: TierListStyle): ExportPalette = when (style) {
        TierListStyle.MINIMAL -> ExportPalette(0xFF171717.toInt(), 0xFF242424.toInt(), 0xFFAAAAAA.toInt(), 0xFFE6E6E6.toInt(), 0xFF333333.toInt())
    }

    private data class ExportPalette(
        val background: Int,
        val card: Int,
        val mutedText: Int,
        val accent: Int,
        val placeholder: Int,
    )

    private data class ExportTierGroup(
        val code: String,
        val label: String,
        val animes: List<AnimeEntity>,
        val originalCount: Int,
    )

    private const val WIDTH = 1440
    private const val PADDING = 56
    private const val HEADER_HEIGHT = 190
    private const val FOOTER_HEIGHT = 80
    private const val LABEL_WIDTH = 116
    private const val ROW_PADDING = 24
    private const val POSTER_WIDTH = 150
    private const val POSTER_HEIGHT = 214
    private const val TITLE_HEIGHT = 64
    private const val EMPTY_ROW_HEIGHT = 180
    private const val GAP = 18
    private const val MAX_PER_TIER = 10
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1_000L
}
