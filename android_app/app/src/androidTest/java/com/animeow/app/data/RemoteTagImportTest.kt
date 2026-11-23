package com.animeow.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.RemoteCatalogSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteTagImportTest {
    @Test fun importAndCompletionRespectBlockedAliasesAndAvoidDuplicateTags() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AniMeowDatabase::class.java).build()
        try {
            val dao = db.libraryDao()
            val repository = LibraryRepository(db)
            val blocked = dao.insertTag(TagEntity(name = "轻百合", blocked = true))
            val ova = dao.insertTag(TagEntity(name = "OVA"))
            val remote = RemoteAnime(RemoteCatalogSource.BANGUMI, "39123", "标签回归作品", tags = listOf("輕百。", "ＯＶＡ！", "OVA", "。…"))
            repository.importRemoteAnime(remote)
            val anime = repository.getActiveAnimesSnapshot().single()
            assertEquals(listOf(ova), repository.getTagIdsForAnime(anime.id))
            repository.applyRemoteMetadata(anime.id, remote, RemoteMetadataFields(tags = true), overwriteExisting = true)
            assertEquals(listOf(ova), repository.getTagIdsForAnime(anime.id))
            assertTrue(dao.getTag(blocked)!!.blocked)
            assertEquals(2, dao.getAllTags().size)
        } finally { db.close() }
    }
}
