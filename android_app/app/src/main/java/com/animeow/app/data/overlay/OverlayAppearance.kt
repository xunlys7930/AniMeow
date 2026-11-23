package com.animeow.app.data.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.toColorInt
import com.animeow.app.R
import com.animeow.app.ui.theme.OverlayStyle

internal data class OverlayPalette(
    val surface: Int, val text: Int, val secondaryText: Int,
    val primary: Int, val onPrimary: Int, val button: Int, val border: Int,
) {
    fun textColors(primaryAction: Boolean) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(secondaryText, if (primaryAction) onPrimary else text),
    )
}

internal fun OverlayStyle.palette(dark: Boolean): OverlayPalette {
    fun color(value: String) = value.toColorInt()
    val values = when (this) {
        OverlayStyle.CREAM_CAT -> if (dark)
            listOf("#303037", "#FFF6EA", "#C4BBC0", "#FFB37B", "#392014", "#46434A", "#625650")
        else listOf("#FFF9F2", "#382B25", "#7E6E65", "#FFB37B", "#392014", "#F2EAE2", "#EEDFD1")
        OverlayStyle.MOON_GLASS -> if (dark)
            listOf("#242F45", "#F5F8FF", "#B9C5DC", "#80A9FF", "#15223C", "#35435D", "#45516A")
        else listOf("#F0F5FF", "#1C2948", "#5D6D89", "#3169D7", "#FFFFFF", "#E2EAFB", "#D6E4FE")
        OverlayStyle.QUIET_PAW -> listOf("#2C2E30", "#FFF7E9", "#C2C2BF", "#FFB778", "#29231D", "#45474A", "#56585B")
    }.map(::color)
    return OverlayPalette(values[0], values[1], values[2], values[3], values[4], values[5], values[6])
}

internal fun overlaySurface(color: Int, radius: Float, border: Int? = null) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (border != null) setStroke(1, border)
}

/** The same small native drawable is used in settings and the system overlay. */
internal class OverlayBallDrawable(context: Context, val style: OverlayStyle) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val art = BitmapFactory.decodeResource(context.resources, when (style) {
        OverlayStyle.CREAM_CAT -> R.drawable.overlay_cream_cat
        OverlayStyle.MOON_GLASS -> R.drawable.overlay_moon_glass
        OverlayStyle.QUIET_PAW -> R.drawable.overlay_quiet_paw
    }, BitmapFactory.Options().apply { inSampleSize = 4 })
    var progress: Float? = null
        set(value) { field = value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f); invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val rect = RectF(bounds)
        if (style == OverlayStyle.QUIET_PAW) {
            val size = minOf(rect.width(), rect.height())
            rect.inset(size * 0.025f, size * 0.025f)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(44, 46, 48)
            canvas.drawRoundRect(rect, size * 0.39f, size * 0.39f, paint)
            paint.color = Color.WHITE
            val icon = RectF(bounds).apply { inset(size * 0.19f, size * 0.19f); offset(0f, size * 0.04f) }
            canvas.drawBitmap(art, null, icon, paint)
            progress?.let { fraction ->
                val ring = RectF(bounds).apply { inset(size * 0.12f, size * 0.12f) }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.045f
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = Color.rgb(255, 183, 120)
                canvas.drawArc(ring, -90f, 360f * fraction, false, paint)
            }
        } else {
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawBitmap(art, null, rect, paint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
