package com.animeow.app.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.animeow.app.ui.overlay.OverlayStylePreference
import com.animeow.app.ui.theme.OverlayStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayStylePickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allThreeChoicesAreSelectableAndDismissDoesNotChangeTheChoice() {
        val selected = mutableStateOf(OverlayStyle.QUIET_PAW)
        var changes = 0
        compose.setContent { MaterialTheme { OverlayStylePreference(selected.value) { selected.value = it; changes++ } } }
        compose.onNodeWithText("悬浮球外观").performClick()
        compose.onNodeWithText("静音猫爪（默认）").assertIsDisplayed()
        compose.onNodeWithText("月光琉璃").performClick()
        assertEquals(OverlayStyle.MOON_GLASS, selected.value)
        compose.onNodeWithText("悬浮球外观").performClick()
        compose.onNodeWithText("关闭").performClick()
        assertEquals(1, changes)
        compose.onNodeWithText("悬浮球外观").performClick()
        compose.onNodeWithText("奶油猫团").performClick()
        assertEquals(OverlayStyle.CREAM_CAT, selected.value)
        compose.onNodeWithText("悬浮球外观").performClick()
        compose.onNodeWithText("静音猫爪（默认）").performClick()
        assertEquals(OverlayStyle.QUIET_PAW, selected.value)
        assertEquals(3, changes)
    }
}
