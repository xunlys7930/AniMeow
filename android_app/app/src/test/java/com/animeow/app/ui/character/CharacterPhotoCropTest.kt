package com.animeow.app.ui.character

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.animeow.app.data.media.CoverImageStore
import com.animeow.app.ui.community.CropShape
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
class CharacterPhotoCropTest {
    @Test fun zoomAndPanChangeTheSavedRegionAndCharacterCopySurvivesCropCacheRemoval() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val source = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        for (x in 0 until 200) for (y in 0 until 200) source.setPixel(x, y, when { x < 50 -> Color.RED; x >= 150 -> Color.BLUE; else -> Color.GREEN })
        val crop = Class.forName("com.animeow.app.ui.community.ImageCropDialogKt").declaredMethods.single { it.name == "cropBitmapToFile" }.apply { isAccessible = true }
        fun output(offset: Float): File {
            val uri = crop.invoke(null, context, source, 200f, 200f, 2f, offset, 0f, CropShape.Rectangle, 128, 1f) as Uri
            return File(requireNotNull(uri.path))
        }
        val centered = output(0f)
        val moved = output(70f)
        fun pixels(file: File): Bitmap = file.readBytes().let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        var saved: File? = null
        try {
            pixels(centered).also { image ->
                assertEquals(128, image.width); assertEquals(128, image.height)
                assertEquals(Color.GREEN, image.getPixel(5, 64)); assertEquals(Color.GREEN, image.getPixel(120, 64)); image.recycle()
            }
            pixels(moved).also { image ->
                assertEquals(Color.RED, image.getPixel(5, 64)); assertEquals(Color.GREEN, image.getPixel(120, 64)); image.recycle()
            }
            assertEquals(Color.RED, source.getPixel(5, 64)); assertEquals(Color.BLUE, source.getPixel(195, 64))
            saved = File(Uri.parse(CoverImageStore(context).importCharacterImage(Uri.fromFile(moved))).path!!)
            assertTrue(moved.delete())
            assertTrue(saved.isFile)
            pixels(saved).also { image -> assertEquals(Color.RED, image.getPixel(5, 64)); image.recycle() }
        } finally { centered.delete(); moved.delete(); saved?.delete(); source.recycle() }
        Unit
    }
}
