package com.animeow.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BangumiCharacterParsingTest {
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
