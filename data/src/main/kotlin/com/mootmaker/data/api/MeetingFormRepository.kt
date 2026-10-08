package com.mootmaker.data.api

import com.mootmaker.data.cache.WorkspaceStore
import java.time.LocalDate
import java.time.LocalDateTime

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.graphql.CreateMeetingMutation
import com.mootmaker.data.graphql.EditMeetingQuery
import com.mootmaker.data.graphql.MeetingFormQuery
import com.mootmaker.data.graphql.SuggestRoomQuery
import com.mootmaker.data.graphql.UpdateMeetingMutation
import com.mootmaker.data.graphql.type.DateFormat as ApiDateFormat
import com.mootmaker.data.graphql.type.MeetingInput
import com.mootmaker.data.graphql.type.TimeFormat as ApiTimeFormat
import com.mootmaker.data.meeting.CreateResult
import com.mootmaker.data.meeting.ExistingMeeting
import com.mootmaker.data.meeting.MeetingDraft
import com.mootmaker.data.meeting.MeetingFormReference
import com.mootmaker.data.meeting.PersonOption
import com.mootmaker.data.meeting.RoomOption
import com.mootmaker.data.meeting.meetingErrorMessage

interface MeetingFormSource {
    suspend fun loadReference(): MeetingFormReference

    /** Rooms free for the slot that hold [requiredCapacity] people, best fit first. */
    suspend fun suggestRooms(startTime: String, endTime: String, requiredCapacity: Int, excludingMeetingId: String? = null): List<RoomOption>

    suspend fun create(draft: MeetingDraft): CreateResult

    /** The meeting to edit, or null when it no longer exists. */
    suspend fun loadMeeting(meetingId: String): ExistingMeeting?

    /** Saves an edit. The draft's `expectedVersion` makes a stale edit come back as `MeetingChanged`. */
    suspend fun update(meetingId: String, draft: MeetingDraft): CreateResult
}

/** Reads the form's reference data and books the meeting. Authoritative validation stays on the server. */
class MeetingFormRepository(
    private val apollo: suspend () -> ApolloClient,
    private val idToken: suspend () -> String,
    /** Told what each saved meeting changed, so this device's own change never waits on the live channel. */
    private val store: WorkspaceStore? = null,
) : MeetingFormSource {
    override suspend fun loadReference(): MeetingFormReference {
        val workspace = apollo().call(MeetingFormQuery(), idToken(), "Something went wrong loading the form.").workspace
        val me = workspace.me
        return MeetingFormReference(
            myPersonId = me?.id,
            people = workspace.people.map { PersonOption(it.id, it.name) }.sortedBy { it.name.lowercase() },
            rooms = workspace.rooms.map { RoomOption(it.id, it.name, it.capacity) }.sortedBy { it.name.lowercase() },
            timeFormat = if (me?.timeFormat == ApiTimeFormat.AmPm) TimeFormat.AmPm else TimeFormat.TwentyFourHour,
            dateFormat = when (me?.dateFormat) {
                ApiDateFormat.Usa -> DateFormat.Usa
                ApiDateFormat.British -> DateFormat.British
                else -> DateFormat.Iso
            },
        )
    }

    override suspend fun suggestRooms(startTime: String, endTime: String, requiredCapacity: Int, excludingMeetingId: String?): List<RoomOption> =
        apollo().call(
            SuggestRoomQuery(startTime, endTime, requiredCapacity, Optional.presentIfNotNull(excludingMeetingId)),
            idToken(),
            "Something went wrong suggesting a room.",
        ).suggestRoom.map { RoomOption(it.id, it.name, it.capacity) }

    override suspend fun create(draft: MeetingDraft): CreateResult {
        val result = apollo().send(CreateMeetingMutation(draft.toInput()), idToken(), "Something went wrong saving the meeting.").createMeeting
        val meetingId = result.meeting?.id
        return when {
            result.errors.isNotEmpty() -> CreateResult.Rejected(result.errors.map { meetingErrorMessage(it.rawValue) })
            meetingId != null -> CreateResult.Created(meetingId).also { store?.invalidateDays(listOf(draft.date())) }
            else -> throw ApiException("Something went wrong saving the meeting.")
        }
    }

    override suspend fun loadMeeting(meetingId: String): ExistingMeeting? =
        apollo().call(EditMeetingQuery(meetingId), idToken(), "Something went wrong loading the meeting.").meeting?.let {
            ExistingMeeting(
                id = it.id,
                subject = it.subject,
                startTime = it.startTime,
                endTime = it.endTime,
                roomId = it.room.id,
                organiserId = it.organiser.id,
                attendeeIds = it.attendees.map { attendee -> attendee.person.id },
                version = it.version,
            )
        }

    override suspend fun update(meetingId: String, draft: MeetingDraft): CreateResult {
        val result = apollo().send(UpdateMeetingMutation(meetingId, draft.toInput()), idToken(), "Something went wrong saving the meeting.").updateMeeting
        val updatedId = result.meeting?.id
        return when {
            result.errors.isNotEmpty() -> CreateResult.Rejected(result.errors.map { meetingErrorMessage(it.rawValue) })
            updatedId != null -> CreateResult.Created(updatedId).also {
                // Its old day (found by id in what the store holds) and its new one, which differ for a move.
                store?.invalidateMeeting(meetingId)
                store?.invalidateDays(listOf(draft.date()))
            }
            else -> throw ApiException("Something went wrong saving the meeting.")
        }
    }

    /** The day a draft's meeting starts on. Its times are naive local date-times: never through an Instant. */
    private fun MeetingDraft.date(): LocalDate = LocalDateTime.parse(startTime).toLocalDate()

    private fun MeetingDraft.toInput() = MeetingInput(
        roomId = roomId,
        organiserId = organiserId,
        attendeeIds = attendeeIds,
        subject = subject,
        startTime = startTime,
        endTime = endTime,
        expectedVersion = Optional.presentIfNotNull(expectedVersion),
    )
}
