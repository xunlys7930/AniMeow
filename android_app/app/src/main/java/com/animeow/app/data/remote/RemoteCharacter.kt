package com.animeow.app.data.remote

enum class RemoteCharacterSource(val key: String, val label: String) {
    BANGUMI("bangumi", "Bangumi"),
    ANILIST("anilist", "AniList"),
}

data class RemoteCharacter(
    val id: Long,
    val name: String,
    val nameCn: String? = null,
    val imageUrl: String? = null,
    val summary: String? = null,
    val gender: String? = null,
    val birthYear: Int? = null,
    val birthMonth: Int? = null,
    val birthDay: Int? = null,
    val bloodType: String? = null,
    val infoboxJson: String = "[]",
    val roleName: String? = null,
    val source: RemoteCharacterSource = RemoteCharacterSource.BANGUMI,
    val catalogId: Long? = null,
) {
    val displayName: String
        get() = nameCn?.takeIf(String::isNotBlank) ?: name
    val sourceKey: String get() = "${source.key}:$id"
}

/** Cached `id` is the server's primary key, never a Bangumi/AniList identifier. */
internal fun parseCatalogCharacter(item: org.json.JSONObject): RemoteCharacter? {
    fun text(key: String) = if (item.isNull(key)) null else item.optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
    fun id(key: String) = item.optLong(key, 0).takeIf { it > 0 }
    val source = when (text("source")) {
        "bangumi" -> RemoteCharacterSource.BANGUMI
        "anilist" -> RemoteCharacterSource.ANILIST
        else -> when {
            id("bgm_id") != null -> RemoteCharacterSource.BANGUMI
            id("anilist_id") != null -> RemoteCharacterSource.ANILIST
            else -> return null
        }
    }
    val remoteId = id(if (source == RemoteCharacterSource.BANGUMI) "bgm_id" else "anilist_id")
        ?: id("source_id")
        ?: id("id").takeIf { item.has("cached") && !item.optBoolean("cached", true) }
        ?: return null
    return RemoteCharacter(
        id = remoteId, source = source,
        catalogId = id("catalog_id") ?: id("id").takeIf { item.optBoolean("cached", false) },
        name = text("name") ?: text("name_cn") ?: return null,
        nameCn = text("name_cn"), imageUrl = text("image_proxy_url") ?: text("image_url"),
        summary = text("summary"), gender = text("gender"), roleName = text("role_name"),
        birthYear = id("birth_year")?.toInt(), birthMonth = id("birth_month")?.toInt(), birthDay = id("birth_day")?.toInt(),
        bloodType = text("blood_type"), infoboxJson = text("infobox_json") ?: "[]",
    )
}
