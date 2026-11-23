package com.animeow.app.data.community

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommunityDraftImagesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val id = UUID.randomUUID().toString()
    private val sourceDirectory = File(context.cacheDir, "draft-test-$id").apply { mkdirs() }
    private val draftDirectory = File(context.noBackupFilesDir, "draft-test-$id")
    private val sibling = File(context.noBackupFilesDir, "draft-test-${id}_outside")
    private val store = CommunityDraftImages(context, draftDirectory)

    @After
    fun cleanFixtures() {
        sourceDirectory.deleteRecursively()
        draftDirectory.deleteRecursively()
        sibling.deleteRecursively()
    }

    @Test
    fun savedUriSurvivesCacheRemovalAndStoreRecreation() = runBlocking {
        val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        val source = File(sourceDirectory, "picked.png").apply { writeBytes(bytes) }
        val retained = store.retain(Uri.fromFile(source))
        sourceDirectory.deleteRecursively()
        val recreated = CommunityDraftImages(context, draftDirectory)
        val restored = recreated.retain(Uri.parse(retained.toString()))
        assertEquals(retained, restored)
        assertArrayEquals(bytes, File(restored.path!!).readBytes())
        assertFalse(File(restored.path!!).canonicalPath.startsWith(context.cacheDir.canonicalPath))
        recreated.release(listOf(restored))
        assertFalse(File(restored.path!!).exists())
    }

    @Test
    fun oversizedImageLeavesNoPartialDraft() = runBlocking {
        val source = File(sourceDirectory, "large.jpg")
        RandomAccessFile(source, "rw").use { it.setLength(21L * 1024 * 1024) }
        try {
            store.retain(Uri.fromFile(source))
            fail("Oversized images must be rejected")
        } catch (_: IllegalArgumentException) {
            assertTrue(draftDirectory.listFiles().orEmpty().isEmpty())
            assertTrue(source.isFile)
        }
    }

    @Test
    fun releaseNeverDeletesSourcesOrSimilarDirectoryNames() = runBlocking {
        val source = File(sourceDirectory, "source.jpg").apply { writeText("source") }
        sibling.mkdirs()
        val outside = File(sibling, "keep.jpg").apply { writeText("outside") }
        store.release(listOf(Uri.fromFile(source), Uri.fromFile(outside)))
        assertTrue(source.exists())
        assertTrue(outside.exists())
    }

    @Test
    fun cancellationAtDispatcherHandoffLeavesNoOrphan() = runBlocking {
        val source = File(sourceDirectory, "picked.jpg").apply { writeBytes(ByteArray(1024 * 1024)) }
        repeat(10) {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { store.retain(Uri.fromFile(source)) }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertTrue(draftDirectory.listFiles().orEmpty().isEmpty())
        }
        assertTrue(source.exists())
    }
}
