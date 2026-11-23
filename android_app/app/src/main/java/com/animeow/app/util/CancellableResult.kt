package com.animeow.app.util

import kotlinx.coroutines.CancellationException

/**
 * Equivalent to [runCatching], except coroutine cancellation is never converted into a normal
 * failure result. Use this around suspend work launched from a ViewModel or worker.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}
