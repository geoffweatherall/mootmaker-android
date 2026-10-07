package com.mootmaker.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.api.HomeData
import com.mootmaker.data.api.HomeSource
import com.mootmaker.data.api.MeetingSource
import com.mootmaker.data.api.WriteResult
import com.mootmaker.data.auth.SessionExpiredException
import com.mootmaker.data.meeting.AttendeeStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HomeState(
    val today: LocalDate,
    /** Null until the first load finishes. Kept on screen while a refresh runs. */
    val data: HomeData? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** How many times "Search further ahead" has widened the needs-response window. */
    val searchLevel: Int = 0,
    /** Meetings whose response is being saved; their buttons are disabled meanwhile. */
    val responding: Set<String> = emptySet(),
    /** Why the last response was not saved. Kept apart from [error] so the reload that follows doesn't clear it. */
    val respondError: String? = null,
)

class HomeViewModel(
    private val source: HomeSource,
    private val respondSource: MeetingSource,
    private val clock: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState(today = clock()))
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** Refetches. Called whenever the screen becomes visible (design: refetch on visible until M6). */
    fun refresh() {
        if (loadJob?.isActive == true) return
        val today = clock()
        _state.update { it.copy(today = today, loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val data = source.load(today, _state.value.searchLevel)
                _state.update { it.copy(data = data, loading = false) }
            } catch (expired: SessionExpiredException) {
                // The session has signed out; navigation takes the user back to sign-in.
                _state.update { it.copy(loading = false) }
            } catch (failure: ApiException) {
                _state.update { it.copy(loading = false, error = failure.message) }
            }
        }
    }

    /** Widens the needs-response window by another step and reloads. */
    fun searchFurtherAhead() {
        _state.update { it.copy(searchLevel = it.searchLevel + 1) }
        refresh()
    }

    /** Records the caller's response, then reloads so the answered meeting leaves the list. */
    fun respond(meetingId: String, status: AttendeeStatus) {
        if (meetingId in _state.value.responding) return
        _state.update { it.copy(responding = it.responding + meetingId, respondError = null) }
        viewModelScope.launch {
            try {
                when (val result = respondSource.respond(meetingId, status)) {
                    WriteResult.Done -> Unit
                    is WriteResult.Rejected -> _state.update { it.copy(respondError = result.messages.joinToString("\n")) }
                }
            } catch (expired: SessionExpiredException) {
                // Signed out; navigation takes the user back to sign-in.
            } catch (failure: ApiException) {
                _state.update { it.copy(respondError = failure.message) }
            } finally {
                _state.update { it.copy(responding = it.responding - meetingId) }
            }
            loadJob?.join()
            refresh()
        }
    }
}
