package com.animeow.app.data.remote

internal val preferredRemoteLanguages = listOf("zh-Hans", "zh", "zh-Hant", "ja", "en", "ja-Latn", "ko", "und")

internal fun Map<String, String>.preferredLanguage(): String? =
    preferredRemoteLanguages.firstOrNull { !this[it].isNullOrBlank() } ?: keys.firstOrNull()

internal fun String?.isChineseLanguage() = this == "zh" || this?.startsWith("zh-") == true

internal fun localizedDiscoveryItem(current: RemoteAnime, update: RemoteAnime): RemoteAnime {
    if (current.importSource != update.importSource || current.importId != update.importId || current.subjectType != update.subjectType) return current
    val titles = current.localizedTitles + update.localizedTitles
    val summaries = current.localizedSummaries + update.localizedSummaries
    val titleLanguage = titles.preferredLanguage() ?: current.titleLanguage
    val summaryLanguage = summaries.preferredLanguage() ?: current.summaryLanguage
    return current.copy(
        title = titles[titleLanguage] ?: current.title,
        summary = summaries[summaryLanguage] ?: current.summary ?: update.summary,
        titleLanguage = titleLanguage, summaryLanguage = summaryLanguage,
        localizedTitles = titles, localizedSummaries = summaries,
        coverUrl = current.coverUrl ?: update.coverUrl, coverUrls = current.coverUrls + update.coverUrls,
        metadataReferences = current.metadataReferences + update.metadataReferences,
    )
}

internal fun remoteLanguageLabel(language: String?) = when (language) {
    "zh", "zh-Hans" -> "中文"
    "zh-Hant" -> "繁体中文"
    "ja" -> "日文"
    "en" -> "英文"
    "ja-Latn" -> "罗马字"
    "ko" -> "韩文"
    else -> "原文"
}
