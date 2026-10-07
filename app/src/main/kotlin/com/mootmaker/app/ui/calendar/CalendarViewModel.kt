package com.mootmaker.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.api.CalendarData
import com.mootmaker.data.api.CalendarSource
import com.mootmaker.data.auth.SessionExpiredException
import com.mootmaker.data.calendar.canGoToNextWeek
import com.mootmaker.data.calendar.canGoToPreviousWeek
import com.mootmaker.data.calendar.startOfWorkWeek
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class CalendarState(
    val personId: String,
    /** The visible week's Monday. */
    val monday: LocalDate,
    val today: LocalDate,
    /** Null until the first load finishes. Kept on screen while another week or person loads. */
    val data: CalendarData? = null,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val personName: String? get() = data?.people?.firstOrNull { it.id == personId }?.name
    val isThisWeek: Boolean get() = monday == startOfWorkWeek(today)
    val canGoBack: Boolean get() = canGoToPreviousWeek(monday, data?.bounds)
    val canGoForward: Boolean get() = canGoToNextWeek(monday, data?.bounds)
}

class CalendarViewModel(
    private val source: CalendarSource,
    personId: String,
    private val clock: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {
    private val _state = MutableStateFlow(CalendarState(personId, startOfWorkWeek(clock()), clock()))
    val state: StateFlow<CalendarState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** Loads the visible week. Called when the screen becomes visible and on every week or person change. */
    fun refresh() {
        loadJob?.cancel()
        val current = _state.value
        _state.update { it.copy(today = clock(), loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val data = source.load(current.personId, current.monday)
                _state.update { it.copy(data = data, loading = false) }
            } catch (expired: SessionExpiredException) {
                // The session has signed out; navigation takes the user back to sign-in.
                _state.update { it.copy(loading = false) }
            } catch (failure: ApiException) {
                _state.update { it.copy(loading = false, error = failure.message) }
            }
        }
    }

    fun previousWeek() = goTo(_state.value.monday.minusWeeks(1), allowed = _state.value.canGoBack)

    fun nextWeek() = goTo(_state.value.monday.plusWeeks(1), allowed = _state.value.canGoForward)

    fun thisWeek() = goTo(startOfWorkWeek(clock()), allowed = true)

    fun selectPerson(personId: String) {
        if (personId == _state.value.personId) return
        _state.update { it.copy(personId = personId) }
        refresh()
    }

    private fun goTo(monday: LocalDate, allowed: Boolean) {
        if (!allowed || monday == _state.value.monday) return
        _state.update { it.copy(monday = monday) }
        refresh()
    }
}
