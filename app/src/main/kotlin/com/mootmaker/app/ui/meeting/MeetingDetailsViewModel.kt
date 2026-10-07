package com.mootmaker.app.ui.meeting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.api.MeetingSource
import com.mootmaker.data.api.WriteResult
import com.mootmaker.data.auth.SessionExpiredException
import com.mootmaker.data.meeting.AttendeeStatus
import com.mootmaker.data.meeting.MeetingDetailsData
import com.mootmaker.data.meeting.canEditMeeting
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MeetingDetailsState(
    /** Null until the first load finishes. Kept on screen while a refresh runs. */
    val data: MeetingDetailsData? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** The caller's own response is being saved. */
    val responding: Boolean = false,
    /** The cancel confirmation is showing. */
    val confirmingCancel: Boolean = false,
    val cancelling: Boolean = false,
    /** Why the last response or cancel failed, shown on the confirmation or above the details. */
    val actionErrors: List<String> = emptyList(),
    /** Set once the meeting is deleted; the screen leaves. */
    val cancelled: Boolean = false,
)

class MeetingDetailsViewModel(
    private val source: MeetingSource,
    private val meetingId: String,
    private val isAdmin: Boolean = false,
) : ViewModel() {
    private val _state = MutableStateFlow(MeetingDetailsState())
    val state: StateFlow<MeetingDetailsState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var reloadWhenDone = false

    /** Refetches. Called whenever the screen becomes visible, and by [refreshForLiveChange]. */
    fun refresh() {
        if (loadJob?.isActive == true) return
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val data = source.load(meetingId)
                _state.update { it.copy(data = data, loading = false) }
            } catch (expired: SessionExpiredException) {
                // The session has signed out; navigation takes the user back to sign-in.
                _state.update { it.copy(loading = false) }
            } catch (failure: ApiException) {
                _state.update { it.copy(loading = false, error = failure.message) }
            }
        }.also { job -> job.invokeOnCompletion { if (reloadWhenDone) { reloadWhenDone = false; refresh() } } }
    }

    /**
     * A live broadcast says what is held may be stale. A load already in flight may have read the
     * server before that change, so it is followed by another rather than trusted (the webapp's
     * in-flight race: stale data landing after the eviction, with nothing left to refetch it).
     */
    fun refreshForLiveChange() {
        if (loadJob?.isActive == true) reloadWhenDone = true else refresh()
    }

    /** Whether Edit and Cancel are offered: the organiser or an admin (the API refuses anyone else regardless). */
    fun canEdit(): Boolean {
        val data = _state.value.data ?: return false
        val meeting = data.meeting ?: return false
        return canEditMeeting(meeting, data.myPersonId, isAdmin)
    }

    /** Records the caller's own response, then reloads so every row shows what the server holds. */
    fun respond(status: AttendeeStatus) {
        if (_state.value.responding) return
        _state.update { it.copy(responding = true, actionErrors = emptyList()) }
        viewModelScope.launch {
            try {
                when (val result = source.respond(meetingId, status)) {
                    WriteResult.Done -> Unit
                    is WriteResult.Rejected -> _state.update { it.copy(actionErrors = result.messages) }
                }
            } catch (expired: SessionExpiredException) {
                // Signed out; navigation takes the user back to sign-in.
            } catch (failure: ApiException) {
                _state.update { it.copy(actionErrors = listOf(failure.message.orEmpty())) }
            } finally {
                _state.update { it.copy(responding = false) }
            }
            loadJob?.join()
            refresh()
        }
    }

    fun askToCancel() = _state.update { it.copy(confirmingCancel = true, actionErrors = emptyList()) }

    fun keepMeeting() = _state.update { it.copy(confirmingCancel = false, actionErrors = emptyList()) }

    /** Deletes the meeting for everyone; the screen leaves once it is gone. */
    fun confirmCancel() {
        if (_state.value.cancelling) return
        _state.update { it.copy(cancelling = true, actionErrors = emptyList()) }
        viewModelScope.launch {
            try {
                when (val result = source.cancel(meetingId)) {
                    WriteResult.Done -> _state.update { it.copy(cancelling = false, confirmingCancel = false, cancelled = true) }
                    is WriteResult.Rejected -> _state.update { it.copy(cancelling = false, actionErrors = result.messages) }
                }
            } catch (expired: SessionExpiredException) {
                _state.update { it.copy(cancelling = false) }
            } catch (failure: ApiException) {
                _state.update { it.copy(cancelling = false, actionErrors = listOf(failure.message.orEmpty())) }
            }
        }
    }
}
