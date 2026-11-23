package com.animeow.app.data.reader

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import com.animeow.app.data.reader.legado.LegadoLauncher
import com.animeow.app.data.reader.legado.LegadoOpenResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class LegadoLauncherTest {
    private val app = RuntimeEnvironment.getApplication()
    private val packageManager = shadowOf(app.packageManager)
    private val packageName = "io.legado.app.release"
    private val launcher = ComponentName(packageName, "io.legado.app.ui.welcome.Launcher")
    private val reader = ComponentName(packageName, "io.legado.app.ui.book.read.ReadBookActivity")
    private val bookKey = "https://example.test/book?id=7&name=示例书"

    @Test
    fun opensExportedReaderWithExactImportedBookKey() {
        installClient(readerExported = true)

        assertEquals(LegadoOpenResult.BOOK_OPENED, LegadoLauncher.openLegado(app, bookKey))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(reader, intent.component)
        assertEquals(bookKey, intent.getStringExtra("bookUrl"))
        assertNull(intent.data)
    }

    @Test
    fun opensClientWhenReaderIsPrivate() {
        installClient(readerExported = false)

        assertEquals(LegadoOpenResult.APP_OPENED, LegadoLauncher.openLegado(app, bookKey))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(launcher, intent.component)
        assertNull(intent.getStringExtra("bookUrl"))
    }

    @Test
    fun reportsUnavailableWhenClientIsMissing() {
        assertEquals(LegadoOpenResult.UNAVAILABLE, LegadoLauncher.openLegado(app, bookKey))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    private fun installClient(readerExported: Boolean) {
        packageManager.addOrUpdateActivity(packageManager.addActivityIfNotPresent(launcher).apply { exported = true })
        packageManager.addIntentFilterForActivity(launcher, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        })
        packageManager.addOrUpdateActivity(packageManager.addActivityIfNotPresent(reader).apply { exported = readerExported })
    }
}
