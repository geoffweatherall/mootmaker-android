package com.mootmaker.app.ui.availability

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mootmaker.data.api.AvailabilityData
import com.mootmaker.data.api.AvailabilitySource
import com.mootmaker.data.auth.SessionExpiredException
import com.mootmaker.data.cache.screenMessage
import com.mootmaker.data.api.DateBounds
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

data class AvailabilityState(
    val date: LocalDate,
    val today: LocalDate,
    /** Minutes since midnight when the data was requested: what "free now" is relative to. */
    val nowMinutes: Int,
    /**
     * [date]'s data: null until that date is known, so another date's rooms never show under it, and
     * "no meetings" only ever means the date is loaded and empty (use case M.92, #25).
     */
    val data: AvailabilityData? = null,
    /** The navigable window, kept across date changes so the arrows stay right while a date loads. */
    val bounds: DateBounds? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** Rooms whose meeting list is open. Belongs to [date]: a new day starts collapsed. */
    val expanded: Set<String> = emptySet(),
) {
    val isToday: Boolean get() = date == today
    val canGoBack: Boolean get() = bounds?.let { date.minusDays(1) >= it.earliest } ?: true
    val canGoForward: Boolean get() = bounds?.let { date.plusDays(1) <= it.latest } ?: true
}

class AvailabilityViewModel(
    private val source: AvailabilitySource,
    startDate: LocalDate,
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
) : ViewModel() {
    private val _state = MutableStateFlow(
        clock().let { AvailabilityState(date = startDate, today = it.toLocalDate(), nowMinutes = it.hour * 60 + it.minute) },
    )
    val state: StateFlow<AvailabilityState> = _state.asStateFlow()

    private var watching: Job? = null

    init {
        watch()
    }

    /** Draws [AvailabilityState.date] from the store: at once when held, then as refetches land. */
    private fun watch() {
        watching?.cancel()
        val date = _state.value.date
        watching = viewModelScope.launch {
            source.observe(date).collect { loaded ->
                _state.update { state ->
                    state.copy(
                        data = loaded.data,
                        bounds = loaded.data?.bounds ?: state.bounds,
                        loading = loaded.fetching || (loaded.data == null && loaded.error == null),
                        error = loaded.error?.takeUnless { it is SessionExpiredException }?.screenMessage(),
                    )
                }
            }
        }
    }

    /** On becoming visible and on Try again: moves "now" on, and fetches again whatever failed. */
    fun refresh() {
        val now = clock()
        _state.update { it.copy(today = now.toLocalDate(), nowMinutes = now.hour * 60 + now.minute) }
        source.retry()
    }

    fun goTo(date: LocalDate) {
        val bounds = _state.value.bounds
        val target = if (bounds == null) date else date.coerceIn(bounds.earliest, bounds.latest)
        if (target == _state.value.date) return
        _state.update { it.copy(date = target, expanded = emptySet(), data = null) }
        watch()
    }

    fun previousDay() = goTo(_state.value.date.minusDays(1))

    fun nextDay() = goTo(_state.value.date.plusDays(1))

    fun toggleExpanded(roomId: String) {
        _state.update { it.copy(expanded = if (roomId in it.expanded) it.expanded - roomId else it.expanded + roomId) }
    }
}
