package com.animeow.app.data.background

enum class BackgroundScaleMode(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    COVER("cover", "铺满", "裁切边缘铺满屏幕，适合作为全屏氛围壁纸"),
    CONTAIN("contain", "完整显示", "保留原图比例居中展示"),
    STRETCH("stretch", "拉伸", "忽略原比例并填满整个页面");

    companion object {
        fun fromStorage(value: String?): BackgroundScaleMode =
            entries.firstOrNull { it.storageKey == value } ?: COVER
    }
}

/** Pre-tuned readable background color presets (Hex ARGB as Long) */
val DEFAULT_BACKGROUND_COLOR_PRESETS: List<Long> = listOf(
    0xFF000000L, // OLED 纯黑
    0xFF121216L, // 深空灰黑
    0xFF181B20L, // 极夜蓝黑
    0xFFF7F7FAL, // 极简冷灰 (MIUIX 原版浅色)
    0xFFFFFFFFL, // 纯白
    0xFFEAF4ECL, // 护眼豆沙绿
    0xFFFAF6EEL, // 暖纸米黄
    0xFFF0F4F8L, // 浅暮淡蓝
    0xFFFFF0F5L, // 樱粉淡白
)
