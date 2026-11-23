package com.animeow.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class BangumiCharacterParsingTest {
    @Test fun subjectCastKeepsRelationsAndDistinctIds() {
        val result = BangumiService().parseSubjectCharacters("""[
            {"id":9,"name":"角色甲","relation":"主角","images":{"medium":"https://lain.bgm.tv/a.jpg"}},
            {"id":10,"name":"角色乙","relation":"配角"},
            {"id":9,"name":"角色甲","relation":"主角"}
        ]""")
        org.junit.Assert.assertEquals(listOf(9L, 10L), result.map { it.id })
        org.junit.Assert.assertEquals("主角", result.first().roleName)
    }
    @Test
    fun parsesSearchCharacterAndLocalizedInfoboxName() {
        val result = BangumiService().parseCharacterSearchResponse(
            """
            {
              "data": [{
                "id": 123,
                "name": "Frieren",
                "images": {"large": "https://example.com/frieren.jpg"},
                "summary": "精灵魔法使",
                "gender": "女",
                "birth_mon": 1,
                "birth_day": 1,
                "blood_type": "A",
                "infobox": [{"key": "简体中文名", "value": "芙莉莲"}]
              }]
            }
            """.trimIndent(),
        )

        assertEquals(1, result.size)
        assertEquals(123L, result.single().id)
        assertEquals("芙莉莲", result.single().displayName)
        assertEquals(1, result.single().birthMonth)
        assertTrue(result.single().infoboxJson.contains("简体中文名"))
    }
}
