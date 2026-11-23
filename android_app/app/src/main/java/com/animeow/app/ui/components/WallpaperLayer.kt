package com.animeow.app.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.animeow.app.data.background.BackgroundScaleMode
import java.io.File

@Composable
fun WallpaperLayer(
    imageFile: File?,
    scaleMode: BackgroundScaleMode,
    opacity: Float,
    blurDp: Int,
    modifier: Modifier = Modifier,
) {
    if (imageFile == null || !imageFile.exists() || opacity <= 0f) return

    val contentScale = when (scaleMode) {
        BackgroundScaleMode.COVER -> ContentScale.Crop
        BackgroundScaleMode.CONTAIN -> ContentScale.Fit
        BackgroundScaleMode.STRETCH -> ContentScale.FillBounds
    }

    val blurModifier = if (blurDp > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.blur(blurDp.coerceIn(0, 30).dp)
    } else Modifier

    Box(modifier = modifier.fillMaxSize()) {
        AsyncImage(
            model = imageFile,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier
                .fillMaxSize()
                .then(blurModifier)
                .alpha(opacity.coerceIn(0f, 1f)),
        )
    }
}
