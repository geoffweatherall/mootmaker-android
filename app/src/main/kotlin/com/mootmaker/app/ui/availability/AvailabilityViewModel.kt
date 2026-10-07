package com.mootmaker.app.ui.availability

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.api.AvailabilityData
import com.mootmaker.data.api.AvailabilitySource
import com.mootmaker.data.auth.SessionExpiredException
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
    /** Null until the first load finishes. Kept on screen while another day loads. */
    val data: AvailabilityData? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** Rooms whose meeting list is open. Belongs to [date]: a new day starts collapsed. */
    val expanded: Set<String> = emptySet(),
) {
    val isToday: Boolean get() = date == today
    val canGoBack: Boolean get() = data?.bounds?.let { date.minusDays(1) >= it.earliest } ?: true
    val canGoForward: Boolean get() = data?.bounds?.let { date.plusDays(1) <= it.latest } ?: true
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

    private var loadJob: Job? = null

    /** Loads [AvailabilityState.date]. Called when the screen becomes visible and on every day change. */
    fun refresh() {
        loadJob?.cancel()
        val now = clock()
        val date = _state.value.date
        _state.update { it.copy(today = now.toLocalDate(), nowMinutes = now.hour * 60 + now.minute, loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val data = source.load(date)
                _state.update { it.copy(data = data, loading = false) }
            } catch (expired: SessionExpiredException) {
                // The session has signed out; navigation takes the user back to sign-in.
                _state.update { it.copy(loading = false) }
            } catch (failure: ApiException) {
                _state.update { it.copy(loading = false, error = failure.message) }
            }
        }
    }

    fun goTo(date: LocalDate) {
        val bounds = _state.value.data?.bounds
        val target = if (bounds == null) date else date.coerceIn(bounds.earliest, bounds.latest)
        if (target == _state.value.date) return
        _state.update { it.copy(date = target, expanded = emptySet()) }
        refresh()
    }

    fun previousDay() = goTo(_state.value.date.minusDays(1))

    fun nextDay() = goTo(_state.value.date.plusDays(1))

    fun toggleExpanded(roomId: String) {
        _state.update { it.copy(expanded = if (roomId in it.expanded) it.expanded - roomId else it.expanded + roomId) }
    }
}
