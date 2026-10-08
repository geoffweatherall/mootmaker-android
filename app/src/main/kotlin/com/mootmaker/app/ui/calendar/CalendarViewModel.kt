package com.mootmaker.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mootmaker.data.api.CalendarData
import com.mootmaker.data.api.CalendarSource
import com.mootmaker.data.auth.SessionExpiredException
import com.mootmaker.data.cache.screenMessage
import com.mootmaker.data.calendar.CalendarBounds
import com.mootmaker.data.meeting.PersonRef
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
    /**
     * The visible week's data for [personId]: null until that week is known, so one week's or
     * person's meetings never show under another's header (#24).
     */
    val data: CalendarData? = null,
    /** Everyone and the navigable window, kept across week and person changes while one loads. */
    val people: List<PersonRef> = emptyList(),
    val bounds: CalendarBounds? = null,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val personName: String? get() = people.firstOrNull { it.id == personId }?.name
    val isThisWeek: Boolean get() = monday == startOfWorkWeek(today)
    val canGoBack: Boolean get() = canGoToPreviousWeek(monday, bounds)
    val canGoForward: Boolean get() = canGoToNextWeek(monday, bounds)
}

class CalendarViewModel(
    private val source: CalendarSource,
    personId: String,
    private val clock: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {
    private val _state = MutableStateFlow(CalendarState(personId, startOfWorkWeek(clock()), clock()))
    val state: StateFlow<CalendarState> = _state.asStateFlow()

    private var watching: Job? = null

    init {
        watch()
    }

    /** Draws the visible week from the store: at once when held, then as refetches land. */
    private fun watch() {
        watching?.cancel()
        val current = _state.value
        watching = viewModelScope.launch {
            source.observe(current.personId, current.monday).collect { loaded ->
                _state.update { state ->
                    state.copy(
                        data = loaded.data,
                        people = loaded.data?.people ?: state.people,
                        bounds = loaded.data?.bounds ?: state.bounds,
                        loading = loaded.fetching || (loaded.data == null && loaded.error == null),
                        error = loaded.error?.takeUnless { it is SessionExpiredException }?.screenMessage(),
                    )
                }
            }
        }
    }

    /** On becoming visible and on Try again: fetches again whatever failed. */
    fun refresh() {
        _state.update { it.copy(today = clock()) }
        source.retry()
    }

    fun previousWeek() = goTo(_state.value.monday.minusWeeks(1), allowed = _state.value.canGoBack)

    fun nextWeek() = goTo(_state.value.monday.plusWeeks(1), allowed = _state.value.canGoForward)

    fun thisWeek() = goTo(startOfWorkWeek(clock()), allowed = true)

    fun selectPerson(personId: String) {
        if (personId == _state.value.personId) return
        _state.update { it.copy(personId = personId, data = null) }
        watch()
    }

    private fun goTo(monday: LocalDate, allowed: Boolean) {
        if (!allowed || monday == _state.value.monday) return
        _state.update { it.copy(monday = monday, data = null) }
        watch()
    }
}
