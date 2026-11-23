package com.animeow.app.ui.tracker

import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

internal fun homeDateUpdates(refresh: Flow<Unit>, currentDate: () -> LocalDate = { LocalDate.now() }): Flow<LocalDate> =
    merge(refresh, flow { while (true) { emit(Unit); delay(60_000) } })
        .map { currentDate() }.distinctUntilChanged()
