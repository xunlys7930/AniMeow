package com.animeow.app.ui.importer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.bangumi.*
import com.animeow.app.data.cloud.SecureCloudSessionStore
import com.animeow.app.util.runCatchingCancellable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class BangumiBatchDirection { UPLOAD, DOWNLOAD }
internal enum class BangumiBatchField { STATUS, RATING, PROGRESS }

internal fun BangumiUploadRow.isBatchEligible(direction: BangumiBatchDirection, field: BangumiBatchField): Boolean = when (direction) {
    BangumiBatchDirection.UPLOAD -> when (field) {
        BangumiBatchField.STATUS -> available.status
        BangumiBatchField.RATING -> available.rating && (local.rate ?: 0) > 0
        BangumiBatchField.PROGRESS -> available.progress && (local.progress ?: 0) > (remote?.progress ?: 0)
    }
    BangumiBatchDirection.DOWNLOAD -> when (field) {
        BangumiBatchField.STATUS -> downloadable.status
        BangumiBatchField.RATING -> downloadable.rating && (remote?.rate ?: 0) > 0
        BangumiBatchField.PROGRESS -> downloadable.progress && (remote?.progress ?: 0) > (local.progress ?: 0)
    }
}

internal fun BangumiUploadChoice.hasField(direction: BangumiBatchDirection, field: BangumiBatchField): Boolean = when (direction) {
    BangumiBatchDirection.UPLOAD -> when (field) {
        BangumiBatchField.STATUS -> status
        BangumiBatchField.RATING -> rating
        BangumiBatchField.PROGRESS -> progress
    }
    BangumiBatchDirection.DOWNLOAD -> when (field) {
        BangumiBatchField.STATUS -> downloadStatus
        BangumiBatchField.RATING -> downloadRating
        BangumiBatchField.PROGRESS -> downloadProgress
    }
}

internal data class BangumiUploadState(
    val kind: BangumiUploadKind = BangumiUploadKind.ANIME,
    val reading: Boolean = false, val uploading: Boolean = false, val stopping: Boolean = false,
    val progress: String = "", val preview: BangumiUploadPreview? = null,
    val choices: Map<Long, BangumiUploadChoice> = emptyMap(), val error: String? = null,
    val report: String? = null,
    val association: BangumiUploadAssociation? = null,
) {
    val busy get() = reading || uploading
    val selectedCount get() = choices.values.count { it.selected }
}

