package com.mootmaker.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.api.HomeData
import com.mootmaker.data.api.HomeSource
import com.mootmaker.data.api.MeetingSource
import com.mootmaker.data.api.WriteResult
import com.mootmaker.data.auth.SessionExpiredException
import com.mootmaker.data.cache.screenMessage
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
    /** Meetings whose response is being saved, with the answer given: shown as chosen at once, buttons disabled meanwhile. */
    val pendingResponses: Map<String, AttendeeStatus> = emptyMap(),
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

    private var watching: Job? = null

    /** Meetings whose save has returned and whose chosen answer is waiting for the refetch. */
    private val saved = mutableSetOf<String>()
    private var sawRefetch = false

    init {
        watch()
    }

    /**
     * Draws from the store for today's window: at once when held, then as refetches land. The store
     * refetches on live changes itself. While a wider window loads, the narrower one stays up.
     */
    private fun watch() {
        watching?.cancel()
        val today = clock()
        val level = _state.value.searchLevel
        if (today != _state.value.today) _state.update { it.copy(today = today, data = null) }
        watching = viewModelScope.launch {
            source.observe(today, level).collect { loaded ->
                _state.update { state ->
                    state.copy(
                        data = loaded.data ?: state.data,
                        loading = loaded.fetching || (loaded.data == null && loaded.error == null),
                        error = loaded.error?.takeUnless { it is SessionExpiredException }?.screenMessage(),
                    )
                }
                settleResponses(refetching = loaded.fetching)
            }
        }
    }

    /**
     * Once a save has returned, the chosen answer stays drawn until the refetch takes the meeting off
     * the needs-response list (or a refetch that began after the save has finished), so the buttons
     * never flick back while the store catches up.
     */
    private fun settleResponses(refetching: Boolean) {
        if (saved.isEmpty()) return
        if (refetching) sawRefetch = true
        val stillWaiting = _state.value.data?.needsResponse?.map { it.meetingId }?.toSet().orEmpty()
        val settled = saved.filter { it !in stillWaiting || (sawRefetch && !refetching) }
        if (settled.isEmpty()) return
        saved.removeAll(settled.toSet())
        _state.update { it.copy(pendingResponses = it.pendingResponses - settled.toSet()) }
    }

    /** On becoming visible and on Try again: a new day starts a new window; otherwise failures are retried. */
    fun refresh() {
        if (clock() != _state.value.today) watch() else source.retry()
    }

    /** Widens the needs-response window by another step. */
    fun searchFurtherAhead() {
        _state.update { it.copy(searchLevel = it.searchLevel + 1) }
        watch()
    }

    /** Records the caller's response; the write invalidates the store, which refetches the day. */
    fun respond(meetingId: String, status: AttendeeStatus) {
        if (meetingId in _state.value.pendingResponses) return
        _state.update { it.copy(pendingResponses = it.pendingResponses + (meetingId to status), respondError = null) }
        viewModelScope.launch {
            var succeeded = false
            try {
                when (val result = respondSource.respond(meetingId, status)) {
                    WriteResult.Done -> {
                        succeeded = true
                        saved += meetingId
                        sawRefetch = false
                        settleResponses(refetching = false)
                    }
                    is WriteResult.Rejected -> _state.update { it.copy(respondError = result.messages.joinToString("\n")) }
                }
            } catch (expired: SessionExpiredException) {
                // Signed out; navigation takes the user back to sign-in.
            } catch (failure: ApiException) {
                _state.update { it.copy(respondError = failure.message) }
            } finally {
                // A refusal or failure rolls the selection back; a saved answer waits for the refetch.
                if (!succeeded) _state.update { it.copy(pendingResponses = it.pendingResponses - meetingId) }
            }
        }
    }
}
