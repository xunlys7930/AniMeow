package com.animeow.app.data.backup

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeRestoreRollbackTest {
    @Test fun undoesInReverseOrderAndContinuesAfterAFailedUndo() = runBlocking {
        val journal = NativeRestoreRollback(); val undone = mutableListOf<String>(); val reported = mutableListOf<String>()
        journal.beforeWrite("first") { undone += "first" }
        journal.beforeWrite("second") { undone += "second"; throw java.io.IOException("synthetic") }
        journal.beforeWrite("third") { undone += "third" }
        val original = java.io.IOException("original")
        assertFalse(journal.rollback(original) { name, _ -> reported += name })
        assertEquals(listOf("third", "second", "first"), undone); assertEquals(listOf("second"), reported)
        assertEquals(1, original.suppressed.size)
        assertTrue(journal.rollback(original) { _, _ -> fail("Already consumed") }); assertEquals(3, undone.size)
    }

    @Test fun commitPreventsUndoingAnAlreadyCommittedRestore() = runBlocking {
        val journal = NativeRestoreRollback(); var called = false
        journal.beforeWrite("committed") { called = true }; journal.commit()
        assertTrue(journal.rollback(java.io.IOException("late failure")) { _, _ -> }); assertFalse(called)
    }

    @Test fun callerCancellationCannotInterruptRemainingUndoWrites() = runBlocking {
        val journal = NativeRestoreRollback(); val undone = mutableListOf<String>()
        journal.beforeWrite("first") { delay(1); undone += "first" }
        journal.beforeWrite("second") { delay(1); undone += "second" }
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { journal.rollback(CancellationException("synthetic")) { _, _ -> } }
        }
        task.cancelAndJoin(); assertEquals(listOf("second", "first"), undone)
    }

    @Test fun diagnosticsFailureCannotPreventRemainingUndoActions() = runBlocking {
        val journal = NativeRestoreRollback(); var restored = false
        journal.beforeWrite("last remaining") { restored = true }
        journal.beforeWrite("failed") { throw java.io.IOException("synthetic undo failure") }
        val original = java.io.IOException("original")
        assertFalse(journal.rollback(original) { _, _ -> throw IllegalStateException("synthetic logger failure") })
        assertTrue(restored); assertEquals(2, original.suppressed.size)
    }
}
