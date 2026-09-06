package com.animeow.app.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalAppStyle = staticCompositionLocalOf { AppStyle.MIUIX }
val LocalMotionLevel = staticCompositionLocalOf { MotionLevel.FULL }
val LocalContentDensity = staticCompositionLocalOf { ContentDensity.COMFORTABLE }
val LocalDetailLayout = staticCompositionLocalOf { DetailLayout.CLASSIC }

@Composable
fun AniMeowTheme(
    settings: AppearanceSettings,
    content: @Composable () -> Unit,
) {
    val systemIsDark = androidx.compose.foundation.isSystemInDarkTheme()
    val isDark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemIsDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val systemDensity = LocalDensity.current
    val baseColorScheme = when {
        settings.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> styleColorScheme(appStyle = settings.appStyle, isDark = isDark)
    }
    val colorScheme = settings.accentColor?.let { raw ->
        applyAccent(baseColorScheme, Color(raw.toInt()), isDark)
    } ?: baseColorScheme

    CompositionLocalProvider(
        LocalAppStyle provides settings.appStyle,
        LocalMotionLevel provides settings.motionLevel,
        LocalContentDensity provides settings.contentDensity,
        LocalDetailLayout provides settings.detailLayout,
        LocalDensity provides Density(
            density = systemDensity.density,
            // App scaling is relative to the user's system accessibility font size.
            fontScale = combinedFontScale(systemDensity.fontScale, settings.fontScale),
        ),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = styleShapes(settings.appStyle, settings.cornerScale),
            typography = styleTypography(settings.appStyle),
            content = content,
        )
    }
}

internal fun combinedFontScale(systemFontScale: Float, appFontScale: Float): Float =
    systemFontScale.coerceAtLeast(0.1f) * appFontScale.coerceIn(APP_FONT_SCALE_MIN, APP_FONT_SCALE_MAX)

private fun applyAccent(base: ColorScheme, accent: Color, isDark: Boolean): ColorScheme {
    val onAccent = if (accent.luminance() > 0.52f) Color.Black else Color.White
    val container = blend(accent, base.surface, if (isDark) 0.68f else 0.78f)
    val onContainer = if (container.luminance() > 0.52f) Color.Black else Color.White
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        secondary = blend(accent, base.secondary, 0.42f),
        tertiary = blend(accent, base.tertiary, 0.36f),
    )
}

private fun blend(foreground: Color, background: Color, backgroundWeight: Float): Color {
    val weight = backgroundWeight.coerceIn(0f, 1f)
    return Color(
        red = foreground.red * (1 - weight) + background.red * weight,
        green = foreground.green * (1 - weight) + background.green * weight,
        blue = foreground.blue * (1 - weight) + background.blue * weight,
        alpha = 1f,
    )
}

private fun styleColorScheme(
    appStyle: AppStyle,
    isDark: Boolean,
): ColorScheme = when (appStyle) {
    AppStyle.MIUIX -> if (isDark) MiuixDarkColors else MiuixLightColors
    AppStyle.ANIME_DYNAMIC -> if (isDark) AnimeDarkColors else AnimeLightColors
    AppStyle.CYBER_GLASS -> if (isDark) CyberDarkColors else CyberLightColors
    AppStyle.RETRO_PIXEL -> if (isDark) PixelDarkColors else PixelLightColors
}

private fun styleShapes(appStyle: AppStyle, scale: Float): Shapes = when (appStyle) {
    AppStyle.MIUIX -> Shapes(
        extraSmall = RoundedCornerShape(10.dp * scale),
        small = RoundedCornerShape(14.dp * scale),
        medium = RoundedCornerShape(20.dp * scale),
        large = RoundedCornerShape(26.dp * scale),
        extraLarge = RoundedCornerShape(32.dp * scale),
    )
    AppStyle.ANIME_DYNAMIC -> Shapes(
        extraSmall = RoundedCornerShape(12.dp * scale),
        small = RoundedCornerShape(18.dp * scale),
        medium = RoundedCornerShape(24.dp * scale),
        large = RoundedCornerShape(30.dp * scale),
        extraLarge = RoundedCornerShape(36.dp * scale),
    )
    AppStyle.CYBER_GLASS -> Shapes(
        extraSmall = RoundedCornerShape(8.dp * scale),
        small = RoundedCornerShape(12.dp * scale),
        medium = RoundedCornerShape(18.dp * scale),
        large = RoundedCornerShape(22.dp * scale),
        extraLarge = RoundedCornerShape(26.dp * scale),
    )
    AppStyle.RETRO_PIXEL -> Shapes(
        extraSmall = RoundedCornerShape(1.dp * scale),
        small = RoundedCornerShape(2.dp * scale),
        medium = RoundedCornerShape(3.dp * scale),
        large = RoundedCornerShape(4.dp * scale),
        extraLarge = RoundedCornerShape(5.dp * scale),
    )
}

