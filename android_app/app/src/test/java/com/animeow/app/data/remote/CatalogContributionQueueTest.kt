package com.animeow.app.data.remote

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36], application = Application::class)
class CatalogContributionQueueTest {
    @Test fun queueSurvivesProcessRecreationAndDeduplicatesUntilCooldownExpires() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("catalog_contribution_queue", Context.MODE_PRIVATE).edit().clear().commit()
        var clock = 100L
        val item = MetadataReference("bangumi", "123")
        val queue = CatalogContributionQueue(context) { clock }
        assertTrue(queue.add(listOf(item, item, MetadataReference("private", "1"))))
        assertEquals(item, CatalogContributionQueue(context) { clock }.first())
        assertFalse(queue.add(listOf(item)))
        queue.complete(item, 1000)
        assertNull(queue.first())
        assertFalse(queue.add(listOf(item)))
        clock += 1001
        assertTrue(queue.add(listOf(item)))
        queue.clearPending()
        assertNull(queue.first())
    }
}
