package com.animeow.app.data.diagnostics

import android.content.Context
import android.net.Uri
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.DiagnosticLogEntity
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class OperationLogService(
    context: Context,
    database: AniMeowDatabase,
) {
    private val appContext = context.applicationContext
    private val dao = database.libraryDao()
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<LogCommand>(Channel.UNLIMITED)
    @Volatile
    var enabled: Boolean = preferences.getBoolean(KEY_ENABLED, false)
        private set

    init {
        scope.launch { processCommands() }
    }

    suspend fun setEnabled(value: Boolean) {
        enabled = value
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        if (!value) flush()
    }

    fun record(
        action: String,
        screen: String? = null,
        details: Map<String, Any?> = emptyMap(),
    ) {
        if (!enabled) return
        enqueue(
            level = "operation",
            message = buildString {
                append("[操作] ")
                screen?.trim()?.takeIf(String::isNotEmpty)?.let { append("[").append(sanitizeText(it, 80)).append("] ") }
                append(sanitizeText(action.trim(), 240))
            },
            detail = details.takeIf { it.isNotEmpty() }?.let(::encodeDetails),
        )
    }

    fun recordError(error: Throwable, screen: String? = null) {
        if (!enabled) return
        enqueue(
            level = "error",
            message = "[错误] ${screen?.let { "[${sanitizeText(it, 80)}] " }.orEmpty()}${sanitizeText(error.toString(), 1200)}",
            detail = sanitizeText(error.stackTraceToString(), 8_000),
        )
    }

    fun recordRecoveredCrash(message: String, stackTrace: String) {
        enqueue(
            level = "crash",
            message = "[崩溃恢复] ${sanitizeText(message, 1200)}",
            detail = sanitizeText(stackTrace, 12_000),
        )
    }

    suspend fun loadLogs(): List<DiagnosticLogEntity> {
        flush()
        return dao.getDiagnosticLogs(MAX_LOGS)
    }

    suspend fun clear() {
        flush()
        dao.clearDiagnosticLogs()
    }

    suspend fun export(uri: Uri): Int {
        val logs = loadLogs()
        appContext.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.appendLine("# AniMeow 诊断日志")
            writer.appendLine("# 生成时间：${Instant.now()}")
            writer.appendLine("# 操作日志：${if (enabled) "开启" else "关闭"}")
            writer.appendLine("# 所有已识别的密码、Token 与授权头会自动脱敏")
            writer.appendLine()
            logs.forEach { log ->
                writer.appendLine("[${log.timestamp}] [${log.level}] ${sanitizeText(log.message, 1200)}")
                log.stackTrace?.takeIf(String::isNotBlank)?.let { writer.appendLine(sanitizeText(it, 12_000)) }
                writer.appendLine("---")
            }
            if (logs.isEmpty()) writer.appendLine("暂无诊断日志。")
        } ?: error("无法写入日志文件")
        return logs.size
    }

    suspend fun flush() {
        val completion = CompletableDeferred<Unit>()
        commands.send(LogCommand.Flush(completion))
        completion.await()
    }

    private fun enqueue(level: String, message: String, detail: String?) {
        if (message.isBlank()) return
        commands.trySend(
            LogCommand.Entry(
                DiagnosticLogEntity(
                    level = level,
                    message = message,
                    stackTrace = detail,
                    timestamp = Instant.now().toString(),
                ),
            ),
        )
    }

    private suspend fun processCommands() {
        val pending = mutableListOf<DiagnosticLogEntity>()
        while (scope.isActive) {
            when (val command = commands.receive()) {
                is LogCommand.Entry -> {
                    pending += command.log
                    withTimeoutOrNull(FLUSH_DELAY_MS) {
                        while (pending.size < BATCH_SIZE) {
                            when (val next = commands.receive()) {
                                is LogCommand.Entry -> pending += next.log
                                is LogCommand.Flush -> {
                                    persist(pending)
                                    pending.clear()
                                    next.completion.complete(Unit)
                                }
                            }
                        }
                    }
                    persist(pending)
                    pending.clear()
                }
                is LogCommand.Flush -> {
                    persist(pending)
                    pending.clear()
                    command.completion.complete(Unit)
                }
            }
        }
    }

    private suspend fun persist(logs: List<DiagnosticLogEntity>) {
        if (logs.isEmpty()) return
        runCatching {
            dao.insertDiagnosticLogs(logs)
            dao.pruneDiagnosticLogs(MAX_LOGS)
            dao.clearDiagnosticLogsBefore(Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS).toString())
        }
    }

    private fun encodeDetails(details: Map<String, Any?>): String = sanitizeText(
        JSONObject(
            buildMap<String, Any?> {
                details.entries.take(20).forEach { (key, value) ->
                    put(
                        key.take(80),
                        if (isSensitiveKey(key)) "<redacted>" else value?.toString()?.take(500),
                    )
                }
            },
        ).toString(),
        4_000,
    )

    private sealed interface LogCommand {
        data class Entry(val log: DiagnosticLogEntity) : LogCommand
        data class Flush(val completion: CompletableDeferred<Unit>) : LogCommand
    }

    companion object {
        fun sanitizeText(value: String, maxLength: Int): String {
            var clean = value.replace(Regex("Bearer\\s+[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE), "Bearer <redacted>")
            clean = clean.replace(
                Regex(
                    "((?:password|passwd|token|api[_-]?key|secret|authorization)\\s*[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}&]+)",
                    RegexOption.IGNORE_CASE,
                ),
                "\$1<redacted>",
            )
            return if (clean.length <= maxLength) clean else clean.take(maxLength) + "…"
        }

        private fun isSensitiveKey(key: String): Boolean = key.trim().matches(
            Regex("password|passwd|token|api[_-]?key|secret|authorization", RegexOption.IGNORE_CASE),
        )

        private const val PREFERENCES_NAME = "diagnostic_preferences"
        private const val KEY_ENABLED = "operation_log_enabled"
        private const val MAX_LOGS = 500
        private const val RETENTION_DAYS = 14L
        private const val BATCH_SIZE = 12
        private const val FLUSH_DELAY_MS = 800L
    }
}
