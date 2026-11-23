package com.animeow.app.ui.preferences

import com.animeow.app.data.preferences.ConfigurationBundle

/** Draft checkpoints advance only after the corresponding settings write succeeds. */
internal class CustomizationDraft {
    var original: ConfigurationBundle? = null
        private set
    private val history = ArrayDeque<ConfigurationBundle>()
    private var refreshBranding = false
    var classicTarget: ConfigurationBundle? = null
        private set
    private var classicHistory: List<ConfigurationBundle> = emptyList()

    val canUndo: Boolean get() = history.isNotEmpty()
    val undoTarget: ConfigurationBundle? get() = history.lastOrNull()

    fun begin(current: ConfigurationBundle) {
        if (original == null) original = current
        if (refreshBranding) {
            // Branding saves immediately, so every older checkpoint must preserve its new value.
            original = original?.copy(branding = current.branding)
            val rebased = history.map { it.copy(branding = current.branding) }
            history.clear(); history.addAll(rebased)
            classicTarget = classicTarget?.copy(branding = current.branding)
            classicHistory = classicHistory.map { it.copy(branding = current.branding) }
            refreshBranding = false
        }
    }

    fun openBranding(current: ConfigurationBundle) {
        if (original == null) original = current
        refreshBranding = true
    }

    fun commit(current: ConfigurationBundle) {
        original = current
        clear()
    }

    fun discarded() {
        original = null
        clear()
    }

    fun record(before: ConfigurationBundle, classic: Boolean = false) {
        if (classic) {
            classicTarget = before
            classicHistory = history.toList()
        }
        history.addLast(before)
        while (history.size > 20) history.removeFirst()
    }

    fun undoCompleted() {
        history.removeLast()
        finishClassicUndo()
    }

    fun classicUndoCompleted() {
        history.clear(); history.addAll(classicHistory)
        finishClassicUndo()
    }

    fun finishClassicUndo() {
        classicTarget = null
        classicHistory = emptyList()
    }

    private fun clear() {
        history.clear()
        refreshBranding = false
        finishClassicUndo()
    }
}
