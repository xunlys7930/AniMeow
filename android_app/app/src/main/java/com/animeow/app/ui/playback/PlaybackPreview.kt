@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.animeow.app.ui.playback

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.inspector.frame.FrameExtractor
import com.animeow.app.data.playback.PlaybackDownloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Uses playback demuxers for HLS as well as MP4, without seeking the visible player. */
internal class PlaybackPreview(private val context: Context, private val session: PlaybackSession) : AutoCloseable {
    private var extractor: FrameExtractor? = null
    private val bytes = AtomicLong()
    @Volatile private var started = 0L

    suspend fun frame(target: Long): Bitmap? = withContext(Dispatchers.Main.immediate) {
        bytes.set(0); started = SystemClock.elapsedRealtime()
        try {
            val reader = extractor ?: run {
                // Downloads still come from local data; online previews read directly so they never
                // wait on the cache span the visible player is writing.
                val factory = if (session.offline) PlaybackDownloads.get(context).playbackFactory(session.stream.headers, true, session.downloadId)
                else DefaultHttpDataSource.Factory().setDefaultRequestProperties(session.stream.headers)
                    .setConnectTimeoutMs(10000).setReadTimeoutMs(10000)
                val bounded = DataSource.Factory {
                    val upstream = factory.createDataSource()
                    object : DataSource by upstream {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (bytes.get() >= 12L * 1024 * 1024 || SystemClock.elapsedRealtime() - started > 8000)
                                throw IOException("Preview read budget exhausted")
                            return upstream.read(buffer, offset, length).also { if (it > 0) bytes.addAndGet(it.toLong()) }
                        }
                    }
                }
                FrameExtractor.Builder(context, MediaItem.Builder().setUri(session.stream.url).setMimeType(session.stream.mimeType).build())
                    .setMediaSourceFactory(DefaultMediaSourceFactory(bounded))
                    .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                    .setEffects(listOf(Presentation.createForHeight(135)))
                    .build().also { extractor = it }
            }
            withTimeoutOrNull(8000) {
                suspendCancellableCoroutine { continuation ->
                    val future = reader.getFrame(target.coerceAtLeast(0))
                    future.addListener({
                        if (continuation.isActive) try { continuation.resume(future.get().bitmap) }
                        catch (error: Exception) { continuation.resumeWithException(error) }
                    }, Executor { it.run() })
                    continuation.invokeOnCancellation { future.cancel(true) }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    override fun close() { extractor?.close(); extractor = null }
}

internal fun playbackDragTarget(start: Long, deltaX: Float, width: Int, duration: Long): Long =
    if (duration <= 0 || width <= 0) start else (start + deltaX / width * minOf(duration, 180_000L)).toLong().coerceIn(0, duration)
