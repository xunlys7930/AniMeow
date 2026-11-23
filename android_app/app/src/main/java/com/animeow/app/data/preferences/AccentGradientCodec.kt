package com.animeow.app.data.preferences

import com.animeow.app.ui.theme.AccentGradientSettings
import com.animeow.app.ui.theme.AccentMode
import org.json.JSONArray
import org.json.JSONObject

internal object AccentGradientCodec {
    fun toJson(value: AccentGradientSettings): JSONObject = value.normalized().let {
        JSONObject().put("mode", it.mode.storageKey).put("colors", JSONArray(it.colors))
            .put("angle", it.angleDegrees.toDouble()).put("durationMillis", it.durationMillis)
    }
    fun fromJson(json: JSONObject?): AccentGradientSettings {
        if (json == null) return AccentGradientSettings()
        val colors = json.optJSONArray("colors")
        return AccentGradientSettings(
            mode = AccentMode.fromStorage(json.optString("mode")),
            colors = (0 until minOf(colors?.length() ?: 0, 4)).mapNotNull { index ->
                (colors?.opt(index) as? Number)?.toLong()?.takeIf { it in 0..0xFFFFFFFFL }
            },
            angleDegrees = json.optDouble("angle", 35.0).toFloat(),
            durationMillis = json.optInt("durationMillis", 8_000),
        ).normalized()
    }
    fun fromStorage(value: String?): AccentGradientSettings = value?.takeIf { it.length < 4_096 }
        ?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() } ?: AccentGradientSettings()
}
