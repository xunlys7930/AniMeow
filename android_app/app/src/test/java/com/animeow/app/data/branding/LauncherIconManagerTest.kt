package com.animeow.app.data.branding

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherIconManagerTest {
    @Test
    fun debugApplicationIdStillTargetsNamespaceAlias() {
        assertEquals(
            "com.animeow.app.icon_02",
            launcherAliasClassName("com.animeow.app", "icon_02"),
        )
    }

    @Test
    fun aliasNormalizationAvoidsDuplicateSeparators() {
        assertEquals(
            "com.animeow.app.icon_default",
            launcherAliasClassName("com.animeow.app.", ".icon_default"),
        )
    }

    @Test
    fun removedIcon02FallsBackToDefault() {
        assertEquals(LauncherIcon.DEFAULT, LauncherIcon.fromStorage("icon_02"))
    }
}
