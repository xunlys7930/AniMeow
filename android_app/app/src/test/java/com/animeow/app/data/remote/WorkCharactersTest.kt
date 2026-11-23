package com.animeow.app.data.remote

import com.animeow.app.data.local.AnimeEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkCharactersTest {
    @Test fun linkedBangumiWorkLoadsItsCastWithoutSearchingCharacterOrWorkNames() = runBlocking {
        var requested: Long? = null
        val cast = listOf(RemoteCharacter(9, "本作角色", roleName = "主角"))
        val result = loadWorkCharacterCatalog(AnimeEntity(title = "名称并非角色名", externalSource = "bangumi", externalId = "123"),
            searchWorks = { _, _ -> error("Must not search names for a linked work") },
            loadCharacters = { requested = it; cast })
        assertEquals(123L, requested)
        assertEquals(cast, result.characters)
    }

    @Test fun unknownWorkRequiresChoosingAnExactWorkBeforeAnyCharacterLookup() = runBlocking {
        val candidates = listOf(RemoteAnime(RemoteCatalogSource.BANGUMI, "1", "同名动画"),
            RemoteAnime(RemoteCatalogSource.BANGUMI, "2", "同名动画 第二季"))
        val result = loadWorkCharacterCatalog(AnimeEntity(title = "同名动画"),
            searchWorks = { title, type -> assertEquals("同名动画", title); assertEquals("anime", type); candidates },
            loadCharacters = { error("Must not guess a work") })
        assertNull(result.subjectId)
        assertEquals(candidates, result.candidates)
        val selected = loadWorkCharacterCatalog(AnimeEntity(title = "同名动画"), candidates.last(),
            searchWorks = { _, _ -> error("No search after choosing a work") }, loadCharacters = { assertEquals(2L, it); emptyList() })
        assertEquals(2L, selected.subjectId)
    }

    @Test fun legacySubjectLinksResolveButUnrelatedNumericIdsDoNot() {
        assertEquals(55L, bangumiSubjectId(AnimeEntity(title = "旧资料", externalUrl = "https://bgm.tv/subject/55")))
        assertNull(bangumiSubjectId(AnimeEntity(title = "其他来源", externalSource = "anilist", externalId = "55")))
        assertNull(bangumiSubjectId(AnimeEntity(title = "无关网址", externalUrl = "https://example.com/subject/55")))
    }
}
