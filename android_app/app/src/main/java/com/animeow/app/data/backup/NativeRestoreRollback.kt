package com.animeow.app.data.backup

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Register before touching a store, so a cancelled write that already committed is still undone. */
internal class NativeRestoreRollback {
    private val actions = mutableListOf<Pair<String, suspend () -> Unit>>()
    fun beforeWrite(name: String, undo: suspend () -> Unit) { actions += name to undo }
    fun commit() { actions.clear() }

    suspend fun rollback(original: Throwable, report: (String, Throwable) -> Unit): Boolean = withContext(NonCancellable) {
        val pending = actions.asReversed().toList(); actions.clear()
        var complete = true
        for ((name, undo) in pending) {
            try { undo() }
            catch (error: Throwable) {
                complete = false
                if (error !== original) original.addSuppressed(error)
                runCatching { report(name, error) }.exceptionOrNull()?.let { if (it !== original) original.addSuppressed(it) }
            }
        }
        complete
    }
}
