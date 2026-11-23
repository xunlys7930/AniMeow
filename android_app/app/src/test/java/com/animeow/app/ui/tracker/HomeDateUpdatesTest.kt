package com.animeow.app.ui.tracker

import java.time.LocalDate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class HomeDateUpdatesTest {
    @Test fun foregroundRefreshPublishesNewDateWithoutWaitingForMinuteTick() = runBlocking {
        val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var today = LocalDate.of(2026, 10, 25)
        val dates = mutableListOf<LocalDate>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { homeDateUpdates(refresh) { today }.take(2).toList(dates) }
        withTimeout(2000) { while (dates.isEmpty()) yield() }
        refresh.emit(Unit)
        yield()
        assertEquals(1, dates.size)
        today = today.plusDays(1)
        refresh.emit(Unit)
        withTimeout(2000) { job.join() }
        assertEquals(listOf(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26)), dates)
    }
}
