package com.animeow.app.data.preferences

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
class LibraryCategoriesTest {
    @Test fun audioIsHiddenByDefaultAndSelectionPersists() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("library_categories", 0).edit().clear().commit()
        LibraryCategories.init(context)
        assertEquals(setOf("anime", "book", "game"), LibraryCategories.visible.value)

        LibraryCategories.setVisible(setOf("anime", "audio", "unknown"))
        assertEquals(setOf("anime", "audio"), LibraryCategories.visible.value)

        LibraryCategories.init(context)
        assertEquals(setOf("anime", "audio"), LibraryCategories.visible.value)
    }
}
