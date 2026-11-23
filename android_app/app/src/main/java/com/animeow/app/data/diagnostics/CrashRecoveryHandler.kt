package com.animeow.app.data.diagnostics

import android.content.Context
import java.io.File
import java.time.Instant
import org.json.JSONObject

class CrashRecoveryHandler private constructor(
    private val pendingFile: File,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            pendingFile.parentFile?.mkdirs()
            pendingFile.writeText(
                JSONObject()
                    .put("time", Instant.now().toString())
                    .put("thread", OperationLogService.sanitizeText(thread.name, 120))
                    .put("message", OperationLogService.sanitizeText(throwable.toString(), 1200))
                    .put("stack", OperationLogService.sanitizeText(throwable.stackTraceToString(), 12_000))
                    .toString(),
                Charsets.UTF_8,
            )
        }
        previous?.uncaughtException(thread, throwable)
    }

    suspend fun recoverInto(service: OperationLogService) {
        if (!pendingFile.exists()) return
        runCatching {
            val root = JSONObject(pendingFile.readText(Charsets.UTF_8))
            service.recordRecoveredCrash(
                message = "${root.optString("time")} · ${root.optString("thread")} · ${root.optString("message")}",
                stackTrace = root.optString("stack"),
            )
            service.flush()
            pendingFile.delete()
        }
    }

    companion object {
        fun install(context: Context): CrashRecoveryHandler {
            val handler = CrashRecoveryHandler(
                pendingFile = File(context.applicationContext.filesDir, "diagnostics/pending_crash.json"),
                previous = Thread.getDefaultUncaughtExceptionHandler(),
            )
            Thread.setDefaultUncaughtExceptionHandler(handler)
            return handler
        }
    }
}
