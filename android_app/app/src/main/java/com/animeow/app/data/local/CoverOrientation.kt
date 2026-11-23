package com.animeow.app.data.local

/** Per-work display override. Unknown/old values keep the layout's original ratio. */
fun coverAspectRatio(orientation: String?, fallback: Float): Float = when (orientation) {
    "portrait" -> 2f / 3f
    "landscape" -> 16f / 9f
    else -> fallback
}
