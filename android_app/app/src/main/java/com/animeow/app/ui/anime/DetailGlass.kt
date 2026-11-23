package com.animeow.app.ui.anime

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import android.os.Build
import coil.compose.AsyncImage
import com.animeow.app.ui.components.GlassBackdrop
import com.animeow.app.ui.components.glassBackdropEffect
import com.animeow.app.ui.components.glassBackdropSource
import com.animeow.app.ui.components.rememberGlassBackdrop
import com.animeow.app.ui.theme.GlassQuality
import com.animeow.app.ui.theme.LocalGlassQuality
import com.animeow.app.ui.theme.LocalHighContrast

private val LocalDetailBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

@Composable
internal fun DetailGlassScene(enabled: Boolean, cover: Any?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val backdrop = rememberGlassBackdrop()
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val contrast = LocalHighContrast.current
    val fullEffects = LocalGlassQuality.current != GlassQuality.LOW
    Box(modifier) {
        if (enabled) Box(Modifier.matchParentSize().glassBackdropSource(backdrop)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(colors.background)
                drawRect(Brush.radialGradient(listOf(colors.primary.copy(alpha = if (dark) 0.24f else 0.13f), Color.Transparent),
                    center = Offset(size.width * 0.12f, size.height * 0.12f), radius = size.width * 1.1f))
                drawRect(Brush.radialGradient(listOf(colors.tertiary.copy(alpha = if (dark) 0.18f else 0.10f), Color.Transparent),
                    center = Offset(size.width, size.height * 0.7f), radius = size.width * 1.2f))
            }
            if (cover != null && !contrast && fullEffects) {
                // blur 依赖 RenderEffect（Android 12+）；主题层已在旧系统强制 LOW 档，这里再加一道保险
                val coverBlur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(44.dp) else Modifier
                AsyncImage(cover, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().then(coverBlur).alpha(if (dark) 0.20f else 0.14f))
            }
        }
        CompositionLocalProvider(LocalDetailBackdrop provides backdrop.takeIf { enabled }) { content() }
    }
}

@Composable
internal fun DetailGlassSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val contrast = LocalHighContrast.current
    val shape = RoundedCornerShape(22.dp)
    val border = Brush.linearGradient(listOf(
        Color.White.copy(alpha = if (dark) 0.25f else 0.7f),
        colors.primary.copy(alpha = 0.06f),
        Color.White.copy(alpha = if (dark) 0.07f else 0.30f),
    ))
    // One clipped surface. No Card shadow/opaque inner Surface can leak through the glass.
    Column(modifier.clip(shape)
        .glassBackdropEffect(LocalDetailBackdrop.current, opacity = 0.92f, blurRadius = 22, tint = colors.surface)
        .background(colors.surface.copy(alpha = if (contrast) 0.98f else if (dark) 0.48f else 0.43f))
        .border(0.5.dp, border, shape)) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) { content() }
    }
}
