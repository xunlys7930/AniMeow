package com.animeow.app.data.community

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommunityImageOrientationTest {
    @Test
    fun normalizesAllEightExifOrientations() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val compressor = CommunityImageCompressor(context)
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val expected = listOf(
            listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2),
        )
        for (orientation in 1..8) {
            val source = File.createTempFile("exif-test-", ".jpg", context.cacheDir)
            try {
                val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                colors.forEachIndexed { index, color ->
                    val x = (index % 2) * 60f
                    val y = (index / 2) * 40f
                    canvas.drawRect(x, y, x + 60f, y + 40f, Paint().apply { this.color = color })
                }
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
                bitmap.recycle()
                ExifInterface(source).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                val compressed = compressor.compressPostImage(Uri.fromFile(source))
                assertEquals(if (orientation >= 5) 80 else 120, compressed.width)
                assertEquals(if (orientation >= 5) 120 else 80, compressed.height)
                assertTrue(compressed.bytes.size <= 768 * 1024)
                val result = BitmapFactory.decodeByteArray(compressed.bytes, 0, compressed.bytes.size)
                try {
                    val points = listOf(0.25f to 0.25f, 0.75f to 0.25f, 0.25f to 0.75f, 0.75f to 0.75f)
                    points.forEachIndexed { index, (x, y) ->
                        val pixel = result.getPixel((result.width * x).toInt(), (result.height * y).toInt())
                        val nearest = colors.indices.minBy { candidate ->
                            val color = colors[candidate]
                            kotlin.math.abs(Color.red(pixel) - Color.red(color)) +
                                kotlin.math.abs(Color.green(pixel) - Color.green(color)) +
                                kotlin.math.abs(Color.blue(pixel) - Color.blue(color))
                        }
                        assertEquals("Orientation $orientation, corner $index", expected[orientation - 1][index], nearest)
                    }
                } finally {
                    result.recycle()
                }
            } finally {
                source.delete()
            }
        }
    }
}
