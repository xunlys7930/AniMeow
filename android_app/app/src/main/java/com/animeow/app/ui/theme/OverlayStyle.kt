package com.animeow.app.ui.theme

enum class OverlayStyle(val storageKey: String, val displayName: String, val description: String) {
    CREAM_CAT("cream_cat", "奶油猫团", "奶白猫头与暖杏色面板"),
    MOON_GLASS("moon_glass", "月光琉璃", "冷色琉璃球与清透面板"),
    QUIET_PAW("quiet_paw", "静音猫爪", "深灰猫爪与杏色进度弧线");

    companion object {
        fun fromStorage(value: String?): OverlayStyle =
            entries.firstOrNull { it.storageKey == value } ?: QUIET_PAW
    }
}
