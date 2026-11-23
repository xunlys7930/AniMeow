package com.animeow.app.data.local

import java.util.Locale

const val SUBJECT_TYPE_ANIME = "anime"
const val SUBJECT_TYPE_BOOK = "book"
const val SUBJECT_TYPE_ALL = "all"

/** AniMeow only stores and exposes two work types. */
fun normalizeSubjectType(value: String?): String = when (value.normalizedSubjectTypeKey()) {
    SUBJECT_TYPE_BOOK, "manga", "novel" -> SUBJECT_TYPE_BOOK
    else -> SUBJECT_TYPE_ANIME
}

fun normalizeSubjectTypeFilter(value: String?): String = when (value.normalizedSubjectTypeKey()) {
    SUBJECT_TYPE_ALL -> SUBJECT_TYPE_ALL
    SUBJECT_TYPE_ANIME -> SUBJECT_TYPE_ANIME
    SUBJECT_TYPE_BOOK, "manga", "novel" -> SUBJECT_TYPE_BOOK
    else -> SUBJECT_TYPE_ALL
}

fun subjectTypeDisplayName(value: String?): String = when (normalizeSubjectType(value)) {
    SUBJECT_TYPE_BOOK -> "书籍"
    else -> "动画"
}

fun String?.isBookSubjectType(): Boolean = normalizeSubjectType(this) == SUBJECT_TYPE_BOOK

fun AnimeEntity.withNormalizedSubjectType(): AnimeEntity = copy(
    subjectType = normalizeSubjectType(subjectType),
)

private fun String?.normalizedSubjectTypeKey(): String =
    orEmpty().trim().lowercase(Locale.ROOT)
