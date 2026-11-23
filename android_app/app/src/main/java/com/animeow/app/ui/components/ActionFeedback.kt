package com.animeow.app.ui.components

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.animeow.app.R
import com.animeow.app.ui.theme.AppearanceSettings

class ActionFeedback internal constructor(private val context: Context?) {
    internal var hapticEnabled = false
    internal var soundEnabled = false
    private var lastHapticAt = -1000L
    private var lastSoundAt = -1000L
    fun tap() { vibrate(18, 105); sound(false) }
    fun selection() { vibrate(22, 135); sound(false) }
    fun confirm() { vibrate(32, 185); sound(true) }
    fun longPress() { vibrate(40, 200); sound(false) }

    fun previewHaptic(): String = when {
        !hapticEnabled -> "请先开启触觉反馈"
        vibrate(32, 185) -> "已触发确认触感"
        else -> "系统触感已关闭，或当前设备没有振动马达"
    }
    fun previewSound(): String = when {
        !soundEnabled -> "请先开启操作音效"
        sound(true) -> "正在试听，请留意系统音量"
        else -> "静音、勿扰或系统音量为零时不播放音效"
    }

    private fun vibrate(duration: Long, amplitude: Int): Boolean {
        val context = context ?: return false
        if (!hapticEnabled) return false
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return false
        if (!vibrator.hasVibrator() || Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastHapticAt < 70) return true // Nested card/action handlers should yield one pulse.
        lastHapticAt = now
        val effect = VibrationEffect.createOneShot(duration, if (vibrator.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE)
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            }
            true
        }.getOrDefault(false)
    }

    private fun sound(success: Boolean): Boolean {
        val context = context ?: return false
        if (!soundEnabled) return false
        if (!systemSoundAllowed(context)) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastSoundAt < 90) return true
        lastSoundAt = now
        return FeedbackSound.play(context, success)
    }
}

private fun systemSoundAllowed(context: Context): Boolean {
    val audio = context.getSystemService(AudioManager::class.java) ?: return false
    val interruption = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
    return audio.ringerMode == AudioManager.RINGER_MODE_NORMAL && audio.getStreamVolume(AudioManager.STREAM_SYSTEM) > 0 &&
        interruption !in setOf(NotificationManager.INTERRUPTION_FILTER_NONE, NotificationManager.INTERRUPTION_FILTER_PRIORITY, NotificationManager.INTERRUPTION_FILTER_ALARMS)
}

private object FeedbackSound {
    private var pool: SoundPool? = null
    private var click = 0
    private var success = 0
    private val ready = mutableSetOf<Int>()
    private var pending: Int? = null

    @Synchronized fun prepare(context: Context) {
        if (pool != null) return
        val sounds = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        pool = sounds
        sounds.setOnLoadCompleteListener { _, id, status ->
            synchronized(this) {
                if (status == 0) {
                    ready += id
                    if (pending == id) {
                        if (systemSoundAllowed(context)) sounds.play(id, 0.55f, 0.55f, 1, 0, 1f)
                        pending = null
                    }
                }
            }
        }
        click = sounds.load(context, R.raw.ui_click, 1)
        success = sounds.load(context, R.raw.ui_confirm, 1)
    }

    @Synchronized fun play(context: Context, confirmed: Boolean): Boolean {
        prepare(context.applicationContext)
        val id = if (confirmed) success else click
        if (id in ready) pool?.play(id, 0.55f, 0.55f, 1, 0, 1f) else pending = id
        return true
    }

    @Synchronized fun cancelPending() { pending = null }
}

val LocalActionFeedback = staticCompositionLocalOf { ActionFeedback(null) }

private val DisabledHapticFeedback = object : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

@Composable
fun ProvideActionFeedback(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val feedback = remember(context) { ActionFeedback(context) }
    val platformHaptic = LocalHapticFeedback.current
    val effectiveHaptic = if (settings.hapticFeedback) platformHaptic else DisabledHapticFeedback
    SideEffect {
        feedback.hapticEnabled = settings.hapticFeedback
        feedback.soundEnabled = settings.soundFeedback
        if (settings.soundFeedback) FeedbackSound.prepare(context) else FeedbackSound.cancelPending()
    }
    CompositionLocalProvider(
        LocalActionFeedback provides feedback,
        LocalHapticFeedback provides effectiveHaptic,
        content = content,
    )
}
