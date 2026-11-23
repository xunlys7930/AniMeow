package com.animeow.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import com.animeow.app.R
import com.animeow.app.data.fonts.AppFontStore

enum class AppFontChoice(val storageKey: String, val displayName: String) {
    SYSTEM("system", "跟随系统"), NOTO_SANS("noto_sans", "清晰黑体"), NOTO_SERIF("noto_serif", "文艺宋体"), WENKAI("wenkai", "轻松手写"), CUSTOM("custom", "导入字体");
    companion object {
        fun fromStorage(value: String?): AppFontChoice = entries.firstOrNull { it.storageKey == value } ?: SYSTEM
    }
}

@Composable
internal fun rememberAppFont(settings: AppearanceSettings): FontFamily? {
    val context = LocalContext.current
    val store = remember(context) { AppFontStore(context) }
    val custom by produceState<FontFamily?>(null, settings.appFontChoice, settings.customFontId) {
        value = null
        if (settings.appFontChoice == AppFontChoice.CUSTOM) value = store.load(settings.customFontId)?.let(::FontFamily)
    }
    return when (settings.appFontChoice) {
        AppFontChoice.SYSTEM -> null
        AppFontChoice.CUSTOM -> custom
        else -> bundledFontFamily(settings.appFontChoice)
    }
}

internal fun bundledFontFamily(choice: AppFontChoice): FontFamily? = when (choice) {
    AppFontChoice.NOTO_SANS -> FontFamily(Font(R.font.animeow_sans))
    AppFontChoice.NOTO_SERIF -> FontFamily(Font(R.font.animeow_serif))
    AppFontChoice.WENKAI -> FontFamily(Font(R.font.animeow_wenkai))
    else -> null
}

internal fun Typography.withAppFont(family: FontFamily?): Typography = if (family == null) this else copy(
    displayLarge = displayLarge.copy(fontFamily = family), displayMedium = displayMedium.copy(fontFamily = family), displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family), headlineMedium = headlineMedium.copy(fontFamily = family), headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family), titleMedium = titleMedium.copy(fontFamily = family), titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family), bodyMedium = bodyMedium.copy(fontFamily = family), bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family), labelMedium = labelMedium.copy(fontFamily = family), labelSmall = labelSmall.copy(fontFamily = family),
)
