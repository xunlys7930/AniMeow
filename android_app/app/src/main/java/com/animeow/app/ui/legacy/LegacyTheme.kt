package com.animeow.app.ui.legacy

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.FrontendMode
import com.animeow.app.ui.theme.ThemeMode

val LegacyPurple = Color(0xFF7135C5)
val LegacyPurpleSoft = Color(0xFFDCC5FF)
val LegacyLavender = Color(0xFFF1EDF8)
val LegacyLavenderStrong = Color(0xFFE8E1F3)
val LegacyBlue = Color(0xFF1EA6E8)
val LegacyGreen = Color(0xFF2DBB62)
val LegacyOrange = Color(0xFFFF9800)

private val LegacyLightColors = lightColorScheme(
    primary = LegacyPurple,
    onPrimary = Color.White,
    primaryContainer = LegacyPurpleSoft,
    onPrimaryContainer = Color(0xFF2D1550),
    secondary = Color(0xFF67507F),
    onSecondary = Color.White,
    secondaryContainer = LegacyLavenderStrong,
    onSecondaryContainer = Color(0xFF21182B),
    tertiary = LegacyBlue,
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1D1A22),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1D1A22),
    surfaceVariant = LegacyLavender,
    onSurfaceVariant = Color(0xFF5B5562),
    outline = Color(0xFF8B858F),
    outlineVariant = Color(0xFFE0DAE7),
)

private val LegacyDarkColors = darkColorScheme(
    primary = Color(0xFFD3B4FF),
    onPrimary = Color(0xFF3C126B),
    primaryContainer = Color(0xFF55258D),
    onPrimaryContainer = Color(0xFFEBDDFF),
    secondary = Color(0xFFD3C1E4),
    secondaryContainer = Color(0xFF40344B),
    background = Color(0xFF151218),
    onBackground = Color(0xFFECE6EF),
    surface = Color(0xFF151218),
    onSurface = Color(0xFFECE6EF),
    surfaceVariant = Color(0xFF29242F),
    onSurfaceVariant = Color(0xFFD0C8D6),
    outline = Color(0xFF99909E),
    outlineVariant = Color(0xFF453E4A),
)

@Composable
fun LegacyAniMeowTheme(
    settings: AppearanceSettings,
    content: @Composable () -> Unit,
) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val base = Typography()
    MaterialTheme(
        colorScheme = if (dark) LegacyDarkColors else LegacyLightColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(12.dp),
            small = RoundedCornerShape(18.dp),
            medium = RoundedCornerShape(24.dp),
            large = RoundedCornerShape(30.dp),
            extraLarge = RoundedCornerShape(38.dp),
        ),
        typography = base.copy(
            displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black),
            headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Black),
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
            labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold),
        ),
        content = content,
    )
}

@Composable
fun AniMeowFrontendTheme(
    settings: AppearanceSettings,
    content: @Composable () -> Unit,
) {
    if (settings.frontendMode == FrontendMode.LEGACY) {
        LegacyAniMeowTheme(settings, content)
    } else {
        content()
    }
}
