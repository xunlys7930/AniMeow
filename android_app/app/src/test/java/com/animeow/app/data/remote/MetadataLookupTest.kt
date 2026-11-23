package com.animeow.app.data.remote

import com.animeow.app.data.preferences.MetadataLookupStep
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MetadataLookupTest {
    private val now = Instant.parse("2026-09-12T00:00:00Z")
    private val remote = RemoteAnime(RemoteCatalogSource.BANGUMI, "123", "示例", coverUrl = "https://example.com/cover.jpg", summary = "公开简介")

    @Test fun manualCandidatesKeepIncompleteSequelsWhileAutomaticCompletionStillRequiresUsefulMetadata() = runBlocking {
        val sequel = remote.copy(id = "124", title = "示例 第二季", coverUrl = null, summary = null)
        suspend fun lookup(includeIncomplete: Boolean) = fetchMetadata(listOf(MetadataLookupStep.BANGUMI),
            com.animeow.app.data.preferences.MetadataLookupStrategy.ORDERED, { it.isNotEmpty() },
            { _, _ -> listOf(remote, sequel) }, now = now, includeIncompleteResults = includeIncomplete)
        assertEquals(listOf(remote, sequel), lookup(true).items)
        assertEquals(listOf(remote), lookup(false).items)
    }

    @Test fun freshServerHitDoesNotRequestOfficialsOrProxy() = runBlocking {
        val requests = mutableListOf<MetadataLookupStep>()
        val cached = remote.copy(source = RemoteCatalogSource.SERVER, catalogUpdatedAt = now.toString())
        val result = orderedMetadataLookup(MetadataLookupStep.entries, { it.isNotEmpty() }, { step, _ -> requests += step; listOf(cached) }, now)
        assertEquals(listOf(MetadataLookupStep.SERVER), requests)
        assertEquals(listOf(cached), result.items)
    }

    @Test fun serverMissTriesOfficialBeforeProxyAndStopsOnHit() = runBlocking {
        val requests = mutableListOf<MetadataLookupStep>()
        val result = orderedMetadataLookup(MetadataLookupStep.entries, { it.isNotEmpty() }, { step, _ ->
            requests += step
            if (step == MetadataLookupStep.BANGUMI) listOf(remote) else emptyList()
        }, now)
        assertEquals(listOf(MetadataLookupStep.SERVER, MetadataLookupStep.BANGUMI), requests)
        assertEquals(listOf(remote), result.items)
    }

    @Test fun emptyOfficialResultIsMarkedSuccessfulForQuotaProtection(): Unit = runBlocking {
        orderedMetadataLookup(MetadataLookupStep.entries, { it.isNotEmpty() }, { step, successful ->
            if (step == MetadataLookupStep.PROXY) {
                assertTrue(MetadataLookupStep.BANGUMI in successful)
                assertTrue(MetadataLookupStep.ANILIST in successful)
            }
            emptyList()
        }, now)
    }

    @Test fun failedOfficialIsEligibleForProxyAndCustomOrderIsPreserved() = runBlocking {
        val requests = mutableListOf<MetadataLookupStep>()
        orderedMetadataLookup(listOf(MetadataLookupStep.ANILIST, MetadataLookupStep.PROXY), { it.isNotEmpty() }, { step, successful ->
            requests += step
            if (step == MetadataLookupStep.ANILIST) throw RemoteHttpException(403, "blocked")
            assertFalse(MetadataLookupStep.ANILIST in successful)
            listOf(remote)
        }, now)
        assertEquals(listOf(MetadataLookupStep.ANILIST, MetadataLookupStep.PROXY), requests)
    }

    @Test fun staleCacheIsKeptWhenOfficialsCannotProvideBetterData() = runBlocking {
        val stale = remote.copy(source = RemoteCatalogSource.SERVER, catalogUpdatedAt = "2020-01-01T00:00:00Z")
        val result = orderedMetadataLookup(MetadataLookupStep.entries, { it.isNotEmpty() }, { step, _ ->
            if (step == MetadataLookupStep.SERVER) listOf(stale) else throw java.io.IOException("offline")
        }, now)
        assertEquals(listOf(stale), result.items)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test fun cancellationNeverRequestsFallback() = runBlocking {
        var count = 0
        try {
            orderedMetadataLookup(MetadataLookupStep.entries, { it.isNotEmpty() }, { _, _ -> count++; throw CancellationException() }, now)
            fail("Cancellation should propagate")
        } catch (_: CancellationException) { assertEquals(1, count) }
    }

    @Test fun invalidRequestsAndMissingTitlesDoNotUseProxy() {
        assertFalse(shouldTryRemoteFallback(RemoteHttpException(404, "missing")))
        assertFalse(shouldTryRemoteFallback(RemoteHttpException(400, "invalid")))
        assertTrue(shouldTryRemoteFallback(RemoteHttpException(403, "blocked")))
        assertTrue(shouldTryRemoteFallback(java.net.SocketTimeoutException()))
        assertFalse(userFriendlyRemoteFailure("AniList", RemoteHttpException(403, "blocked")).contains("鉴权方式"))
    }

    @Test fun fastestCompleteResultWinsWithoutWaitingForBlockedSource() = runBlocking {
        val calls = java.util.Collections.synchronizedList(mutableListOf<MetadataLookupStep>())
        val result = fetchMetadata(MetadataLookupStep.entries, com.animeow.app.data.preferences.MetadataLookupStrategy.RACE,
            { it.isNotEmpty() }, { step, _ ->
                calls += step
                if (step == MetadataLookupStep.BANGUMI) { kotlinx.coroutines.delay(20); listOf(remote) }
                else { kotlinx.coroutines.delay(5_000); emptyList() }
            }, timeoutMillis = 100, now = now)
        assertEquals(listOf(remote), result.items)
        assertEquals(setOf(MetadataLookupStep.SERVER, MetadataLookupStep.BANGUMI, MetadataLookupStep.ANILIST), calls.toSet())
        assertFalse(MetadataLookupStep.PROXY in calls)
    }

    @Test fun incompleteOfficialDataCanUseProxyToFillMissingCover() = runBlocking {
        val calls = mutableListOf<MetadataLookupStep>()
        val result = fetchMetadata(listOf(MetadataLookupStep.BANGUMI, MetadataLookupStep.PROXY), com.animeow.app.data.preferences.MetadataLookupStrategy.ORDERED,
            { it.isNotEmpty() }, { step, _ ->
                calls += step
                listOf(if (step == MetadataLookupStep.BANGUMI) remote.copy(coverUrl = null) else remote)
            }, now = now)
        assertEquals(listOf(MetadataLookupStep.BANGUMI, MetadataLookupStep.PROXY), calls)
        assertEquals(remote.coverUrl, result.items.single().coverUrl)
    }

    @Test fun disabledSourcesAreNeverReintroducedAndProxyIsSeparateTier() {
        val enabled = listOf(MetadataLookupStep.ANILIST, MetadataLookupStep.PROXY, MetadataLookupStep.KITSU)
        assertEquals(enabled.map { listOf(it) }, metadataRequestGroups(enabled, com.animeow.app.data.preferences.MetadataLookupStrategy.RACE))
    }

    @Test fun differentSeasonsAndRemakesNeverShareCoverAlternatives() {
        val first = remote.copy(originalTitle = "Test", airDate = "2026-01-01")
        val remake = first.copy(source = RemoteCatalogSource.ANILIST, id = "5", airDate = "2027-01-01")
        val otherSeason = remake.copy(id = "6", airDate = first.airDate, originalTitle = "Test Season 2")
        assertFalse(samePublicWork(first, remake))
        assertFalse(samePublicWork(first, otherSeason))
        assertEquals(3, mergeRemoteCandidates(listOf(first, remake, otherSeason)).size)
    }

    @Test fun mergingVerifiedSourcesRetainsBothContributionIdentities() {
        val first = remote.copy(originalTitle = "Test", airDate = "2026-01-01", metadataReferences = setOf(MetadataReference("bangumi", "123")), coverUrls = mapOf("bangumi" to "https://one.test/a"))
        val second = first.copy(source = RemoteCatalogSource.ANILIST, id = "5", metadataReferences = setOf(MetadataReference("anilist", "5")), coverUrls = mapOf("anilist" to "https://two.test/b"))
        val merged = mergeRemoteCandidates(listOf(first, second)).single()
        assertEquals(2, merged.metadataReferences.size)
        assertEquals(setOf("bangumi", "anilist"), merged.coverUrls.keys)
    }

    @Test fun staleServerFallbackDoesNotMaskFreshOfficialFields() = runBlocking {
        val old = remote.copy(source = RemoteCatalogSource.SERVER, id = "900", originSource = "bangumi", originId = remote.id,
            summary = "旧简介", catalogUpdatedAt = "2020-01-01T00:00:00Z")
        val fresh = remote.copy(summary = "更新简介", coverUrl = "https://example.com/new-cover.jpg", episodes = 24)
        val result = orderedMetadataLookup(listOf(MetadataLookupStep.SERVER, MetadataLookupStep.BANGUMI), { it.isNotEmpty() },
            { step, _ -> listOf(if (step == MetadataLookupStep.SERVER) old else fresh) }, now)
        assertEquals(fresh.summary, result.items.single().summary)
        assertEquals(fresh.coverUrl, result.items.single().coverUrl)
        assertEquals(24, result.items.single().episodes)
    }

    @Test fun conflictingEpisodeTotalsAcrossSourcesStaySeparate() {
        val first = remote.copy(originalTitle = "Test", airDate = "2026-01-01", episodes = 12)
        val other = first.copy(source = RemoteCatalogSource.ANILIST, id = "6", episodes = 24)
        assertFalse(samePublicWork(first, other))
    }
}
