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
    DEFAULT("default", "默认", "icon_default", R.drawable.app_icon, enabledByDefault = true),
    ICON_04("icon_04", "图标 04", "icon_04", R.mipmap.ic_launcher_04),
    ICON_06("icon_06", "图标 06", "icon_06", R.mipmap.ic_launcher_06),
    ICON_07("icon_07", "图标 07", "icon_07", R.mipmap.ic_launcher_07),
    ICON_08("icon_08", "图标 08", "icon_08", R.mipmap.ic_launcher_08),
    ICON_09("icon_09", "图标 09", "icon_09", R.mipmap.ic_launcher_09),
    ICON_12("icon_12", "图标 12", "icon_12", R.mipmap.ic_launcher_12),
    ICON_13("icon_13", "图标 13", "icon_13", R.mipmap.ic_launcher_13),
    ICON_14("icon_14", "图标 14", "icon_14", R.mipmap.ic_launcher_14),
    ICON_15("icon_15", "图标 15", "icon_15", R.mipmap.ic_launcher_15),
    ICON_16("icon_16", "图标 16", "icon_16", R.mipmap.ic_launcher_16),
    ICON_18("icon_18", "图标 18", "icon_18", R.mipmap.ic_launcher_18),
    ;

    companion object {
        fun fromStorage(value: String?): LauncherIcon = when (value) {
            "icon_02" -> DEFAULT
            else -> entries.firstOrNull { it.storageKey == value } ?: DEFAULT
        }

        fun fromLegacyAlias(value: String?): LauncherIcon = fromStorage(value ?: "default")
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
) {
    fun normalized(): BrandingSettings = copy(
        customSplashEnabled = customSplashEnabled && !splashImagePath.isNullOrBlank(),
        splashImagePath = splashImagePath?.trim()?.takeIf(String::isNotBlank),
        splashDurationMillis = splashDurationMillis.coerceIn(500, 5_000),
        splashFocalX = splashFocalX.coerceIn(0f, 1f),
        splashFocalY = splashFocalY.coerceIn(0f, 1f),
    )
}
