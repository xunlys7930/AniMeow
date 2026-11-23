package com.animeow.app.data.playback

import android.app.Application
import com.animeow.app.data.remote.PlaybackEpisode
import com.animeow.app.data.remote.PlaybackLine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class PlaybackPreferencesTest {
    @Test fun upscalingIsOptInAndUnknownOrOldSettingsKeepOriginalVideo() {
        val context = RuntimeEnvironment.getApplication()
        val raw = context.getSharedPreferences("playback_preferences_v1", 0)
        raw.edit().clear().putString("upscale", "unsupported-model").commit()
        val preferences = PlaybackPreferences(context)
        assertEquals(PlaybackUpscale.OFF, preferences.read().upscale)
        assertEquals(PlaybackAiQuality.EFFICIENT, preferences.read().aiQuality)
        for (mode in PlaybackUpscale.entries) {
            val selected = preferences.read().copy(upscale = mode, speed = 1.25f, autoNext = false)
            preferences.save(selected)
            assertEquals(selected, PlaybackPreferences(context).read())
        }
        val quality = preferences.read().copy(aiQuality = PlaybackAiQuality.QUALITY)
        preferences.save(quality)
        assertEquals(quality, PlaybackPreferences(context).read())
        raw.edit().putString("aiQuality", "unknown").commit()
        assertEquals(PlaybackAiQuality.EFFICIENT, preferences.read().aiQuality)
    }
    @Test fun watchingLayoutMigratesUnknownValuesAndPersistsWithoutChangingOtherPreferences() {
        val context = RuntimeEnvironment.getApplication()
        val raw = context.getSharedPreferences("playback_preferences_v1", 0)
        raw.edit().clear().putString("layout", "obsolete-layout").putFloat("speed", 1.5f).putInt("skip", 30).commit()
        val preferences = PlaybackPreferences(context)
        assertEquals(WatchingLayout.TABBED, preferences.read().layout)
        val selected = preferences.read().copy(layout = WatchingLayout.CONTINUE, crop = true, autoLock = true)
        preferences.save(selected)
        assertEquals(selected, PlaybackPreferences(context).read())
        assertEquals(1.5f, preferences.read().speed)
        assertEquals(30, preferences.read().skipSeconds)
        assertFalse(preferences.read().autoNext)
    }
    @Test fun settingsPersistWithSafeDefaultsAndSourcePreferencesStayPerWork() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = PlaybackPreferences(context)
        assertEquals(PlaybackSettings(), prefs.read())
        prefs.save(PlaybackSettings(1.5f, 30, true)); prefs.played("work-a", "seven")
        val reopened = PlaybackPreferences(context)
        assertEquals(PlaybackSettings(1.5f, 30, true), reopened.read())
        assertEquals("seven", reopened.preferredSource("work-a")); assertNull(reopened.preferredSource("work-b"))
        assertThrows(IllegalArgumentException::class.java) { prefs.save(PlaybackSettings(4f, 30)) }
        context.getSharedPreferences("playback_preferences_v1", 0).edit().putFloat("speed", Float.NaN).putInt("skip", -1).commit()
        assertEquals(PlaybackSettings(1f, 10, true), reopened.read())
    }
    @Test fun automaticProgressDefaultsToLegacyBehaviorAndRemembersExplicitOptOut() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = PlaybackPreferences(context)
        assertTrue(preferences.read().autoMarkProgress)
        preferences.save(preferences.read().copy(autoMarkProgress = false))
        val reopened = PlaybackPreferences(context)
        assertFalse(reopened.read().autoMarkProgress)
        reopened.save(reopened.read().copy(speed = 1.5f, autoNext = true))
        reopened.played("work", "seven")
        assertFalse(PlaybackPreferences(context).read().autoMarkProgress)
        reopened.save(reopened.read().copy(autoMarkProgress = true))
        assertTrue(PlaybackPreferences(context).read().autoMarkProgress)
    }
    @Test fun preferredSourcesAreBoundedWithoutDeletingPlaybackSettings() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = PlaybackPreferences(context)
        prefs.save(PlaybackSettings(2f, 5, true))
        repeat(501) { prefs.played("work-$it", "giri") }
        assertEquals(500, context.getSharedPreferences("playback_preferences_v1", 0).all.keys.count { it.startsWith("source:") })
        assertEquals("giri", prefs.preferredSource("work-500"))
        assertEquals(PlaybackSettings(2f, 5, true), prefs.read())
    }
    @Test fun nextEpisodeUsesCurrentLineAndDoesNotSkipSpecialsGapsOrLastEpisode() {
        val first = PlaybackEpisode("1-1", "01", 1)
        val second = PlaybackEpisode("1-2", "02", 2)
        val special = PlaybackEpisode("1-3", "SP", null)
        val lines = listOf(PlaybackLine("1", "one", listOf(first, second, special)),
            PlaybackLine("2", "two", listOf(PlaybackEpisode("2-1", "01", 1), PlaybackEpisode("2-2", "03", 3))))
        assertEquals(second, nextPlaybackEpisode(first, lines))
        assertNull(nextPlaybackEpisode(second, lines)); assertNull(nextPlaybackEpisode(special, lines))
        assertNull(nextPlaybackEpisode(lines[1].episodes.first(), lines))
        assertNull(nextPlaybackEpisode(PlaybackEpisode("missing", "01", 1), lines))
        assertNull(nextPlaybackEpisode(second, listOf(lines.first().copy(episodes = listOf(first, second)))))
    }
    @Test fun seekClampsToKnownDurationAndAllowsUnknownDuration() {
        assertEquals(0L, playbackSeekPosition(5000, 100_000, -10_000))
        assertEquals(100_000L, playbackSeekPosition(95_000, 100_000, 10_000))
        assertEquals(105_000L, playbackSeekPosition(95_000, -1, 10_000))
    }
}
