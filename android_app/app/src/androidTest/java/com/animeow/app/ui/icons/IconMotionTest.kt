package com.animeow.app.ui.icons

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.animeow.app.ui.theme.AniMeowTheme
import com.animeow.app.ui.theme.AppearanceSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IconMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun interactionAnimatesAndTurningMotionOffRestoresTheExactStaticFrame() {
        var trigger by mutableIntStateOf(0)
        var animated by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AniMeowTheme(AppearanceSettings(icons = IconSettings(style = IconStyle.DUO, animated = animated))) {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text("图标交互动效", style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            listOf(AppIcons.Refresh to "刷新", AppIcons.Notification to "提醒", AppIcons.HeartFilled to "喜欢")
                                .forEach { (icon, label) ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        AppIcon(icon, label, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary,
                                            animationTrigger = trigger)
                                        Text(label, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val before = compose.onRoot().captureToImage().asAndroidBitmap()
        saveFrame(before, "motion-00.png")
        compose.runOnUiThread { trigger++ }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(96)
        val during = compose.onRoot().captureToImage().asAndroidBitmap()
        assertFalse("A user action should produce a different frame", before.sameAs(during))
        saveFrame(during, "motion-01.png")
        for (index in 2..12) {
            compose.mainClock.advanceTimeBy(48)
            saveFrame(compose.onRoot().captureToImage().asAndroidBitmap(), "motion-" + index.toString().padStart(2, '0') + ".png")
        }
        compose.runOnUiThread { trigger++ }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnUiThread { animated = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val stopped = compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("Disabling motion must also reset a transform midway through playback", before.sameAs(stopped))
        compose.mainClock.autoAdvance = true
    }

    private fun saveFrame(bitmap: Bitmap, name: String) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.getExternalFilesDir(null), "icon-qa/" + name)
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
