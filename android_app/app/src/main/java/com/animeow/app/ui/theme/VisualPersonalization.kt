package com.animeow.app.ui.theme

import org.json.JSONObject
import com.animeow.app.ui.icons.IconColorMode
import com.animeow.app.ui.icons.IconColorSettings
import com.animeow.app.ui.icons.IconStyle
import com.animeow.app.ui.icons.IconVariant

enum class GlassQuality(val storageKey: String, val label: String) {
    ADAPTIVE("adaptive", "自动适配"),
    LIQUID("liquid", "液态与模糊"),
    BLUR("blur", "仅高斯模糊"),
    LOW("low", "轻量半透明");
    companion object { fun fromStorage(value: String?) = entries.find { it.storageKey == value } ?: ADAPTIVE }
}

val DENSITY_PAGES = linkedMapOf("tracker" to "追番首页", "anime" to "作品详情", "series" to "系列", "characters" to "角色")

fun AppearanceSettings.densityFor(page: String): ContentDensity = pageDensities[page] ?: contentDensity

internal fun pageDensitiesFromJson(json: JSONObject?): Map<String, ContentDensity> = DENSITY_PAGES.keys.mapNotNull { key ->
    json?.optString(key)?.takeIf { value -> ContentDensity.entries.any { it.storageKey == value } }?.let { key to ContentDensity.fromStorage(it) }
}.toMap()

/** Complete, deliberately different visual suites; functional preferences are retained. */
enum class AppearanceSuite(val label: String, val description: String) {
    QUIET("灵动聚合", "智能聚合 · 悬浮胶囊 · 紧致滑动 · 蓝绿渐变"),
    GLASS("流光网格", "海报网格 · 液态玻璃 · 层叠视差 · 暮色流光"),
    JOURNAL("暖纸清单", "文字清单 · 暖纸纯色 · 楷体 · 轻柔动效"),
    CLASSIC("经典书架", "经典界面 · 密集书架 · 底部导航 · 低装饰");

    fun apply(current: AppearanceSettings): AppearanceSettings {
        val base = current.copy(useDynamicColor = false, themeColor = null, randomAccentOnLaunch = false, customFontId = null, customFontName = "",
            showTitle = true, showProgress = true, showStatus = true, showRating = true, fontScale = 1f,
            highContrast = false, pageDensities = emptyMap(), coverAspectRatio = CoverAspectRatio.POSTER,
            frontendModeChoiceMade = true, floatingBarHeight = 56, floatingBarHorizontalMargin = 20, floatingBarCornerRadius = 32,
            floatingBarBottomMargin = 12, floatingBarShadowElevation = 8, navigationLabelMode = NavigationLabelMode.SELECTED)
        return when (this) {
            QUIET -> AccentPalettePreset.MINT_SKY.apply(base).copy(frontendMode = FrontendMode.MODERN, appStyle = AppStyle.MIUIX, themeMode = ThemeMode.SYSTEM,
                homeLayout = HomeLayout.BENTO, contentDensity = ContentDensity.COMFORTABLE,
                appFontChoice = AppFontChoice.NOTO_SANS, cornerScale = 1.15f, motionLevel = MotionLevel.FULL,
                navigationBarStyle = NavigationBarStyle.FLOATING, glassQuality = GlassQuality.BLUR, detailCardStyle = DetailCardStyle.TONAL,
                detailLayout = DetailLayout.DASHBOARD, floatingBarOpacity = 0.94f, floatingBarBlur = 16,
                pageTransitionStyle = PageTransitionStyle.REFINED_SLIDE, gridColumns = 3, coverTitlePosition = CoverTitlePosition.BELOW,
                icons = base.icons.copy(style = IconStyle.SOFT, variant = IconVariant.A, animated = true, color = IconColorSettings()))
            GLASS -> AccentPalettePreset.NIGHT_FLOW.apply(base).copy(frontendMode = FrontendMode.MODERN, appStyle = AppStyle.CYBER_GLASS, themeMode = ThemeMode.DARK,
                homeLayout = HomeLayout.POSTER_WALL, contentDensity = ContentDensity.COMFORTABLE,
                appFontChoice = AppFontChoice.NOTO_SANS, cornerScale = 1.2f, motionLevel = MotionLevel.FULL,
                navigationBarStyle = NavigationBarStyle.LIQUID_GLASS, glassQuality = GlassQuality.ADAPTIVE, detailCardStyle = DetailCardStyle.GLASS,
                detailLayout = DetailLayout.THEATER, floatingBarOpacity = 0.62f, floatingBarBlur = 22, pageTransitionStyle = PageTransitionStyle.PARALLAX,
                gridColumns = 3, coverTitlePosition = CoverTitlePosition.BELOW,
                icons = base.icons.copy(style = IconStyle.DUO, variant = IconVariant.B, animated = true, color = IconColorSettings(IconColorMode.ACCENT)))
            JOURNAL -> AccentPalettePreset.WARM.apply(base).copy(frontendMode = FrontendMode.MODERN, appStyle = AppStyle.ANIME_DYNAMIC, themeMode = ThemeMode.LIGHT,
                homeLayout = HomeLayout.COMPACT_INDEX, contentDensity = ContentDensity.COMFORTABLE,
                appFontChoice = AppFontChoice.WENKAI, fontScale = 1.08f, cornerScale = 1.3f, motionLevel = MotionLevel.REDUCED,
                navigationBarStyle = NavigationBarStyle.FLOATING, glassQuality = GlassQuality.BLUR, detailCardStyle = DetailCardStyle.TONAL,
                detailLayout = DetailLayout.MINIMAL, floatingBarOpacity = 0.96f, floatingBarBlur = 8, pageTransitionStyle = PageTransitionStyle.SOFT_FADE, gridColumns = 2,
                coverTitlePosition = CoverTitlePosition.BELOW,
                icons = base.icons.copy(style = IconStyle.LINE, variant = IconVariant.A, animated = false, color = IconColorSettings()))
            CLASSIC -> base.copy(frontendMode = FrontendMode.LEGACY, appStyle = AppStyle.MIUIX, themeMode = ThemeMode.SYSTEM,
                accentColor = 0xFF6750A4L, accentGradient = AccentGradientSettings(), homeLayout = HomeLayout.POSTER_WALL, contentDensity = ContentDensity.COMPACT,
                appFontChoice = AppFontChoice.SYSTEM, cornerScale = 0.65f, motionLevel = MotionLevel.REDUCED,
                legacyNavigationBarStyle = NavigationBarStyle.DEFAULT, glassQuality = GlassQuality.LOW, detailCardStyle = DetailCardStyle.OUTLINED,
                detailLayout = DetailLayout.CLASSIC, floatingBarOpacity = 1f, pageTransitionStyle = PageTransitionStyle.REFINED_SLIDE, gridColumns = 3,
                coverTitlePosition = CoverTitlePosition.BELOW,
                icons = base.icons.copy(style = IconStyle.LINE, variant = IconVariant.A, animated = false, color = IconColorSettings()))
        }
    }
}
