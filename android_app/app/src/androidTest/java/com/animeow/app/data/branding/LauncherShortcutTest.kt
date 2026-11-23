package com.animeow.app.data.branding

import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.data.preferences.BrandingPreferences
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LauncherShortcutTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun launcherActionsFollowTheEnabledAliasWithExplicitMainActivityIntents() = runBlocking {
        val selected = BrandingPreferences(context).snapshot().launcherIcon
        val icons = LauncherIconManager(context)
        val alternative = LauncherIcon.entries.first { it != selected }
        try {
            icons.reconcile(alternative)
            val shortcuts = context.getSystemService(ShortcutManager::class.java).dynamicShortcuts
            assertEquals(LauncherAction.entries.map { it.key }.toSet(), shortcuts.map { it.id }.toSet())
            shortcuts.forEach { shortcut ->
                assertTrue(shortcut.activity!!.className.endsWith(alternative.aliasName))
                assertEquals("com.animeow.app.MainActivity", shortcut.intent!!.component!!.className)
                assertNotNull(LauncherAction.fromIntentAction(shortcut.intent!!.action))
            }
        } finally { icons.reconcile(selected) }
    }

    @Test fun uploadedIconsBecomeSquarePngAndCleanupPreservesSplashAndOtherAssets() = runBlocking {
        val assets = BrandingAssetStore(context)
        val source = File(context.cacheDir, "custom-icon-qa.png")
        val bitmap = Bitmap.createBitmap(300, 180, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(169, 142, 202))
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val saved = assets.importCustomIcon(Uri.fromFile(source))
        try {
            val image = BitmapFactory.decodeFile(assets.resolve(saved)!!.path)
            assertEquals(512, image.width); assertEquals(512, image.height); image.recycle()
            val baseline = BrandingPreferences(context).snapshot()
            val splash = assets.resolve(baseline.splashImagePath)
            val previous = assets.resolve(baseline.customIconPath)
            assets.discardCustomIcon(saved)
            assertNull(assets.resolve(saved))
            if (splash != null) assertTrue(splash.exists())
            if (previous != null) assertTrue(previous.exists())
        } finally { assets.discardCustomIcon(saved); source.delete() }
    }
}
