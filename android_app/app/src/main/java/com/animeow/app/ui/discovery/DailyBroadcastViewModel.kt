package com.animeow.app.ui.discovery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.remote.BroadcastDay
import com.animeow.app.data.remote.RemoteHttpException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun broadcastWeek(today: LocalDate): List<LocalDate> {
    val monday = today.with(DayOfWeek.MONDAY)
    return (0L..6L).map(monday::plusDays)
}

internal data class DailyBroadcastState(
    val date: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val now: Instant = Instant.now(),
    val loading: Boolean = true,
    val data: BroadcastDay? = null,
    val error: String? = null,
)

internal class DailyBroadcastViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).discoveryRepository
    private val _state = MutableStateFlow(DailyBroadcastState().let { initial ->
        val restored = savedState.get<Long>("broadcast_date")?.let { runCatching { LocalDate.ofEpochDay(it) }.getOrNull() }
        initial.copy(date = restored?.takeIf { it in broadcastWeek(initial.today) } ?: initial.today)
    })
    val state = _state.asStateFlow()
    private var job: Job? = null

    init { load() }

    fun selectDate(date: LocalDate) {
        if (date == state.value.date || date !in broadcastWeek(state.value.today)) return
        savedState["broadcast_date"] = date.toEpochDay()
        _state.update { it.copy(date = date, data = null, error = null) }
        load()
    }

    fun syncClock() {
        val current = state.value
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val changed = today != current.today || zone != current.zone
        val date = if (current.date == current.today || current.date !in broadcastWeek(today)) today else current.date
        _state.update { it.copy(today = today, date = date, zone = zone, now = Instant.now(), data = if (changed) null else it.data) }
        if (changed) { savedState["broadcast_date"] = date.toEpochDay(); load() }
    }

    fun load(forceRefresh: Boolean = false) {
        job?.cancel()
        val request = state.value
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val data = repository.dailyBroadcast(request.date, request.zone, forceRefresh)
                _state.update { it.copy(data = data, loading = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val message = when (error) {
                    is RemoteHttpException -> if (error.statusCode == 429) "查询次数较多，请稍后重试" else "放送来源暂时不可用，请稍后重试或检查资料访问方式"
                    is IllegalStateException -> error.message ?: "放送资料暂时无法读取，请稍后重试"
                    else -> "放送资料暂时无法读取，请检查网络后重试"
                }
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }
}
