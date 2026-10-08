package com.mootmaker.data.api

import com.apollographql.apollo.ApolloClient
import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.cache.CachedMeeting
import com.mootmaker.data.cache.Loaded
import com.mootmaker.data.cache.Reference
import com.mootmaker.data.cache.WorkspaceStore
import com.mootmaker.data.cache.toDetailInput
import com.mootmaker.data.cache.toRoomInputs
import com.mootmaker.data.graphql.CancelMeetingMutation
import com.mootmaker.data.graphql.RespondToMeetingMutation
import com.mootmaker.data.graphql.type.AttendeeStatus as ApiAttendeeStatus
import com.mootmaker.data.meeting.AttendeeStatus
import com.mootmaker.data.meeting.MeetingDetailsData
import com.mootmaker.data.meeting.buildMeetingDetail
import com.mootmaker.data.meeting.meetingErrorMessage
import com.mootmaker.data.meeting.respondErrorMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** The outcome of a write that has nothing to return: done, or every rule it broke, worded for the screen. */
sealed interface WriteResult {
    data object Done : WriteResult
    data class Rejected(val messages: List<String>) : WriteResult
}

interface MeetingSource {
    /** The meeting and the caller's formats, as they change: from the store at once when held. */
    fun observe(meetingId: String): Flow<Loaded<MeetingDetailsData>>

    /** Fetches again whatever failed: the screen's Try again. */
    fun retry()

    /** Sets the caller's own response to the meeting. */
    suspend fun respond(meetingId: String, status: AttendeeStatus): WriteResult

    /** Deletes the meeting for everyone. */
    suspend fun cancel(meetingId: String): WriteResult
}

/**
 * One meeting over the [WorkspaceStore], which finds it in whichever loaded day holds it, or looks
 * it up by id for a link that carries no date. Writes go to the API, then invalidate what they
 * changed, so this device's own change never waits on the live channel.
 */
class MeetingRepository(
    private val apollo: suspend () -> ApolloClient,
    private val idToken: suspend () -> String,
    private val store: WorkspaceStore,
) : MeetingSource {
    override fun retry() = store.retry()

    override fun observe(meetingId: String): Flow<Loaded<MeetingDetailsData>> =
        combine(store.reference(), store.meeting(meetingId)) { reference, view ->
            val held = reference.value
            Loaded(
                data = if (reference.known && held != null && view.known) meetingDetailsData(held, view.meeting) else null,
                fetching = reference.fetching || view.fetching,
                error = reference.error ?: view.error,
            )
        }

    override suspend fun respond(meetingId: String, status: AttendeeStatus): WriteResult {
        val result = apollo()
            .send(RespondToMeetingMutation(meetingId, ApiAttendeeStatus.safeValueOf(status.name)), idToken(), "Something went wrong saving your response.")
            .respondToMeeting
        return when {
            result.errors.isNotEmpty() -> WriteResult.Rejected(result.errors.map { respondErrorMessage(it.rawValue) })
            result.meeting != null -> WriteResult.Done
            else -> throw ApiException("Something went wrong saving your response.")
        }.also { store.invalidateMeeting(meetingId) }
    }

    override suspend fun cancel(meetingId: String): WriteResult {
        val result = apollo().send(CancelMeetingMutation(meetingId), idToken(), "Something went wrong cancelling the meeting.").cancelMeeting
        if (result.errors.isNotEmpty()) return WriteResult.Rejected(result.errors.map { meetingErrorMessage(it.rawValue) })
        store.invalidateMeeting(meetingId)
        return WriteResult.Done
    }
}

/** What the details screen shows, from what the store holds. A null [meeting] reads as gone (H.73). */
fun meetingDetailsData(reference: Reference, meeting: CachedMeeting?): MeetingDetailsData = MeetingDetailsData(
    meeting = meeting?.let { buildMeetingDetail(it.toDetailInput(reference), reference.rooms.toRoomInputs()) },
    myPersonId = reference.me?.id,
    timeFormat = reference.me?.timeFormat ?: TimeFormat.TwentyFourHour,
    dateFormat = reference.me?.dateFormat ?: DateFormat.Iso,
)
