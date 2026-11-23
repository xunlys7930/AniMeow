package com.animeow.app.data.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CharacterImageStoreTest {
    @Test fun jpegOrientationIsAppliedBeforeSaving() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "rotated-character.jpg")
        val bitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        android.media.ExifInterface(source.path).apply {
            setAttribute(android.media.ExifInterface.TAG_ORIENTATION, "6"); saveAttributes()
        }
        val imported = CoverImageStore(context).importCharacterImage(source.toUri())
        val destination = File(requireNotNull(android.net.Uri.parse(imported).path))
        try {
            val copy = BitmapFactory.decodeFile(destination.path)
            assertEquals(80, copy.width); assertEquals(160, copy.height); copy.recycle()
        } finally { source.delete(); destination.delete() }
    }

    @Test fun importedImageSurvivesSourceRemovalAndPreservesTransparencyAndAspect() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "character-source.png")
        val bitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(10, 10, android.graphics.Color.RED)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val imported = CoverImageStore(context).importCharacterImage(source.toUri())
        val destination = File(requireNotNull(android.net.Uri.parse(imported).path))
        try {
            assertTrue(destination.canonicalPath.startsWith(File(context.filesDir, "covers/characters").canonicalPath))
            assertTrue(source.delete())
            val copy = BitmapFactory.decodeFile(destination.path)
            assertEquals(160, copy.width)
            assertEquals(80, copy.height)
            assertEquals(0, android.graphics.Color.alpha(copy.getPixel(0, 0)))
            assertEquals(android.graphics.Color.RED, copy.getPixel(10, 10))
            copy.recycle()
            val before = destination.parentFile!!.listFiles()!!.size
            try { CoverImageStore(context).importCharacterImage(File(context.cacheDir, "missing-image.png").toUri()); fail("missing image") }
            catch (_: IllegalStateException) { }
            assertEquals(before, destination.parentFile!!.listFiles()!!.size)
        } finally { source.delete(); destination.delete() }
    }
}
