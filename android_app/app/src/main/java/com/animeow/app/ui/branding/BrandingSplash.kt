package com.animeow.app.ui.branding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.animeow.app.data.branding.BrandingSettings
import com.animeow.app.data.branding.SplashBackgroundMode
import com.animeow.app.data.branding.SplashScaleMode
import com.animeow.app.ui.theme.MotionLevel
import java.io.File
import kotlinx.coroutines.delay

@Composable
fun BrandingSplash(
    settings: BrandingSettings,
    imageFile: File,
    motionLevel: MotionLevel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var imageReady by remember(imageFile) { mutableStateOf(false) }
    val fadeDuration = (360 * motionLevel.durationScale).toInt()
    val imageAlpha by animateFloatAsState(
        targetValue = if (imageReady) 1f else 0f,
        animationSpec = tween(fadeDuration),
        label = "brandingSplashImage",
    )
    val background = when (settings.splashBackgroundMode) {
        SplashBackgroundMode.THEME -> MaterialTheme.colorScheme.background
        SplashBackgroundMode.BLACK -> Color.Black
        SplashBackgroundMode.WHITE -> Color.White
        SplashBackgroundMode.ACCENT -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentScale = when (settings.splashScaleMode) {
        SplashScaleMode.COVER -> ContentScale.Crop
        SplashScaleMode.CONTAIN -> ContentScale.Fit
        SplashScaleMode.STRETCH -> ContentScale.FillBounds
    }
    val alignment = BiasAlignment(
        horizontalBias = settings.splashFocalX * 2f - 1f,
        verticalBias = settings.splashFocalY * 2f - 1f,
    )

    LaunchedEffect(imageReady, settings.splashDurationMillis) {
        if (!imageReady) return@LaunchedEffect
        delay(settings.splashDurationMillis.toLong())
        onFinished()
    }

    Box(modifier = modifier.fillMaxSize().background(background)) {
        AsyncImage(
            model = imageFile,
            contentDescription = "自定义启动封面",
            modifier = Modifier.fillMaxSize().alpha(imageAlpha),
            contentScale = contentScale,
            alignment = alignment,
            onSuccess = { imageReady = true },
            onError = { onFinished() },
        )
        if (!imageReady) {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize().wrapContentSize(),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (settings.tapToSkip) {
            Surface(
                onClick = onFinished,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomEnd)
                    .padding(20.dp),
                color = Color.Black.copy(alpha = 0.52f),
                contentColor = Color.White,
                shape = RoundedCornerShape(24.dp),
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("跳过", style = MaterialTheme.typography.labelLarge)
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

