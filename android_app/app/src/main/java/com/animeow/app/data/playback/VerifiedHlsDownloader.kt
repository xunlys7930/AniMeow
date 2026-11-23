@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.animeow.app.data.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.offline.Downloader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Reject live/protected manifests before Media3 can mark a partial snapshot as an offline episode. */
internal class VerifiedHlsDownloader(private val delegate: Downloader, private val upstream: DataSource.Factory,
    private val uri: Uri) : Downloader {
    @Volatile private var cancelled = false
    override fun download(progressListener: Downloader.ProgressListener?) {
        verify(uri, 0, mutableSetOf())
        if (cancelled) throw InterruptedException()
        delegate.download(progressListener)
    }
    private fun verify(uri: Uri, depth: Int, seen: MutableSet<String>) {
        if (cancelled) throw InterruptedException()
        if (!seen.add(uri.toString())) return
        if (depth > 2 || seen.size > 32) throw IOException("ANIMEOW_HLS: unsupported playlist hierarchy")
        val source = upstream.createDataSource()
        var resolvedUri = uri
        val bytes = DataSourceInputStream(source, DataSpec(uri)).use { input ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                if (cancelled) throw InterruptedException()
                val count = input.read(buffer)
                if (count == -1) break
                if (out.size() + count > 2 * 1024 * 1024) throw IOException("ANIMEOW_HLS: oversized playlist")
                out.write(buffer, 0, count)
            }
            resolvedUri = source.uri ?: uri
            out.toByteArray()
        }
        when (val playlist = HlsPlaylistParser().parse(resolvedUri, ByteArrayInputStream(bytes))) {
            is HlsMultivariantPlaylist -> {
                if (playlist.sessionKeyDrmInitData.isNotEmpty() || playlist.mediaPlaylistUrls.isEmpty()) throw IOException("ANIMEOW_HLS: protected playlist")
                playlist.mediaPlaylistUrls.forEach { verify(it, depth + 1, seen) }
            }
            is HlsMediaPlaylist -> {
                if (!playlist.hasEndTag || playlist.segments.isEmpty() || playlist.protectionSchemes != null ||
                    playlist.segments.any { it.drmInitData != null || it.fullSegmentEncryptionKeyUri != null }) throw IOException("ANIMEOW_HLS: live or protected playlist")
            }
            else -> throw IOException("ANIMEOW_HLS: unsupported playlist")
        }
    }
    override fun cancel() { cancelled = true; delegate.cancel() }
    override fun remove() = delegate.remove()
}
