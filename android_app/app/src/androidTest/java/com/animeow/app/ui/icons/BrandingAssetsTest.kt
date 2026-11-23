package com.animeow.app.ui.icons

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.animeow.app.BuildConfig
import com.animeow.app.R
import com.animeow.app.data.branding.BrandingAssetStore
import com.animeow.app.data.branding.BrandingSettings
import com.animeow.app.data.branding.LauncherIcon
import com.animeow.app.data.branding.LauncherIconManager
import com.animeow.app.data.preferences.BrandingPreferences
import com.animeow.app.ui.branding.BrandingSplash
import com.animeow.app.ui.theme.AniMeowTheme
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.MotionLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class BrandingAssetsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun originalDefaultAndOptionalAdaptiveAssetsMatchManifest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(R.drawable.app_icon, context.applicationInfo.icon)
        LauncherIcon.entries.forEach { icon ->
            val component = ComponentName(context.packageName, "${BuildConfig.APP_NAMESPACE}.${icon.aliasName}")
            val activity = context.packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
            val drawable = requireNotNull(context.getDrawable(activity.icon))
            if (icon == LauncherIcon.DEFAULT) {
                assertEquals(R.drawable.app_icon, activity.icon)
                assertEquals(R.drawable.app_icon, icon.previewResource)
            } else {
                assertTrue(icon.displayName, drawable is AdaptiveIconDrawable)
                if (android.os.Build.VERSION.SDK_INT >= 33) assertNotNull((drawable as AdaptiveIconDrawable).monochrome)
            }
            assertNotNull(context.getDrawable(icon.previewResource))
        }
    }

    @Test fun switchingEveryOptionPersistsAndKeepsOneLauncherEntry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = BrandingPreferences(context)
        val previous = preferences.snapshot().launcherIcon
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        try {
            // Begin from the original even if an earlier run selected another icon.
            preferences.setLauncherIcon(LauncherIcon.DEFAULT)
            LauncherIcon.entries.filterNot { it == LauncherIcon.DEFAULT }.plus(LauncherIcon.DEFAULT).forEach { icon ->
                preferences.setLauncherIcon(icon)
                val restored = BrandingPreferences(context).snapshot().launcherIcon
                assertEquals(icon, restored)
                LauncherIconManager(context).reconcile(restored)
                val entries = context.packageManager.queryIntentActivities(launcherIntent, 0)
                assertEquals(icon.displayName, 1, entries.size)
                assertEquals("${BuildConfig.APP_NAMESPACE}.${icon.aliasName}", entries.single().activityInfo.name)
            }
        } finally {
            preferences.setLauncherIcon(previous)
        }
    }

    @Test fun importedSplashSurvivesCleanupLoadsAndCanBeSkipped() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "branding-native-test.png")
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        val art = requireNotNull(context.getDrawable(LauncherIcon.DEFAULT.previewResource))
        art.setBounds(0, 0, 432, 432); art.draw(Canvas(bitmap))
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val store = BrandingAssetStore(context)
        val path = store.importSplash(Uri.fromFile(source))
        store.cleanupSplashFiles(path)
        val imported = requireNotNull(BrandingAssetStore(context).resolve(path))
        val finished = mutableStateOf(false)
        compose.setContent {
            AniMeowTheme(AppearanceSettings()) {
                BrandingSplash(BrandingSettings(customSplashEnabled = true, splashImagePath = path, splashDurationMillis = 5_000),
                    imported, MotionLevel.NONE, onFinished = { finished.value = true })
            }
        }
        compose.onNodeWithContentDescription("自定义启动封面").assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
                .fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        assertFalse("The image must load instead of immediately taking the error exit", finished.value)
        val capture = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File(context.getExternalFilesDir(null), "icon-qa/splash-native.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { capture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("跳过").performClick()
        compose.runOnIdle { assertTrue(finished.value) }
    }
}
