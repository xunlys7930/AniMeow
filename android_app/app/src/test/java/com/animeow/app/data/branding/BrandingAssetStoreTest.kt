package com.animeow.app.data.branding

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowBitmapFactory
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BrandingAssetStoreTest {
    @Before fun rejectInvalidImageDataLikeAndroid() {
        ShadowBitmapFactory.setAllowInvalidImageData(false)
    }

    private fun image(context: Context): File = File.createTempFile("splash", ".png", context.cacheDir).apply {
        outputStream().use { Bitmap.createBitmap(48, 72, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun currentImageSurvivesCleanupWhenFilesDirectoryContainsANonCanonicalPath() = runBlocking {
        val base: Context = RuntimeEnvironment.getApplication()
        File(base.filesDir, "alias").mkdirs()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(base.filesDir, "alias/..")
        }
        val store = BrandingAssetStore(context)
        val original = image(context)
        val saved = store.importSplash(Uri.fromFile(original))
        store.cleanupSplashFiles(saved)
        val retained = requireNotNull(BrandingAssetStore(context).resolve(saved))
        assertTrue(original.readBytes().contentEquals(retained.readBytes()))
        assertTrue(saved.startsWith("branding/splash_"))
    }

    @Test fun replacingImageRemovesOnlyThePreviousSplash() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = BrandingAssetStore(context)
        val old = store.importSplash(Uri.fromFile(image(context)))
        val otherAsset = File(store.rootDirectory, "unrelated.txt").apply { writeText("keep") }
        val current = store.importSplash(Uri.fromFile(image(context)))
        store.cleanupSplashFiles(current)
        assertNull(store.resolve(old))
        assertNotNull(store.resolve(current))
        assertTrue(otherAsset.exists())
    }

    @Test fun invalidImageDoesNotRemoveTheExistingCover() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = BrandingAssetStore(context)
        val current = store.importSplash(Uri.fromFile(image(context)))
        val bad = File(context.cacheDir, "invalid-image.txt").apply { writeText("not an image") }
        assertTrue(runCatching { store.importSplash(Uri.fromFile(bad)) }.isFailure)
        assertNotNull(store.resolve(current))
        assertFalse(store.rootDirectory.listFiles().orEmpty().any { it.extension == "tmp" })
    }
}
