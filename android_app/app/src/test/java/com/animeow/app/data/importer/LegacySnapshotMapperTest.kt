package com.animeow.app.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySnapshotMapperTest {
    @Test
    fun acceptsEveryDeclaredLegacySchemaVersionWithOnlyCoreColumns() {
        (1..19).forEach { version ->
            val snapshot = LegacySnapshotMapper.map(
                sourceSchemaVersion = version,
                tables = mapOf(
                    "animes" to listOf(
                        mapOf("id" to version.toLong(), "title" to "v$version 作品"),
                    ),
                ),
            )

            assertEquals(version, snapshot.sourceSchemaVersion)
            assertEquals("v$version 作品", snapshot.animes.single().title)
            assertEquals("未看", snapshot.animes.single().status)
        }
    }

    @Test
    fun mapsCoreAnimeFieldsAndRepairsCompletedProgress() {
        val snapshot = LegacySnapshotMapper.map(
            sourceSchemaVersion = 19,
            tables = mapOf(
                "series" to listOf(
                    mapOf("id" to 4L, "name" to "主系列"),
                ),
                "animes" to listOf(
                    mapOf(
                        "id" to 9L,
                        "title" to "测试番剧",
                        "status" to "看完",
                        "series_id" to 4L,
                        "watched_episodes" to 8L,
                        "total_episodes" to 12L,
                        "rating_grade" to "A",
                        "fun_rating_tier" to "S",
                    ),
                ),
            ),
        )

        val anime = snapshot.animes.single()
        assertEquals("测试番剧", anime.title)
        assertEquals(12, anime.watchedEpisodes)
        assertEquals(12, anime.totalEpisodes)
        assertEquals(4L, anime.seriesId)
        assertEquals("A", anime.ratingGrade)
        assertEquals("S", anime.funRatingTier)
    }

    @Test
    fun dropsOrphanRelationsAndKeepsCustomStatuses() {
        val snapshot = LegacySnapshotMapper.map(
            sourceSchemaVersion = 6,
            tables = mapOf(
                "animes" to listOf(
                    mapOf("id" to 1L, "title" to "作品", "status" to "搁置"),
                ),
                "tags" to listOf(
                    mapOf("id" to 2L, "name" to "治愈"),
                ),
                "anime_tags" to listOf(
                    mapOf("anime_id" to 1L, "tag_id" to 2L),
                    mapOf("anime_id" to 99L, "tag_id" to 2L),
                ),
                "watch_records" to listOf(
                    mapOf("id" to 3L, "anime_id" to 99L, "episode" to 1L),
                ),
            ),
        )

        assertEquals(1, snapshot.animeTags.size)
        assertTrue(snapshot.watchRecords.isEmpty())
        assertTrue(snapshot.watchStatuses.any { it.name == "搁置" })
    }

    @Test
    fun invalidSeriesReferenceBecomesNull() {
        val snapshot = LegacySnapshotMapper.map(
            sourceSchemaVersion = 3,
            tables = mapOf(
                "animes" to listOf(
                    mapOf("id" to 1L, "title" to "作品", "series_id" to 404L),
                ),
            ),
        )

        assertNull(snapshot.animes.single().seriesId)
    }

    @Test
    fun preservesLegacyDecimalAndAlreadyScaledRatings() {
        val snapshot = LegacySnapshotMapper.map(
            sourceSchemaVersion = 19,
            tables = mapOf(
                "animes" to listOf(
                    mapOf("id" to 1L, "title" to "小数评分", "rating" to 8.5),
                    mapOf("id" to 2L, "title" to "已缩放评分", "rating" to 85L),
                ),
            ),
        )

        assertEquals(85, snapshot.animes[0].rating)
        assertEquals(85, snapshot.animes[1].rating)
    }

    @Test
    fun letterGradeWinsWhenLegacyRowContainsBothRatingModes() {
        val snapshot = LegacySnapshotMapper.map(
            sourceSchemaVersion = 19,
            tables = mapOf(
                "animes" to listOf(
                    mapOf(
                        "id" to 1L,
                        "title" to "双值异常记录",
                        "rating" to 9.5,
                        "rating_grade" to "b",
                    ),
                ),
            ),
        )

        assertEquals("B", snapshot.animes.single().ratingGrade)
        assertNull(snapshot.animes.single().rating)
    }

    @Test
    fun mapsCompleteV19CharacterGraphAndNormalizesRelations() {
        val snapshot = LegacySnapshotMapper.map(
            sourceSchemaVersion = 19,
            tables = mapOf(
                "animes" to listOf(mapOf("id" to 1L, "title" to "作品")),
                "characters" to listOf(
                    mapOf("id" to 10L, "bgm_id" to 100L, "name" to "角色 A", "rating" to 99L),
                    mapOf("id" to 20L, "bgm_id" to 200L, "name" to "角色 B"),
                ),
                "anime_characters" to listOf(
                    mapOf("anime_id" to 1L, "character_id" to 10L, "role_name" to "主角"),
                ),
                "character_relations" to listOf(
                    mapOf(
                        "id" to 1L,
                        "source_character_id" to 20L,
                        "target_character_id" to 10L,
                        "relation_type" to "搭档",
                        "strength" to 99L,
                    ),
                    mapOf(
                        "id" to 2L,
                        "source_character_id" to 10L,
                        "target_character_id" to 20L,
                        "relation_type" to "重复反向关系",
                    ),
                    mapOf(
                        "id" to 3L,
                        "source_character_id" to 10L,
                        "target_character_id" to 10L,
                    ),
                ),
                "character_tags" to listOf(mapOf("id" to 30L, "name" to "治愈")),
                "character_tag_links" to listOf(mapOf("character_id" to 10L, "tag_id" to 30L)),
                "character_groups" to listOf(mapOf("id" to 40L, "name" to "主角团")),
                "character_group_characters" to listOf(mapOf("group_id" to 40L, "character_id" to 10L)),
                "character_group_works" to listOf(mapOf("group_id" to 40L, "anime_id" to 1L)),
                "anime_analysis_records" to listOf(
                    mapOf("id" to 50L, "server_record_id" to 5L, "analysis" to "分析历史"),
                ),
            ),
        )

        assertEquals(2, snapshot.characters.size)
        assertEquals(10, snapshot.characters.first().rating)
        assertEquals(1, snapshot.animeCharacters.size)
        assertEquals(1, snapshot.characterRelations.size)
        snapshot.characterRelations.single().also {
            assertEquals(10L, it.sourceCharacterId)
            assertEquals(20L, it.targetCharacterId)
            assertEquals(5, it.strength)
            assertEquals("搭档", it.relationType)
        }
        assertEquals(1, snapshot.characterTagLinks.size)
        assertEquals(1, snapshot.characterGroupCharacters.size)
        assertEquals(1, snapshot.characterGroupWorks.size)
        assertEquals("分析历史", snapshot.animeAnalysisRecords.single().analysis)
    }

    @Test
    fun dropsInvalidIdsAndNumericOverflowInsteadOfCreatingBrokenLinks() {
        val snapshot = LegacySnapshotMapper.map(
            sourceSchemaVersion = 19,
            tables = mapOf(
                "animes" to listOf(
                    mapOf(
                        "id" to 1L,
                        "title" to "有效作品",
                        "total_episodes" to Long.MAX_VALUE,
                    ),
                    mapOf("id" to 0L, "title" to "无效零 ID"),
                ),
                "tags" to listOf(mapOf("id" to -2L, "name" to "无效标签")),
                "anime_tags" to listOf(mapOf("anime_id" to 1L, "tag_id" to -2L)),
            ),
        )

        assertEquals(1, snapshot.animes.size)
        assertEquals(0, snapshot.animes.single().totalEpisodes)
        assertTrue(snapshot.tags.isEmpty())
        assertTrue(snapshot.animeTags.isEmpty())
    }

    @Test
    fun keepsOnlyCompleteValidReminderPairs() {
        val snapshot = LegacySnapshotMapper.map(
            sourceSchemaVersion = 19,
            tables = mapOf(
                "animes" to listOf(
                    mapOf("id" to 1L, "title" to "有效提醒", "reminder_day" to 2L, "reminder_time" to "20:30"),
                    mapOf("id" to 2L, "title" to "无效日期", "reminder_day" to 9L, "reminder_time" to "20:30"),
                    mapOf("id" to 3L, "title" to "无效时间", "reminder_day" to 2L, "reminder_time" to "tomorrow"),
                ),
            ),
        )

        assertEquals(2, snapshot.animes[0].reminderDay)
        assertEquals("20:30", snapshot.animes[0].reminderTime)
        assertNull(snapshot.animes[1].reminderDay)
        assertNull(snapshot.animes[1].reminderTime)
        assertNull(snapshot.animes[2].reminderDay)
        assertNull(snapshot.animes[2].reminderTime)
    }
}
