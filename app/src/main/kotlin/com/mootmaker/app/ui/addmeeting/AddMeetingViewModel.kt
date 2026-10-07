package com.mootmaker.app.ui.addmeeting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.api.MeetingFormSource
import com.mootmaker.data.auth.SessionExpiredException
import com.mootmaker.data.meeting.CreateResult
import com.mootmaker.data.meeting.MeetingDraft
import com.mootmaker.data.meeting.MeetingFormReference
import com.mootmaker.data.meeting.NO_ROOM_AVAILABLE_MESSAGE
import com.mootmaker.data.meeting.RoomOption
import com.mootmaker.data.meeting.SuggestionCache
import com.mootmaker.data.meeting.advanceSuggestion
import com.mootmaker.data.meeting.defaultMeetingTimes
import com.mootmaker.data.meeting.localDateTime
import com.mootmaker.data.meeting.needsFetch
import com.mootmaker.data.meeting.suggestionKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class AddMeetingState(
    /** Null until the form's people and rooms have loaded. */
    val reference: MeetingFormReference? = null,
    val loading: Boolean = true,
    /** Why the form could not load; shown with a retry. */
    val loadError: String? = null,
    val subject: String = "",
    val organiserId: String = "",
    /** Once the user has picked an organiser (or cleared it) the default never overrides them. */
    val organiserTouched: Boolean = false,
    val attendeeIds: List<String> = emptyList(),
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    val roomId: String = "",
    /** The banner: every rule the server rejected the booking for, or a failed request. */
    val errors: List<String> = emptyList(),
    val suggesting: Boolean = false,
    val saving: Boolean = false,
    /** Set once the meeting exists; the screen navigates to its details. */
    val savedMeetingId: String? = null,
) {
    val startTime: String get() = localDateTime(date, start)
    val endTime: String get() = localDateTime(date, end)
}

class AddMeetingViewModel(
    private val source: MeetingFormSource,
    initialDate: LocalDate,
    now: LocalDateTime = LocalDateTime.now(),
) : ViewModel() {
    private val times = defaultMeetingTimes(now.toLocalTime())
    private val _state = MutableStateFlow(AddMeetingState(date = initialDate, start = times.start, end = times.end))
    val state: StateFlow<AddMeetingState> = _state.asStateFlow()

    private var suggestionCache = SuggestionCache()
    private var loadJob: Job? = null

    /** Loads the people and rooms the fields choose from. Idempotent while one load runs. */
    fun load() {
        if (loadJob?.isActive == true || _state.value.reference != null) return
        _state.update { it.copy(loading = true, loadError = null) }
        loadJob = viewModelScope.launch {
            try {
                val reference = source.loadReference()
                _state.update { withDefaultOrganiser(it.copy(reference = reference, loading = false)) }
            } catch (expired: SessionExpiredException) {
                _state.update { it.copy(loading = false) }
            } catch (failure: ApiException) {
                _state.update { it.copy(loading = false, loadError = failure.message) }
            }
        }
    }

    fun setSubject(subject: String) = _state.update { it.copy(subject = subject) }

    fun setOrganiser(personId: String) = _state.update { it.copy(organiserId = personId, organiserTouched = true) }

    /** Takes the attendee set as chosen; removing yourself from it lets the organiser default apply again. */
    fun setAttendees(personIds: List<String>) = _state.update { withDefaultOrganiser(it.copy(attendeeIds = personIds)) }

    fun setDate(date: LocalDate) = _state.update { it.copy(date = date) }

    fun setStart(time: LocalTime) = _state.update { it.copy(start = time) }

    fun setEnd(time: LocalTime) = _state.update { it.copy(end = time) }

    fun setRoom(roomId: String) = _state.update { it.copy(roomId = roomId) }

    fun dismissErrors() = _state.update { it.copy(errors = emptyList()) }

    /**
     * Fills the room field with the best free room for the slot and headcount; further presses step
     * through the ranked list and wrap. The list is fetched once per slot and headcount.
     */
    fun suggestRoom() {
        val current = _state.value
        if (current.suggesting) return
        val key = suggestionKey(current.startTime, current.endTime, current.attendeeIds.size)
        _state.update { it.copy(errors = emptyList()) }
        if (!suggestionCache.needsFetch(key)) {
            applySuggestion(key, fetched = null)
            return
        }
        _state.update { it.copy(suggesting = true) }
        viewModelScope.launch {
            try {
                val rooms = source.suggestRooms(current.startTime, current.endTime, current.attendeeIds.size + 1)
                applySuggestion(key, rooms)
            } catch (expired: SessionExpiredException) {
                // Signed out; navigation takes the user back to sign-in.
            } catch (failure: ApiException) {
                _state.update { it.copy(errors = listOf(failure.message.orEmpty())) }
            } finally {
                _state.update { it.copy(suggesting = false) }
            }
        }
    }

    private fun applySuggestion(key: String, fetched: List<RoomOption>?) {
        val step = advanceSuggestion(suggestionCache, key, fetched)
        suggestionCache = step.cache
        val room = step.room
        _state.update { if (room == null) it.copy(errors = listOf(NO_ROOM_AVAILABLE_MESSAGE)) else it.copy(roomId = room.id) }
    }

    fun save() {
        val current = _state.value
        if (current.saving || current.reference == null) return
        _state.update { it.copy(saving = true, errors = emptyList()) }
        viewModelScope.launch {
            try {
                val draft = MeetingDraft(
                    subject = current.subject,
                    roomId = current.roomId,
                    organiserId = current.organiserId,
                    attendeeIds = current.attendeeIds,
                    startTime = current.startTime,
                    endTime = current.endTime,
                )
                when (val result = source.create(draft)) {
                    is CreateResult.Created -> _state.update { it.copy(saving = false, savedMeetingId = result.meetingId) }
                    is CreateResult.Rejected -> _state.update { it.copy(saving = false, errors = result.messages) }
                }
            } catch (expired: SessionExpiredException) {
                _state.update { it.copy(saving = false) }
            } catch (failure: ApiException) {
                _state.update { it.copy(saving = false, errors = listOf(failure.message.orEmpty())) }
            }
        }
    }

    /** The signed-in person is the organiser unless the user chose otherwise or is attending themselves. */
    private fun withDefaultOrganiser(state: AddMeetingState): AddMeetingState {
        val me = state.reference?.myPersonId
        return if (me != null && !state.organiserTouched && me !in state.attendeeIds) state.copy(organiserId = me) else state
    }
}
