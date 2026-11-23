package com.animeow.app.util

import kotlinx.coroutines.sync.Mutex

/**
 * Serializes full-library transfer operations inside this app process. Room already protects
 * individual transactions; this gate additionally prevents cloud baselines from being updated
 * from a snapshot taken while an import or restore is replacing external files and preferences.
 */
private val dataTransferMutex = Mutex()

internal suspend fun <T> withDataTransferLock(block: suspend () -> T): T {
    dataTransferMutex.lock()
    return try {
        block()
    } finally {
        dataTransferMutex.unlock()
    }
}
