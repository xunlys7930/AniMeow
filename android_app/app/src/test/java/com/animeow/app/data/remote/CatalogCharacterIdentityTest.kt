package com.animeow.app.data.remote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CatalogCharacterIdentityTest {
    @Test fun cachePrimaryKeyNeverBecomesARemoteCharacterId() {
        val remote = parseCatalogCharacter(JSONObject("""{"id":4,"catalog_id":4,"bgm_id":904,"source":"bangumi","cached":true,"name":"小喵"}"""))!!
        assertEquals(904L, remote.id)
        assertEquals(4L, remote.catalogId)
        assertEquals("bangumi:904", remote.sourceKey)
        assertNull(parseCatalogCharacter(JSONObject("""{"id":4,"source":"bangumi","cached":true,"name":"未知来源"}""")))
    }

    @Test fun twoSourcesCanUseTheSameNumericIdWithoutBeingTheSameCharacter() {
        val bangumi = parseCatalogCharacter(JSONObject("""{"source_id":42,"source":"bangumi","name":"Alice"}"""))!!
        val anilist = parseCatalogCharacter(JSONObject("""{"anilist_id":42,"source":"anilist","name":"Alice"}"""))!!
        assertNotEquals(bangumi.sourceKey, anilist.sourceKey)
        assertEquals(RemoteCharacterSource.ANILIST, anilist.source)
    }
}
