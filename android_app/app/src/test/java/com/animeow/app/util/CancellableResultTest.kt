package com.animeow.app.util

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableResultTest {
    @Test(expected = CancellationException::class)
    fun cancellationIsNeverConvertedIntoFailureResult() {
        runCatchingCancellable<Unit> { throw CancellationException("stop") }
    }

    @Test
    fun regularFailureRemainsInspectable() {
        val result = runCatchingCancellable<Unit> { throw IOException("offline") }

        assertTrue(result.isFailure)
        assertEquals("offline", result.exceptionOrNull()?.message)
    }
}
