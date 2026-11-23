package com.animeow.app.data.branding

import androidx.annotation.DrawableRes
import com.animeow.app.R

enum class SplashScaleMode(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    COVER("cover", "铺满", "裁切边缘并铺满屏幕，适合竖版封面"),
    CONTAIN("contain", "完整显示", "保留整张图片，空白区域使用所选背景"),
    STRETCH("stretch", "拉伸", "忽略原比例并填满屏幕"),
    ;

    companion object {
        fun fromStorage(value: String?): SplashScaleMode =
            entries.firstOrNull { it.storageKey == value } ?: COVER
    }
}

enum class SplashBackgroundMode(
    val storageKey: String,
    val displayName: String,
) {
    THEME("theme", "跟随主题"),
    BLACK("black", "纯黑"),
    WHITE("white", "纯白"),
    ACCENT("accent", "强调色"),
    ;

    companion object {
        fun fromStorage(value: String?): SplashBackgroundMode =
            entries.firstOrNull { it.storageKey == value } ?: THEME
    }
}

enum class LauncherIcon(
    val storageKey: String,
    val displayName: String,
    val aliasName: String,
    @param:DrawableRes val previewResource: Int,
    val enabledByDefault: Boolean = false,
) {
    DEFAULT("default", "原版默认", "icon_default", R.drawable.app_icon, enabledByDefault = true),
    MINT("mint", "薄荷放映", "icon_mint", R.drawable.brand_mint_preview),
    ICON_04("icon_04", "月光追番", "icon_04", R.drawable.brand_04_preview),
    ICON_06("icon_06", "樱花来信", "icon_06", R.drawable.brand_06_preview),
    ICON_07("icon_07", "晴空影院", "icon_07", R.drawable.brand_07_preview),
    ICON_08("icon_08", "奶油手账", "icon_08", R.drawable.brand_08_preview),
    ICON_09("icon_09", "海盐胶片", "icon_09", R.drawable.brand_09_preview),
    ICON_12("icon_12", "暮色放映", "icon_12", R.drawable.brand_12_preview),
    ICON_13("icon_13", "星夜频道", "icon_13", R.drawable.brand_13_preview),
    ICON_14("icon_14", "墨色猫影", "icon_14", R.drawable.brand_14_preview),
    ICON_15("icon_15", "白桃气泡", "icon_15", R.drawable.brand_15_preview),
    ICON_16("icon_16", "森林频道", "icon_16", R.drawable.brand_16_preview),
    ICON_18("icon_18", "莓果书签", "icon_18", R.drawable.brand_18_preview),
    ;

    companion object {
        fun fromStorage(value: String?): LauncherIcon = when (value) {
            "icon_02" -> DEFAULT
            else -> entries.firstOrNull { it.storageKey == value } ?: DEFAULT
        }

        fun fromLegacyAlias(value: String?): LauncherIcon =
            entries.firstOrNull { it.aliasName == value } ?: fromStorage(value)
    }
}

data class BrandingSettings(
    val customSplashEnabled: Boolean = false,
    val splashImagePath: String? = null,
    val splashDurationMillis: Int = 1_000,
    val splashScaleMode: SplashScaleMode = SplashScaleMode.COVER,
    val splashBackgroundMode: SplashBackgroundMode = SplashBackgroundMode.THEME,
    val splashFocalX: Float = 0.5f,
    val splashFocalY: Float = 0.5f,
    val tapToSkip: Boolean = true,
    val launcherIcon: LauncherIcon = LauncherIcon.DEFAULT,
    val customIconPath: String? = null,
) {
    fun normalized(): BrandingSettings = copy(
        customSplashEnabled = customSplashEnabled && !splashImagePath.isNullOrBlank(),
        splashImagePath = splashImagePath?.trim()?.takeIf(String::isNotBlank),
        customIconPath = customIconPath?.trim()?.takeIf { it.startsWith("branding/icon_") && !it.contains("..") && !it.contains('\\') },
        splashDurationMillis = splashDurationMillis.coerceIn(500, 5_000),
        splashFocalX = splashFocalX.coerceIn(0f, 1f),
        splashFocalY = splashFocalY.coerceIn(0f, 1f),
    )
}
