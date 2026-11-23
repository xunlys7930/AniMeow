package com.animeow.app.data.remote

import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.local.sourceReferences
import java.net.URI

data class WorkCharacterCatalog(
    val subjectId: Long? = null,
    val subjectTitle: String = "",
    val characters: List<RemoteCharacter> = emptyList(),
    val candidates: List<RemoteAnime> = emptyList(),
    val source: RemoteCharacterSource = RemoteCharacterSource.BANGUMI,
)

internal fun bangumiSubjectId(anime: AnimeEntity): Long? {
    anime.sourceReferences().firstNotNullOfOrNull { reference ->
        reference.externalId?.toLongOrNull()?.takeIf { reference.source.equals("bangumi", true) && it > 0 }
    }?.let { return it }
    if (anime.externalSource.equals("bangumi", true)) anime.externalId?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
    val uri = runCatching { URI(anime.externalUrl.orEmpty()) }.getOrNull() ?: return null
    if (uri.host?.lowercase() !in setOf("bgm.tv", "bangumi.tv", "bangumi.in") || uri.userInfo != null) return null
    return Regex("^/subject/([0-9]+)/?$").matchEntire(uri.path.orEmpty())?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
}

internal suspend fun loadWorkCharacterCatalog(
    anime: AnimeEntity,
    selected: RemoteAnime? = null,
    searchWorks: suspend (String, String) -> List<RemoteAnime>,
    loadCharacters: suspend (Long) -> List<RemoteCharacter>,
): WorkCharacterCatalog {
    val type = normalizeSubjectType(anime.subjectType)
    val selectedId = selected?.takeIf { it.importSource == "bangumi" && normalizeSubjectType(it.subjectType) == type }
        ?.importId?.toLongOrNull()?.takeIf { it > 0 }
    val id = selectedId ?: bangumiSubjectId(anime)
    return if (id != null) WorkCharacterCatalog(id, selected?.title ?: anime.title, loadCharacters(id))
    else WorkCharacterCatalog(subjectTitle = anime.title, candidates = searchWorks(anime.title, type))
}