internal class BangumiUploadViewModel @JvmOverloads constructor(application: Application,
    private val service: BangumiUploadService = BangumiUploadService(application, (application as AniMeowApplication).database),
) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(BangumiUploadState())
    val state = mutable.asStateFlow()
    private var readingJob: Job? = null
    private val stop = AtomicBoolean(false)

    init { refreshSession() }

    fun refreshSession() {
        val userId = runCatching { SecureCloudSessionStore(getApplication()).load()?.userId }.getOrNull()
        val previousOwner = mutable.value.preview?.account?.userId ?: mutable.value.report?.let { JSONObject(it).optLong("user_id") }
        if (!mutable.value.busy && previousOwner != null && previousOwner != userId) {
            mutable.value = BangumiUploadState(kind = mutable.value.kind)
        }
        if (mutable.value.report == null) viewModelScope.launch(Dispatchers.IO) {
            val report = service.lastReport(userId)
            mutable.update { if (it.report == null && it.preview == null && !it.busy) it.copy(report = report) else it }
        }
    }

    fun chooseKind(kind: BangumiUploadKind) {
        if (!state.value.busy) mutable.update { it.copy(kind = kind, preview = null, choices = emptyMap(), error = null) }
    }

    fun read() {
        if (state.value.busy) return
        val kind = state.value.kind
        mutable.update { it.copy(reading = true, preview = null, choices = emptyMap(), error = null, progress = "正在核对绑定账号") }
        readingJob = viewModelScope.launch {
            runCatchingCancellable { service.preview(kind) { read, total ->
                mutable.update { it.copy(progress = "已读取 $read / $total 项收藏") }
            } }.fold(onSuccess = { preview ->
                mutable.update { it.copy(reading = false, preview = preview, progress = "", report = null) }
            }, onFailure = { error -> mutable.update { it.copy(reading = false,
                error = bangumiUploadFailureMessage(error, "读取失败，请稍后重试"), progress = "") } })
        }
    }

    fun cancelRead() {
        if (!state.value.reading) return
        readingJob?.cancel()
        mutable.update { it.copy(reading = false, progress = "", preview = null, choices = emptyMap()) }
    }

    fun select(id: Long, choice: BangumiUploadChoice) {
        if (state.value.busy) return
        val row = state.value.preview?.rows?.firstOrNull { it.local.id == id } ?: return
        val previous = state.value.choices[id] ?: BangumiUploadChoice()
        val safe = row.sanitize(choice, previous)
        mutable.update { current ->
            val updated = if (safe.selected) current.choices + (id to safe) else current.choices - id
            current.copy(choices = updated)
        }
    }

    fun selectRows(ids: Set<Long>, localOnly: Boolean) {
        val current = state.value
        if (current.busy) return
        val next = current.choices.toMutableMap()
        current.preview?.rows?.filter { it.local.id in ids && (!localOnly || it.remote == null) }
            ?.forEach { row ->
                val safe = row.safeUploadChoice()
                if (safe.selected) next[row.local.id] = safe else next.remove(row.local.id)
            }
        mutable.update { it.copy(choices = next) }
    }

    fun selectDownloadRows(ids: Set<Long>) {
        val current = state.value
        if (current.busy) return
        val next = current.choices.toMutableMap()
        current.preview?.rows?.filter { it.local.id in ids }?.forEach { row ->
            val safe = row.safeDownloadChoice()
            if (safe.selected) next[row.local.id] = safe
        }
        mutable.update { it.copy(choices = next) }
    }

    fun toggleBatchField(ids: Set<Long>, direction: BangumiBatchDirection, field: BangumiBatchField) {
        val current = state.value
        if (current.busy) return
        val eligible = current.preview?.rows?.filter { it.local.id in ids && it.isBatchEligible(direction, field) }.orEmpty()
        if (eligible.isEmpty()) return
        val selectAll = eligible.any { !(current.choices[it.local.id]?.hasField(direction, field) ?: false) }
        val next = current.choices.toMutableMap()
        eligible.forEach { row ->
            val prev = next[row.local.id] ?: BangumiUploadChoice()
            val raw = when (direction) {
                BangumiBatchDirection.UPLOAD -> when (field) {
                    BangumiBatchField.STATUS -> prev.copy(status = selectAll, downloadStatus = if (selectAll) false else prev.downloadStatus)
                    BangumiBatchField.RATING -> prev.copy(rating = selectAll, downloadRating = if (selectAll) false else prev.downloadRating)
                    BangumiBatchField.PROGRESS -> prev.copy(progress = selectAll, downloadProgress = if (selectAll) false else prev.downloadProgress)
                }
                BangumiBatchDirection.DOWNLOAD -> when (field) {
                    BangumiBatchField.STATUS -> prev.copy(downloadStatus = selectAll, status = if (selectAll) false else prev.status)
                    BangumiBatchField.RATING -> prev.copy(downloadRating = selectAll, rating = if (selectAll) false else prev.rating)
                    BangumiBatchField.PROGRESS -> prev.copy(downloadProgress = selectAll, progress = if (selectAll) false else prev.progress)
                }
            }
            val safe = row.sanitize(raw, prev)
            if (safe.selected) next[row.local.id] = safe else next.remove(row.local.id)
        }
        mutable.update { it.copy(choices = next) }
    }

    fun clearRows(ids: Set<Long>) {
        if (!state.value.busy) mutable.update { current -> current.copy(choices = current.choices - ids) }
    }

    fun clearSelection() { if (!state.value.busy) mutable.update { it.copy(choices = emptyMap()) } }

    fun saveBookProgress(id: Long, rawVolumes: String, rawChapters: String) {
        val preview = state.value.preview ?: return
        if (state.value.busy) return
        val volumes = rawVolumes.trim().toIntOrNull(); val chapters = rawChapters.trim().toIntOrNull()
        if (rawVolumes.isNotBlank() && volumes !in 0..10000 || rawChapters.isNotBlank() && chapters !in 0..10000) {
            mutable.update { it.copy(error = "卷数和话数请填写 0 至 10000，未知可留空") }; return
        }
        mutable.update { it.copy(reading = true, error = null, progress = "正在保存书籍进度") }
        readingJob = viewModelScope.launch {
            runCatchingCancellable { service.saveBookProgress(preview, id, volumes, chapters) }.fold(
                onSuccess = { mutable.update { it.copy(reading = false) }; read() },
                onFailure = { error -> mutable.update { it.copy(reading = false, progress = "", error = bangumiUploadFailureMessage(error, "书籍进度未保存，请重新读取")) } })
        }
    }

    fun proposeAssociation(id: Long, rawSubjectId: String) {
        val preview = state.value.preview ?: return
        if (state.value.busy) return
        val subjectId = rawSubjectId.trim().toLongOrNull()?.takeIf { it > 0 }
        if (subjectId == null) { mutable.update { it.copy(error = "请输入有效的 Bangumi 数字编号") }; return }
        mutable.update { it.copy(reading = true, error = null, association = null, progress = "正在核对关联作品") }
        readingJob = viewModelScope.launch {
            runCatchingCancellable { service.proposeAssociation(preview, id, subjectId) }.fold(
                onSuccess = { proposal -> mutable.update { it.copy(reading = false, association = proposal, progress = "") } },
                onFailure = { error -> mutable.update { it.copy(reading = false, progress = "", error = bangumiUploadFailureMessage(error, "关联资料读取失败，请重试")) } })
        }
    }
    fun dismissAssociation() { mutable.update { it.copy(association = null) } }
    fun confirmAssociation() {
        val proposal = state.value.association ?: return
        if (state.value.busy) return
        mutable.update { it.copy(reading = true, association = null, error = null, progress = "正在保存关联") }
        readingJob = viewModelScope.launch {
            runCatchingCancellable { service.confirmAssociation(proposal) }.fold(
                onSuccess = { mutable.update { it.copy(reading = false) }; read() },
                onFailure = { error -> mutable.update { it.copy(reading = false, progress = "", error = bangumiUploadFailureMessage(error, "关联未保存，请重新读取")) } })
        }
    }

    fun importRemote(subjectId: Long, includeProgress: Boolean) {
        val preview = state.value.preview ?: return
        if (state.value.busy) return
        mutable.update { it.copy(uploading = true, error = null, report = null) }
        viewModelScope.launch {
            runCatchingCancellable { service.importRemote(preview, subjectId, includeProgress) }.fold(
                onSuccess = { report -> mutable.update { it.copy(uploading = false, stopping = false, preview = null, choices = emptyMap(), report = report) } },
                onFailure = { error ->
                    val report = withContext(Dispatchers.IO) { service.lastReport(preview.account.userId) }
                    mutable.update { it.copy(uploading = false, stopping = false, preview = null, choices = emptyMap(), report = report,
                        error = bangumiUploadFailureMessage(error, "添加结果需要核实，请重新读取；不会自动重复添加")) }
                })
        }
    }

    fun upload(privateNew: Boolean) {
        val current = state.value
        if (current.busy || current.selectedCount == 0) return
        val preview = current.preview ?: return
        stop.set(false)
        mutable.update { it.copy(uploading = true, stopping = false, error = null, report = null) }
        viewModelScope.launch {
            runCatchingCancellable { service.execute(preview, current.choices, privateNew, stop::get) { report ->
                mutable.update { it.copy(report = report) }
            } }.fold(onSuccess = { report ->
                mutable.update { it.copy(uploading = false, stopping = false, preview = null, choices = emptyMap(), report = report) }
            }, onFailure = { error -> mutable.update { it.copy(uploading = false, stopping = false, preview = null, choices = emptyMap(),
                error = bangumiUploadFailureMessage(error, "操作未完成，请重新读取对比；已发出的修改可能需要核实")) } })
        }
    }

    fun stopAfterCurrent() {
        stop.set(true); mutable.update { it.copy(stopping = true) }
    }

    fun export(uri: Uri) {
        val report = state.value.report ?: return
        viewModelScope.launch {
            runCatchingCancellable { withContext(Dispatchers.IO) {
                check(SecureCloudSessionStore(getApplication()).load()?.userId == JSONObject(report).optLong("user_id")) { "账号已变化，请重新登录后查看记录" }
                getApplication<Application>().contentResolver.openOutputStream(uri, "w")?.use {
                    it.write(report.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入所选位置")
            } }.onFailure { error -> mutable.update { it.copy(error = bangumiUploadFailureMessage(error, "导出失败，请换个位置重试")) } }
        }
    }
}
