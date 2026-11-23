package com.animeow.app.data.playback

import com.animeow.app.data.remote.PlaybackStream

internal suspend fun resolvePlaybackStream(local: PlaybackStream?, offlineOnly: Boolean, resolve: suspend () -> PlaybackStream): PlaybackStream {
    if (local != null) return local
    check(!offlineOnly) { "本集缓存已被移除，请返回下载管理检查；如需联网观看，请从作品详情重新选择" }
    return resolve()
}
