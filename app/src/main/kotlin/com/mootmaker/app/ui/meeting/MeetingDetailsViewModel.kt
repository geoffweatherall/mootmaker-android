package com.mootmaker.app.ui.meeting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.api.MeetingSource
import com.mootmaker.data.auth.SessionExpiredException
import com.mootmaker.data.meeting.MeetingDetailsData
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
)

class MeetingDetailsViewModel(
    private val source: MeetingSource,
    private val meetingId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(MeetingDetailsState())
    val state: StateFlow<MeetingDetailsState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** Refetches. Called whenever the screen becomes visible (design: refetch on visible until M6). */
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
        }
    }
}
