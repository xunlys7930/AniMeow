package com.animeow.app.data.preferences

import com.animeow.app.ui.icons.AppIconId
import com.animeow.app.ui.icons.IconOverride
import com.animeow.app.ui.icons.IconSettings
import com.animeow.app.ui.icons.IconStyle
import com.animeow.app.ui.icons.IconVariant
import org.json.JSONObject

/** Only stable semantic IDs are portable. Resource IDs and file paths never enter a profile. */
internal object IconSettingsCodec {
    fun toJson(settings: IconSettings): JSONObject = JSONObject()
        .put("style", settings.style.storageKey)
        .put("variant", settings.variant.storageKey)
        .put("animated", settings.animated)
        .put("color", colorJson(settings.color))
        .put("overrides", JSONObject().apply {
            AppIconId.entries.forEach { id ->
                settings.overrides[id]?.takeUnless { it.followsGlobal }?.let { choice ->
                    put(id.storageKey, JSONObject().apply {
                        choice.style?.let { put("style", it.storageKey) }
                        choice.variant?.let { put("variant", it.storageKey) }
                        choice.color?.let { put("color", colorJson(it)) }
                    })
                }
            }
        })

    fun fromJson(json: JSONObject?): IconSettings {
        if (json == null) return IconSettings()
        val overrides = json.optJSONObject("overrides")
        return IconSettings(
            style = IconStyle.fromStorage(json.optString("style")) ?: IconStyle.LINE,
            variant = IconVariant.fromStorage(json.optString("variant")) ?: IconVariant.A,
            animated = json.optBoolean("animated", true),
            color = colorFromJson(json.optJSONObject("color")),
            // Bound work and ignore unknown or malformed entries from future/untrusted profiles.
            overrides = buildMap {
                AppIconId.entries.forEach { id ->
                    overrides?.optJSONObject(id.storageKey)?.let { value ->
                        val choice = IconOverride(
                            style = IconStyle.fromStorage(value.optString("style")),
                            variant = IconVariant.fromStorage(value.optString("variant")),
                            color = value.optJSONObject("color")?.let(::colorFromJson),
                        )
                        if (!choice.followsGlobal) put(id, choice)
                    }
                }
            },
        )
    }

    private fun colorJson(value: com.animeow.app.ui.icons.IconColorSettings) = JSONObject()
        .put("mode", value.mode.storageKey).put("custom", value.customColor)

    private fun colorFromJson(json: JSONObject?): com.animeow.app.ui.icons.IconColorSettings =
        com.animeow.app.ui.icons.IconColorSettings(
            com.animeow.app.ui.icons.IconColorMode.fromStorage(json?.optString("mode")),
            ((json?.optLong("custom", 0xFF527D72L) ?: 0xFF527D72L) and 0xFFFFFFL) or 0xFF000000L,
        )

    fun fromStorage(value: String?): IconSettings = value?.takeIf { it.length <= 65_536 }
        ?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
        ?: IconSettings()
}
