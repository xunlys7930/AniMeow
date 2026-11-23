package com.animeow.app.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.theme.GlassQuality
import com.animeow.app.ui.theme.LocalGlassQuality
import com.animeow.app.ui.theme.LocalHighContrast
import kotlin.math.ceil
import kotlin.math.roundToInt

@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var position by mutableStateOf(Offset.Zero)
    internal var ready by mutableStateOf(false)
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** Capture only the page layer, never the glass overlay itself. No screenshots or CPU copies. */
@Composable
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop, enabled: Boolean = true): Modifier =
    if (!enabled || Build.VERSION.SDK_INT < 31 || com.animeow.app.ui.theme.LocalGlassQuality.current == com.animeow.app.ui.theme.GlassQuality.LOW) this else
        onGloballyPositioned { backdrop.position = it.positionInRoot() }.drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            backdrop.ready = true
            drawLayer(backdrop.layer)
        }

@Composable
fun Modifier.glassBackdropEffect(
    backdrop: GlassBackdrop?,
    opacity: Float,
    blurRadius: Int,
    tint: Color,
): Modifier {
    val quality = LocalGlassQuality.current
    val alpha = if (LocalHighContrast.current) opacity.coerceIn(0.9f, 1f) else opacity.coerceIn(0f, 1f)
    // 轻量档必须在进入含新平台类型的绘制实现前返回，不能只关闭背景采样。
    if (Build.VERSION.SDK_INT < 31 || quality == GlassQuality.LOW || backdrop == null) {
        return drawWithContent {
            drawGlassTint(tint, alpha, blurred = false)
            drawContent()
        }
    }
    return with(GlassBackdropApi31) { effect(backdrop, alpha, blurRadius, tint, quality) }
}

@RequiresApi(31)
private object GlassBackdropApi31 {
    @DoNotInline
    @Composable
    fun Modifier.effect(
        backdrop: GlassBackdrop,
        alpha: Float,
        blurRadius: Int,
        tint: Color,
        quality: GlassQuality,
    ): Modifier {
        val blurLayer = rememberGraphicsLayer()
        var position by remember { mutableStateOf(Offset.Zero) }
        val radius = with(LocalDensity.current) { blurRadius.coerceIn(0, 40).dp.toPx() }
        val gaussian = remember(radius) {
            try {
                if (radius > 0f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null
            } catch (_: RuntimeException) {
                null
            }
        }
        return onGloballyPositioned { position = it.positionInRoot() }.drawWithCache {
            val inset = ceil(radius * 2).toInt()
            val layerSize = IntSize(size.width.roundToInt() + inset * 2, size.height.roundToInt() + inset * 2)
            blurLayer.renderEffect = if (Build.VERSION.SDK_INT >= 33 && quality != GlassQuality.BLUR) {
                LiquidGlassApi33.createEffect(gaussian, layerSize, 5.dp.toPx())
                    ?: gaussian?.asComposeRenderEffect()
            } else gaussian?.asComposeRenderEffect()
            onDrawWithContent {
                if (alpha > 0f) {
                    val canBlur = backdrop.ready && (blurLayer.renderEffect != null || radius == 0f)
                    if (canBlur) {
                        blurLayer.record(size = layerSize) {
                            translate(backdrop.position.x - position.x + inset, backdrop.position.y - position.y + inset) { drawLayer(backdrop.layer) }
                        }
                        blurLayer.alpha = alpha
                        translate(-inset.toFloat(), -inset.toFloat()) { drawLayer(blurLayer) }
                    }
                    drawGlassTint(tint, alpha, blurred = canBlur)
                }
                drawContent()
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlassTint(tint: Color, alpha: Float, blurred: Boolean) {
    if (alpha <= 0f) return
    drawRect(tint.copy(alpha = alpha * if (blurred) 0.20f else 0.72f))
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f * alpha), Color.Transparent, Color.White.copy(alpha = 0.04f * alpha))))
}

// RuntimeShader 类型只存在于 Android 13+；Android 12 的高斯路径不应解析它。
@RequiresApi(33)
internal object LiquidGlassApi33 {
    @DoNotInline
    fun createEffect(gaussian: RenderEffect?, size: IntSize, strength: Float): androidx.compose.ui.graphics.RenderEffect? =
        try {
            val shader = RuntimeShader(SHADER)
            shader.setFloatUniform("resolution", size.width.toFloat(), size.height.toFloat())
            shader.setFloatUniform("strength", strength)
            val lens = RenderEffect.createRuntimeShaderEffect(shader, "content")
            (if (gaussian != null) RenderEffect.createChainEffect(lens, gaussian) else lens).asComposeRenderEffect()
        } catch (_: RuntimeException) {
            // 驱动拒绝着色器时保留高斯/半透明回退，避免已保存的外观导致启动循环闪退。
            null
        }

    private val SHADER = """
    uniform shader content;
    uniform float2 resolution;
    uniform float strength;
    half4 main(float2 p) {
        float2 q = (p / resolution - 0.5) * 2.0;
        float edge = pow(clamp(max(abs(q.x), abs(q.y)), 0.0, 1.0), 4.0);
        return content.eval(p - q * edge * strength);
    }
""".trimIndent()
}
