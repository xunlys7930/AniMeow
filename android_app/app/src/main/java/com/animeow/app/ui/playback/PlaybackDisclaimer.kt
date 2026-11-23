package com.animeow.app.ui.playback

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

private const val DISCLAIMER_PREFS = "playback_disclaimer"

/**
 * First entry to playback or download management explains that AniMeow only resolves
 * third-party addresses; it does not host, store or transcode media.
 */
@Composable
internal fun PlaybackDisclaimer(kind: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(DISCLAIMER_PREFS, Context.MODE_PRIVATE) }
    var accepted by remember { mutableStateOf(prefs.getBoolean(kind, false)) }
    if (accepted) return
    val watching = kind == "watch"
    AlertDialog(
        onDismissRequest = { },
        title = { Text(if (watching) "观看服务说明" else "离线缓存说明") },
        text = {
            Text(
                if (watching) {
                    "AniMeow 不提供、不托管、不制作也不转码任何视频内容。播放链接来自外部公开网页，应用仅提供目录检索与本地播放工具；" +
                        "相关版权归原权利人所有，能否正常访问及内容合规性取决于对应站点。请在遵守当地法律法规和站点条款的前提下使用。"
                } else {
                    "离线缓存仅将当前外部公开网页的视频流保存在本机，便于无网时播放。AniMeow 不托管任何视频资源，也不对外部站点内容的完整性与可用性负责；" +
                        "请仅缓存你有权观看的内容，卸载应用或清理缓存后数据不会保留。"
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { prefs.edit().putBoolean(kind, true).apply(); accepted = true }) { Text("我知道了") }
        },
    )
}
