package com.animeow.app.ui.preferences

import com.animeow.app.data.preferences.ConfigurationBundle
import com.animeow.app.data.preferences.PortableBrandingSettings
import com.animeow.app.ui.theme.AppearanceSettings
import org.junit.Assert.*
import org.junit.Test

class CustomizationDraftTest {
    private fun config(scale: Float) = ConfigurationBundle(AppearanceSettings(fontScale = scale), branding = PortableBrandingSettings())

    @Test fun enteringAgainKeepsTheInitialBaselineAndCommitStartsANewOne() {
        val draft = CustomizationDraft()
        draft.begin(config(1f)); draft.record(config(1f)); draft.begin(config(1.2f))
        assertEquals(config(1f), draft.original); assertEquals(config(1f), draft.undoTarget)
        draft.commit(config(1.2f))
        assertEquals(config(1.2f), draft.original); assertFalse(draft.canUndo)
        draft.record(config(1.2f)); draft.discarded()
        assertNull(draft.original); assertNull(draft.undoTarget); assertNull(draft.classicTarget)
    }

    @Test fun failedWritesKeepTheirTargetsAndHistoryIsBounded() {
        val draft = CustomizationDraft()
        draft.begin(config(1f))
        repeat(25) { draft.record(config(it.toFloat())) }
        // Reading an undo target does not consume it; callers acknowledge only a successful write.
        assertEquals(config(24f), draft.undoTarget); assertEquals(config(24f), draft.undoTarget)
        val undone = buildList { while (draft.canUndo) { add(draft.undoTarget); draft.undoCompleted() } }
        assertEquals(20, undone.size); assertEquals(config(5f), undone.last())
    }

    @Test fun undoingAClassicPreviewRestoresThePreviousUndoChain() {
        val draft = CustomizationDraft()
        draft.begin(config(1f)); draft.record(config(1f)); draft.record(config(1.1f), classic = true)
        draft.record(config(1.2f))
        assertEquals(config(1.1f), draft.classicTarget)
        draft.classicUndoCompleted()
        assertNull(draft.classicTarget); assertEquals(config(1f), draft.undoTarget)
    }

    @Test fun immediateBrandingChangesSurviveBothOrdinaryAndClassicUndo() {
        val draft = CustomizationDraft()
        draft.begin(config(1f)); draft.record(config(1f)); draft.record(config(1.1f), classic = true)
        draft.openBranding(config(1.2f))
        val branded = config(1.2f).copy(branding = PortableBrandingSettings(splashDurationMillis = 2_000))
        draft.begin(branded)
        assertEquals(branded.branding, draft.original!!.branding)
        assertEquals(branded.branding, draft.undoTarget!!.branding)
        assertEquals(branded.branding, draft.classicTarget!!.branding)
        draft.classicUndoCompleted()
        assertEquals(1f, draft.undoTarget!!.appearance.fontScale)
        assertEquals(branded.branding, draft.undoTarget!!.branding)
    }
}
