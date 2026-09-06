package com.animeow.app.data.remote

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
) {
    val displayName: String
        get() = nameCn?.takeIf(String::isNotBlank) ?: name
}
