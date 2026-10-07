package com.mootmaker.data.api

import com.apollographql.apollo.ApolloClient
import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.graphql.CreateMeetingMutation
import com.mootmaker.data.graphql.MeetingFormQuery
import com.mootmaker.data.graphql.SuggestRoomQuery
import com.mootmaker.data.graphql.type.DateFormat as ApiDateFormat
import com.mootmaker.data.graphql.type.MeetingInput
import com.mootmaker.data.graphql.type.TimeFormat as ApiTimeFormat
import com.mootmaker.data.meeting.CreateResult
import com.mootmaker.data.meeting.MeetingDraft
import com.mootmaker.data.meeting.MeetingFormReference
import com.mootmaker.data.meeting.PersonOption
import com.mootmaker.data.meeting.RoomOption
import com.mootmaker.data.meeting.meetingErrorMessage

interface MeetingFormSource {
    suspend fun loadReference(): MeetingFormReference

    /** Rooms free for the slot that hold [requiredCapacity] people, best fit first. */
    suspend fun suggestRooms(startTime: String, endTime: String, requiredCapacity: Int): List<RoomOption>

    suspend fun create(draft: MeetingDraft): CreateResult
}

/** Reads the form's reference data and books the meeting. Authoritative validation stays on the server. */
class MeetingFormRepository(
    private val apollo: suspend () -> ApolloClient,
    private val idToken: suspend () -> String,
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

    override suspend fun suggestRooms(startTime: String, endTime: String, requiredCapacity: Int): List<RoomOption> =
        apollo().call(SuggestRoomQuery(startTime, endTime, requiredCapacity), idToken(), "Something went wrong suggesting a room.")
            .suggestRoom.map { RoomOption(it.id, it.name, it.capacity) }

    override suspend fun create(draft: MeetingDraft): CreateResult {
        val input = MeetingInput(
            roomId = draft.roomId,
            organiserId = draft.organiserId,
            attendeeIds = draft.attendeeIds,
            subject = draft.subject,
            startTime = draft.startTime,
            endTime = draft.endTime,
        )
        val result = apollo().send(CreateMeetingMutation(input), idToken(), "Something went wrong saving the meeting.").createMeeting
        val meetingId = result.meeting?.id
        return when {
            result.errors.isNotEmpty() -> CreateResult.Rejected(result.errors.map { meetingErrorMessage(it.rawValue) })
            meetingId != null -> CreateResult.Created(meetingId)
            else -> throw ApiException("Something went wrong saving the meeting.")
        }
    }
}
