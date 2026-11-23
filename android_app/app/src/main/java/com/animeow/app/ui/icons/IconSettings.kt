package com.animeow.app.ui.icons

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.animeow.app.ui.theme.MotionLevel

enum class IconVariant(val storageKey: String, val label: String) {
    A("a", "变体 A"),
    B("b", "变体 B");

    companion object {
        fun fromStorage(value: String?): IconVariant? = entries.firstOrNull { it.storageKey == value }
    }
}

/** Null values follow the global choice, including after a later style switch. */
@Immutable
data class IconOverride(val style: IconStyle? = null, val variant: IconVariant? = null, val color: IconColorSettings? = null) {
    val followsGlobal: Boolean get() = style == null && variant == null && color == null
}

enum class IconColorMode(val storageKey: String, val label: String) {
    CONTENT("content", "界面配色"), ACCENT("accent", "主题强调色"), CUSTOM("custom", "自定义颜色");
    companion object { fun fromStorage(value: String?) = entries.firstOrNull { it.storageKey == value } ?: CONTENT }
}

@Immutable
data class IconColorSettings(val mode: IconColorMode = IconColorMode.CONTENT, val customColor: Long = 0xFF527D72L)

@Immutable
data class ResolvedIcon(val style: IconStyle, val variant: IconVariant, val color: IconColorSettings = IconColorSettings())

data class IconSettings(
    val style: IconStyle = IconStyle.LINE,
    val variant: IconVariant = IconVariant.A,
    val animated: Boolean = true,
    val overrides: Map<AppIconId, IconOverride> = emptyMap(),
    val color: IconColorSettings = IconColorSettings(),
) {
    fun resolve(id: AppIconId): ResolvedIcon {
        val individual = overrides[id]
        return ResolvedIcon(individual?.style ?: style, individual?.variant ?: variant, individual?.color ?: color)
    }

    fun withOverride(id: AppIconId, value: IconOverride?): IconSettings = copy(
        overrides = if (value == null || value.followsGlobal) overrides - id else overrides + (id to value),
    )
}

val LocalIconSettings = staticCompositionLocalOf { IconSettings() }
internal val LocalIconSystemAnimationsEnabled = staticCompositionLocalOf { true }

/** Decorative motion is suppressed for both reduced motion and disabled system animations. */
internal fun iconAnimationsEnabled(settings: IconSettings, motion: MotionLevel, systemEnabled: Boolean): Boolean =
    settings.animated && motion == MotionLevel.FULL && systemEnabled