private fun styleTypography(appStyle: AppStyle): Typography {
    val base = Typography()
    return when (appStyle) {
        AppStyle.MIUIX -> base.copy(
            headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        AppStyle.ANIME_DYNAMIC -> base.copy(
            headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.3.sp),
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.2.sp),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
            labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.35.sp),
        )
        AppStyle.CYBER_GLASS -> base.copy(
            headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 1.1.sp),
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.9.sp),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.7.sp),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.55.sp),
            labelLarge = base.labelLarge.copy(letterSpacing = 0.8.sp),
        )
        AppStyle.RETRO_PIXEL -> base.copy(
            displayLarge = base.displayLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
            headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp),
            titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            labelLarge = base.labelLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
        )
    }
}

private val MiuixLightColors = lightColorScheme(
    primary = Color(0xFF3482FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF0B326B),
    secondary = Color(0xFF7B61D1),
    background = Color(0xFFF7F7FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECECF2),
    outline = Color(0xFFD7D7E0),
)

private val MiuixDarkColors = darkColorScheme(
    primary = Color(0xFF8DB5FF),
    onPrimary = Color(0xFF062D67),
    primaryContainer = Color(0xFF244D88),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFFC5B4FF),
    background = Color(0xFF101216),
    surface = Color(0xFF1A1D23),
    surfaceVariant = Color(0xFF282C34),
    outline = Color(0xFF414751),
)

private val AnimeLightColors = lightColorScheme(
    primary = Color(0xFF8A4FD0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0DBFF),
    onPrimaryContainer = Color(0xFF321052),
    secondary = Color(0xFFE35F9A),
    background = Color(0xFFFFF7FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF5EAF3),
    outline = Color(0xFFE4D4E0),
)

private val AnimeDarkColors = darkColorScheme(
    primary = Color(0xFFD2A8FF),
    onPrimary = Color(0xFF421267),
    primaryContainer = Color(0xFF60328A),
    onPrimaryContainer = Color(0xFFF0DBFF),
    secondary = Color(0xFFFFA8CB),
    background = Color(0xFF171119),
    surface = Color(0xFF241B27),
    surfaceVariant = Color(0xFF35283A),
    outline = Color(0xFF514258),
)

private val CyberLightColors = lightColorScheme(
    primary = Color(0xFF006C8A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBDE9FF),
    onPrimaryContainer = Color(0xFF001F2A),
    secondary = Color(0xFF6955B5),
    background = Color(0xFFF3F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE3EDF4),
    outline = Color(0xFFC7D7E0),
)

private val CyberDarkColors = darkColorScheme(
    primary = Color(0xFF64D8FF),
    onPrimary = Color(0xFF003546),
    primaryContainer = Color(0xFF004D63),
    onPrimaryContainer = Color(0xFFBDE9FF),
    secondary = Color(0xFFCAB9FF),
    background = Color(0xFF090C12),
    surface = Color(0xFF141A24),
    surfaceVariant = Color(0xFF202B39),
    outline = Color(0xFF33475B),
)

private val MaterialLightColors = lightColorScheme(
    primary = Color(0xFF42664A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC4ECC9),
    onPrimaryContainer = Color(0xFF00210A),
    secondary = Color(0xFF526350),
    background = Color(0xFFF8FBF5),
    surface = Color(0xFFF8FBF5),
)

private val MaterialDarkColors = darkColorScheme(
    primary = Color(0xFFA8D1AE),
    onPrimary = Color(0xFF14371E),
    primaryContainer = Color(0xFF2B4E34),
    onPrimaryContainer = Color(0xFFC4ECC9),
    secondary = Color(0xFFBACCB7),
    background = Color(0xFF101510),
    surface = Color(0xFF101510),
)

private val MinimalLightColors = lightColorScheme(
    primary = Color(0xFF1769C2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E7FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF625F67),
    background = Color(0xFFF5F5F7),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDEDF1),
    outline = Color(0xFFD9D9DF),
)

private val MinimalDarkColors = darkColorScheme(
    primary = Color(0xFFA7C8FF),
    onPrimary = Color(0xFF003062),
    primaryContainer = Color(0xFF004789),
    onPrimaryContainer = Color(0xFFD5E7FF),
    secondary = Color(0xFFCBC5CE),
    background = Color(0xFF101012),
    surface = Color(0xFF1B1B1E),
    surfaceVariant = Color(0xFF29292D),
    outline = Color(0xFF444449),
)

private val PixelLightColors = lightColorScheme(
    primary = Color(0xFF526B2D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E7AC),
    onPrimaryContainer = Color(0xFF172000),
    secondary = Color(0xFF83552F),
    background = Color(0xFFF0F3D7),
    surface = Color(0xFFF9FBDD),
    surfaceVariant = Color(0xFFE1E8BB),
    outline = Color(0xFFADB77F),
)

private val PixelDarkColors = darkColorScheme(
    primary = Color(0xFFB7D27B),
    onPrimary = Color(0xFF263600),
    primaryContainer = Color(0xFF3C500F),
    onPrimaryContainer = Color(0xFFD7E7AC),
    secondary = Color(0xFFE8B68A),
    background = Color(0xFF15190F),
    surface = Color(0xFF222819),
    surfaceVariant = Color(0xFF303923),
    outline = Color(0xFF596642),
)
