package com.animeow.app.data.backup

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class NativeBackupMergerTest {
    @Test fun privateMemoAndCrossMediaSeriesLinksMergeLosslesslyWithCanonicalGameSyncId() {
        val old = "2026-10-20T00:00:00Z"
        val recent = "2026-10-21T00:00:00Z"
        val local = snapshot(
            "games" to rows(row("id" to 5, "syncId" to "local-game-sync", "bangumiId" to 888, "updatedAt" to old)),
            "series" to rows(row("id" to 1, "name" to "跨媒体企划", "linkedMediaJson" to """[{"kind":"AUDIO","workSyncId":"audio-1"}]""")),
            "animes" to rows(
                row("id" to 1, "title" to "作品甲", "externalSource" to "bangumi", "externalId" to "10", "memo" to "本地备注优先"),
                row("id" to 2, "title" to "作品乙", "externalSource" to "bangumi", "externalId" to "11", "memo" to null),
            ),
        )
        val remote = snapshot(
            "games" to rows(row("id" to 2, "syncId" to "remote-game-sync", "bangumiId" to 888, "updatedAt" to recent)),
            "series" to rows(row(
                "id" to 9,
                "name" to "跨媒体企划",
                "linkedMediaJson" to """[{"kind":"GAME","workSyncId":"remote-game-sync","role":"原作"},{"kind":"AUDIO","workSyncId":"audio-1","role":"番外／衍生"}]""",
            )),
            "animes" to rows(
                row("id" to 1, "title" to "作品甲", "externalSource" to "bangumi", "externalId" to "10", "memo" to "远端备注"),
                row("id" to 2, "title" to "作品乙", "externalSource" to "bangumi", "externalId" to "11", "memo" to "  填补缺失备注  "),
            ),
        )
        val merged = NativeBackupMerger.merge(local, remote, true, false)
        val animes = (0 until merged.getJSONArray("animes").length()).map { merged.getJSONArray("animes").getJSONObject(it) }
            .associateBy { it.getString("title") }
        assertEquals("本地备注优先", animes.getValue("作品甲").getString("memo"))
        assertEquals("填补缺失备注", animes.getValue("作品乙").getString("memo"))
        val linked = com.animeow.app.data.local.parseSeriesLinkedMedia(merged.singleRow("series").getString("linkedMediaJson"))
        assertEquals(
            listOf(
                com.animeow.app.data.local.SeriesLinkedMediaItem(kind = "AUDIO", workSyncId = "audio-1", role = "番外／衍生"),
                com.animeow.app.data.local.SeriesLinkedMediaItem(kind = "GAME", workSyncId = "local-game-sync", role = "原作"),
            ),
            linked,
        )
    }

    @Test fun seriesRolesFollowMappedSeriesAndDoNotCrossDifferentLocalGroups() {
        fun data(seriesId: Int, name: String, role: String?) = snapshot(
            "series" to rows(row("id" to seriesId, "name" to name)),
            "animes" to rows(row("id" to 1, "title" to "同一作品", "externalSource" to "bangumi", "externalId" to "12", "seriesId" to seriesId, "seriesRole" to role)))
        val incoming = data(99, "同系列", "原作")
        val mapped = NativeBackupMerger.merge(data(7, "同系列", null), incoming, true, false).singleRow("animes")
        assertEquals(7L, mapped.getLong("seriesId")); assertEquals("原作", mapped.getString("seriesRole"))
        val different = NativeBackupMerger.merge(data(7, "本地系列", null), incoming, true, false).singleRow("animes")
        assertEquals(7L, different.getLong("seriesId")); assertTrue(different.isNull("seriesRole"))
    }

    @Test
    fun remappedCharacterIdsCarryRolesAndPreserveExplicitBlank() {
        val local = snapshot("characters" to rows(
            row("id" to 1, "name" to "儿子"), row("id" to 9, "name" to "父亲")),
        )
        val remote = snapshot("characters" to rows(
            row("id" to 1, "name" to "父亲", "imageDisplayMode" to "fit"),
            row("id" to 2, "name" to "儿子")),
            "character_relations" to rows(row("id" to 1, "sourceCharacterId" to 1,
                "targetCharacterId" to 2, "relationType" to "父子", "sourceRole" to "父亲", "targetRole" to "")),
        )
        val merged = NativeBackupMerger.merge(local, remote, true, false)
        val relation = merged.getJSONArray("character_relations").getJSONObject(0)
        assertEquals(1L, relation.getLong("sourceCharacterId"))
        assertEquals(9L, relation.getLong("targetCharacterId"))
        assertEquals("", relation.getString("sourceRole"))
        assertEquals("父亲", relation.getString("targetRole"))
        assertEquals("fit", merged.getJSONArray("characters").getJSONObject(1).getString("imageDisplayMode"))
        val renamed = JSONObject(remote.toString())
        renamed.getJSONArray("character_relations").getJSONObject(0).put("targetRole", "儿子")
        val again = NativeBackupMerger.merge(merged, renamed, true, false)
        assertEquals("", again.getJSONArray("character_relations").getJSONObject(0).getString("sourceRole"))
    }

    @Test
    fun actorAndCoverFieldsKeepLocalValuesAndFillOnlyGaps() {
        fun data(actor: String, orientation: String) = snapshot(
            "animes" to rows(row("id" to 1, "title" to "作品", "subjectType" to "anime",
                "voiceActors" to actor, "coverOrientation" to orientation)),
            "characters" to rows(row("id" to 1, "name" to "角色")),
            "anime_characters" to rows(row("animeId" to 1, "characterId" to 1, "voiceActor" to actor)),
        )
        val remote = data("远端声优", "landscape")
        val kept = NativeBackupMerger.merge(data("本地声优", "portrait"), remote, true, false)
        assertEquals("本地声优", kept.getJSONArray("animes").getJSONObject(0).getString("voiceActors"))
        assertEquals("portrait", kept.getJSONArray("animes").getJSONObject(0).getString("coverOrientation"))
        assertEquals("本地声优", kept.getJSONArray("anime_characters").getJSONObject(0).getString("voiceActor"))
        val filled = NativeBackupMerger.merge(data("", ""), remote, true, false)
        assertEquals("远端声优", filled.getJSONArray("animes").getJSONObject(0).getString("voiceActors"))
        assertEquals("landscape", filled.getJSONArray("animes").getJSONObject(0).getString("coverOrientation"))
        assertEquals("远端声优", filled.getJSONArray("anime_characters").getJSONObject(0).getString("voiceActor"))
    }

    @Test
    fun mergesSameAnimeWithoutReplacingLocalUserFields() {
        val local = snapshot(
            "animes" to rows(
                row(
                    "id" to 1,
                    "title" to "作品",
                    "subjectType" to "anime",
                    "externalSource" to "bangumi",
                    "externalId" to "42",
                    "status" to "在看",
                    "rating" to 90,
                    "watchedEpisodes" to 3,
                    "broadcastDay" to 3,
                    "broadcastTime" to "22:00",
                    "totalEpisodes" to 12,
                    "deletedAt" to JSONObject.NULL,
                ),
            ),
        )
        val remote = snapshot(
            "animes" to rows(
                row(
                    "id" to 9,
                    "title" to "作品",
                    "subjectType" to "anime",
                    "externalSource" to "bangumi",
                    "externalId" to "42",
                    "status" to "看完",
                    "rating" to 70,
                    "watchedEpisodes" to 8,
                    "broadcastDay" to 5,
                    "broadcastTime" to "23:00",
                    "totalEpisodes" to 12,
                    "synopsis" to "远端补齐的简介",
                    "deletedAt" to "2026-01-01T00:00:00Z",
                ),
            ),
        )

        val merged = NativeBackupMerger.merge(local, remote, includeCore = true, includeAnalysis = false)
        val anime = merged.getJSONArray("animes").getJSONObject(0)

        assertEquals(1, merged.getJSONArray("animes").length())
        assertEquals("在看", anime.getString("status"))
        assertEquals(3, anime.getInt("broadcastDay"))
        assertEquals("22:00", anime.getString("broadcastTime"))
        assertEquals(90, anime.getInt("rating"))
        assertEquals(12, anime.getInt("watchedEpisodes"))
        assertEquals("远端补齐的简介", anime.getString("synopsis"))
        assertNull(anime.opt("deletedAt").takeUnless { it == JSONObject.NULL })
    }

    @Test
    fun remapsCollidingIdsAndKeepsRemoteRelations() {
        val local = snapshot(
            "animes" to rows(
                row(
                    "id" to 1,
                    "title" to "本机作品",
                    "subjectType" to "anime",
                    "externalSource" to "bangumi",
                    "externalId" to "1",
                ),
            ),
            "tags" to rows(row("id" to 1, "name" to "本机标签")),
        )
        val remote = snapshot(
            "animes" to rows(
                row(
                    "id" to 1,
                    "title" to "远端作品",
                    "subjectType" to "anime",
                    "externalSource" to "bangumi",
                    "externalId" to "2",
                ),
            ),
            "tags" to rows(row("id" to 1, "name" to "远端标签")),
            "anime_tags" to rows(row("animeId" to 1, "tagId" to 1)),
        )

        val merged = NativeBackupMerger.merge(local, remote, includeCore = true, includeAnalysis = false)
        val remoteAnime = merged.getJSONArray("animes").getJSONObject(1)
        val remoteTag = merged.getJSONArray("tags").getJSONObject(1)
        val link = merged.getJSONArray("anime_tags").getJSONObject(0)

        assertEquals(2L, remoteAnime.getLong("id"))
        assertEquals(2L, remoteTag.getLong("id"))
        assertEquals(2L, link.getLong("animeId"))
        assertEquals(2L, link.getLong("tagId"))
    }

    @Test
    fun remapsCompleteCharacterGraphAcrossCollidingIds() {
        val local = snapshot(
            "animes" to rows(row("id" to 1, "title" to "本机作品", "externalSource" to "bangumi", "externalId" to "1")),
            "characters" to rows(row("id" to 1, "bgmId" to 100, "name" to "本机角色")),
            "character_tags" to rows(row("id" to 1, "name" to "本机角色标签")),
            "character_groups" to rows(row("id" to 1, "name" to "本机角色组")),
        )
        val remote = snapshot(
            "animes" to rows(row("id" to 1, "title" to "远端作品", "externalSource" to "bangumi", "externalId" to "2")),
            "characters" to rows(
                row("id" to 1, "bgmId" to 200, "name" to "远端角色 A"),
                row("id" to 2, "bgmId" to 201, "name" to "远端角色 B"),
            ),
            "character_tags" to rows(row("id" to 1, "name" to "远端角色标签")),
            "character_groups" to rows(row("id" to 1, "name" to "远端角色组")),
            "anime_characters" to rows(row("animeId" to 1, "characterId" to 1, "roleName" to "主角")),
            "character_relations" to rows(
                row("id" to 1, "sourceCharacterId" to 2, "targetCharacterId" to 1, "relationType" to "搭档"),
            ),
            "character_tag_links" to rows(row("characterId" to 1, "tagId" to 1)),
            "character_group_characters" to rows(row("groupId" to 1, "characterId" to 2, "roleName" to "成员")),
            "character_group_works" to rows(row("groupId" to 1, "animeId" to 1)),
        )

        val merged = NativeBackupMerger.merge(local, remote, includeCore = true, includeAnalysis = false)

        assertEquals(listOf(1L, 2L), merged.ids("animes"))
        assertEquals(listOf(1L, 2L, 3L), merged.ids("characters"))
        assertEquals(listOf(1L, 2L), merged.ids("character_tags"))
        assertEquals(listOf(1L, 2L), merged.ids("character_groups"))
        merged.singleRow("anime_characters").also {
            assertEquals(2L, it.getLong("animeId"))
            assertEquals(2L, it.getLong("characterId"))
        }
        merged.singleRow("character_relations").also {
            assertEquals(2L, it.getLong("sourceCharacterId"))
            assertEquals(3L, it.getLong("targetCharacterId"))
        }
        merged.singleRow("character_tag_links").also {
            assertEquals(2L, it.getLong("characterId"))
            assertEquals(2L, it.getLong("tagId"))
        }
        merged.singleRow("character_group_characters").also {
            assertEquals(2L, it.getLong("groupId"))
            assertEquals(3L, it.getLong("characterId"))
        }
        merged.singleRow("character_group_works").also {
            assertEquals(2L, it.getLong("groupId"))
            assertEquals(2L, it.getLong("animeId"))
        }
    }

    @Test
    fun keepsLocalRelationshipAndPrivacyFieldsWhenLogicalRecordsMatch() {
        val local = snapshot(
            "characters" to rows(
                row("id" to 5, "bgmId" to 100, "name" to "角色 A"),
                row("id" to 6, "bgmId" to 101, "name" to "角色 B"),
            ),
            "character_relations" to rows(
                row(
                    "id" to 1,
                    "sourceCharacterId" to 5,
                    "targetCharacterId" to 6,
                    "relationType" to "朋友",
                    "strength" to 2,
                    "note" to JSONObject.NULL,
                ),
            ),
            "character_groups" to rows(
                row("id" to 7, "name" to "私有组", "communityId" to "group-1", "isPublic" to false),
            ),
        )
        val remote = snapshot(
            "characters" to rows(
                row("id" to 50, "bgmId" to 100, "name" to "角色 A"),
                row("id" to 60, "bgmId" to 101, "name" to "角色 B"),
            ),
            "character_relations" to rows(
                row(
                    "id" to 9,
                    "sourceCharacterId" to 60,
                    "targetCharacterId" to 50,
                    "relationType" to "敌对",
                    "strength" to 5,
                    "note" to "远端补齐备注",
                ),
            ),
            "character_groups" to rows(
                row(
                    "id" to 70,
                    "name" to "远端名称",
                    "communityId" to "group-1",
                    "isPublic" to true,
                    "coverUrl" to "https://example.com/group.jpg",
                ),
            ),
        )

        val merged = NativeBackupMerger.merge(local, remote, includeCore = true, includeAnalysis = false)
        val relation = merged.singleRow("character_relations")
        val group = merged.singleRow("character_groups")

        assertEquals("朋友", relation.getString("relationType"))
        assertEquals(2, relation.getInt("strength"))
        assertEquals("远端补齐备注", relation.getString("note"))
        assertFalse(group.getBoolean("isPublic"))
        assertEquals("https://example.com/group.jpg", group.getString("coverUrl"))
    }

    @Test
    fun mergesAnalysisHistoryByServerIdentityAndRemapsIdCollisions() {
        val local = snapshot(
            "anime_analysis_records" to rows(
                row("id" to 1, "serverRecordId" to 10, "analysis" to "保留本机分析", "statsJson" to JSONObject.NULL),
            ),
        )
        val remote = snapshot(
            "anime_analysis_records" to rows(
                row("id" to 9, "serverRecordId" to 10, "analysis" to "远端分析", "statsJson" to "{\"count\":1}"),
                row("id" to 1, "serverRecordId" to 11, "analysis" to "远端新增分析"),
            ),
        )

        val merged = NativeBackupMerger.merge(local, remote, includeCore = false, includeAnalysis = true)
        val records = merged.getJSONArray("anime_analysis_records")

        assertEquals(2, records.length())
        assertEquals("保留本机分析", records.getJSONObject(0).getString("analysis"))
        assertEquals("{\"count\":1}", records.getJSONObject(0).getString("statsJson"))
        assertEquals(2L, records.getJSONObject(1).getLong("id"))
        assertEquals(11L, records.getJSONObject(1).getLong("serverRecordId"))
    }

    @Test
    fun matchesRemoteExternalAnimeToManualLocalTitleAndCopiesIdentityAsOneUnit() {
        val local = snapshot(
            "animes" to rows(
                row(
                    "id" to 1,
                    "title" to "同一作品",
                    "subjectType" to "anime",
                    "externalSource" to "legacy",
                    "externalId" to JSONObject.NULL,
                    "externalUrl" to "https://local.invalid/item",
                ),
            ),
        )
        val remote = snapshot(
            "animes" to rows(
                row(
                    "id" to 9,
                    "title" to "同一作品",
                    "subjectType" to "anime",
                    "airDate" to "2026-04-01",
                    "externalSource" to "Bangumi",
                    "externalId" to "42",
                    "externalUrl" to "https://bgm.tv/subject/42",
                ),
            ),
        )

        val merged = NativeBackupMerger.merge(local, remote, includeCore = true, includeAnalysis = false)
        val anime = merged.singleRow("animes")

        assertEquals("Bangumi", anime.getString("externalSource"))
        assertEquals("42", anime.getString("externalId"))
        assertEquals("https://bgm.tv/subject/42", anime.getString("externalUrl"))
    }

    @Test
    fun doesNotUseTitleFallbackWhenExternalIdentitiesConflict() {
        val local = snapshot(
            "animes" to rows(
                row(
                    "id" to 1,
                    "title" to "重名作品",
                    "subjectType" to "anime",
                    "airDate" to "2025-01-01",
                    "externalSource" to "bangumi",
                    "externalId" to "1",
                ),
            ),
        )
        val remote = snapshot(
            "animes" to rows(
                row(
                    "id" to 9,
                    "title" to "重名作品",
                    "subjectType" to "anime",
                    "airDate" to "2025-07-01",
                    "externalSource" to "bangumi",
                    "externalId" to "2",
                ),
            ),
        )

        val merged = NativeBackupMerger.merge(local, remote, includeCore = true, includeAnalysis = false)

        assertEquals(2, merged.getJSONArray("animes").length())
    }

    @Test
    fun keepsRatingAndReminderFieldsAtomicAcrossDevices() {
        val local = snapshot(
            "animes" to rows(
                row(
                    "id" to 1,
                    "title" to "作品",
                    "externalSource" to "bangumi",
                    "externalId" to "42",
                    "rating" to 90,
                    "ratingGrade" to JSONObject.NULL,
                    "reminderDay" to 2,
                    "reminderTime" to JSONObject.NULL,
                ),
            ),
        )
        val remote = snapshot(
            "animes" to rows(
                row(
                    "id" to 9,
                    "title" to "作品",
                    "externalSource" to "bangumi",
                    "externalId" to "42",
                    "rating" to JSONObject.NULL,
                    "ratingGrade" to "A",
                    "reminderDay" to JSONObject.NULL,
                    "reminderTime" to "21:00",
                ),
            ),
        )

        val anime = NativeBackupMerger.merge(local, remote, true, false).singleRow("animes")

        assertEquals(90, anime.getInt("rating"))
        assertTrue(anime.isNull("ratingGrade"))
        assertTrue(anime.isNull("reminderDay"))
        assertTrue(anime.isNull("reminderTime"))
    }

    @Test
    fun adoptsACompleteRemoteReminderWhenLocalReminderIsIncomplete() {
        val local = snapshot(
            "animes" to rows(
                row(
                    "id" to 1,
                    "title" to "作品",
                    "externalSource" to "bangumi",
                    "externalId" to "42",
                    "reminderDay" to 2,
                    "reminderTime" to JSONObject.NULL,
                ),
            ),
        )
        val remote = snapshot(
            "animes" to rows(
                row(
                    "id" to 9,
                    "title" to "作品",
                    "externalSource" to "bangumi",
                    "externalId" to "42",
                    "reminderDay" to 5,
                    "reminderTime" to "21:30",
                ),
            ),
        )

        val anime = NativeBackupMerger.merge(local, remote, true, false).singleRow("animes")

        assertEquals(5, anime.getInt("reminderDay"))
        assertEquals("21:30", anime.getString("reminderTime"))
    }

    @Test
    fun usesNameFallbackWhenRemoteAddsCharacterAndGroupIdentity() {
        val local = snapshot(
            "characters" to rows(row("id" to 1, "name" to "角色 A", "bgmId" to JSONObject.NULL)),
            "character_groups" to rows(
                row("id" to 1, "name" to "收藏组", "communityId" to JSONObject.NULL),
            ),
        )
        val remote = snapshot(
            "characters" to rows(row("id" to 9, "name" to "角色 A", "bgmId" to 100)),
            "character_groups" to rows(
                row("id" to 9, "name" to "收藏组", "communityId" to "community-1"),
            ),
        )

        val merged = NativeBackupMerger.merge(local, remote, includeCore = true, includeAnalysis = false)

        assertEquals(100L, merged.singleRow("characters").getLong("bgmId"))
        assertEquals("community-1", merged.singleRow("character_groups").getString("communityId"))
    }

    @Test
    fun ignoresIntegerOverflowAndDropsInvalidWatchRecords() {
        val local = snapshot(
            "animes" to rows(
                row(
                    "id" to 1,
                    "title" to "作品",
                    "externalSource" to "bangumi",
                    "externalId" to "42",
                    "watchedEpisodes" to 7,
                    "totalEpisodes" to 12,
                ),
            ),
        )
        val remote = snapshot(
            "animes" to rows(
                row(
                    "id" to 9,
                    "title" to "作品",
                    "externalSource" to "bangumi",
                    "externalId" to "42",
                    "watchedEpisodes" to Long.MAX_VALUE,
                    "totalEpisodes" to 2_147_483_648L,
                ),
            ),
            "watch_records" to rows(
                row("id" to 1, "animeId" to 9, "episode" to Long.MAX_VALUE),
            ),
        )

        val merged = NativeBackupMerger.merge(local, remote, includeCore = true, includeAnalysis = false)

        assertEquals(7, merged.singleRow("animes").getInt("watchedEpisodes"))
        assertEquals(12, merged.singleRow("animes").getInt("totalEpisodes"))
        assertEquals(0, merged.getJSONArray("watch_records").length())
    }

    @Test
    fun mergesLegacyReadingTypesIntoTheSingleBookType() {
        val local = snapshot(
            "animes" to rows(
                row("id" to 1, "title" to "同一本书", "subjectType" to "book"),
            ),
        )
        val remote = snapshot(
            "animes" to rows(
                row(
                    "id" to 9,
                    "title" to "同一本书",
                    "subjectType" to "manga",
                    "synopsis" to "远端简介",
                ),
            ),
        )

        val merged = NativeBackupMerger.merge(local, remote, includeCore = true, includeAnalysis = false)
        val anime = merged.singleRow("animes")

        assertEquals("book", anime.getString("subjectType"))
        assertEquals("远端简介", anime.getString("synopsis"))
    }

    @Test
    fun logicalNamesAreLocaleIndependent() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            val local = snapshot("tags" to rows(row("id" to 1, "name" to "I")))
            val remote = snapshot("tags" to rows(row("id" to 9, "name" to "i", "color" to 123L)))

            val merged = NativeBackupMerger.merge(local, remote, includeCore = true, includeAnalysis = false)

            assertEquals(1, merged.getJSONArray("tags").length())
            assertEquals(123L, merged.singleRow("tags").getLong("color"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun JSONObject.ids(table: String): List<Long> = getJSONArray(table).let { array ->
        List(array.length()) { index -> array.getJSONObject(index).getLong("id") }
    }

    private fun JSONObject.singleRow(table: String): JSONObject = getJSONArray(table).also {
        assertEquals(1, it.length())
    }.getJSONObject(0)

    private fun snapshot(vararg tables: Pair<String, JSONArray>): JSONObject = JSONObject().apply {
        tables.forEach { (name, value) -> put(name, value) }
    }

    private fun rows(vararg values: JSONObject): JSONArray = JSONArray(values.toList())

    private fun row(vararg values: Pair<String, Any?>): JSONObject = JSONObject().apply {
        values.forEach { (name, value) -> put(name, value) }
    }
}
