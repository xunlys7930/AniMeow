package com.animeow.app.ui.components

import android.app.Application
import android.os.Looper
import android.os.ResultReceiver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import com.animeow.app.data.overlay.ProgressOverlayService
import com.animeow.app.ui.overlay.rememberProgressOverlayLauncher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [30])
class ProgressOverlayLauncherTest {
    @Test fun requestingAServiceDoesNotReportSuccessUntilItsReceiptArrives() {
        ShadowSettings.setCanDrawOverlays(true)
        try {
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                var started = 0
                lateinit var launch: () -> Unit
                activity.setContent { launch = rememberProgressOverlayLauncher(42) { started++ } }
                shadowOf(Looper.getMainLooper()).idle()
                launch()
                assertEquals(0, started)
                val intent = shadowOf(activity).nextStartedService
                assertEquals(42, intent.getLongExtra(ProgressOverlayService.EXTRA_ID, 0))
                val receipt = IntentCompat.getParcelableExtra(intent, "overlay_start_result", ResultReceiver::class.java)
                assertNotNull(receipt)
                receipt!!.send(ProgressOverlayService.RESULT_STARTED, null)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, started)
            }
        } finally { ShadowSettings.setCanDrawOverlays(false) }
    }
}
