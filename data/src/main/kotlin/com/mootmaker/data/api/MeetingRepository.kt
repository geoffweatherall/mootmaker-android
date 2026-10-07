package com.mootmaker.data.api

import com.apollographql.apollo.ApolloClient
import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.RoomColor
import com.mootmaker.data.agenda.RoomInput
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.graphql.MeetingDetailsQuery
import com.mootmaker.data.graphql.type.AttendeeStatus as ApiAttendeeStatus
import com.mootmaker.data.graphql.type.DateFormat as ApiDateFormat
import com.mootmaker.data.graphql.type.RoomColor as ApiRoomColor
import com.mootmaker.data.graphql.type.TimeFormat as ApiTimeFormat
import com.mootmaker.data.meeting.AttendeeRow
import com.mootmaker.data.meeting.AttendeeStatus
import com.mootmaker.data.meeting.MeetingDetailsData
import com.mootmaker.data.meeting.MeetingInput
import com.mootmaker.data.meeting.MeetingRoomInput
import com.mootmaker.data.meeting.PersonRef
import com.mootmaker.data.meeting.buildMeetingDetail

interface MeetingSource {
    suspend fun load(meetingId: String): MeetingDetailsData
}

/** Looks one meeting up by id: the lookup the API keeps for links that carry no date. */
class MeetingRepository(
    private val apollo: suspend () -> ApolloClient,
    private val idToken: suspend () -> String,
) : MeetingSource {
    override suspend fun load(meetingId: String): MeetingDetailsData {
        val data = apollo().call(MeetingDetailsQuery(meetingId), idToken(), "Something went wrong loading the meeting.")
        val me = data.workspace.me
        return MeetingDetailsData(
            meeting = data.meeting?.let { meeting ->
                buildMeetingDetail(
                    MeetingInput(
                        id = meeting.id,
                        subject = meeting.subject,
                        startTime = meeting.startTime,
                        endTime = meeting.endTime,
                        room = MeetingRoomInput(meeting.room.id, meeting.room.name),
                        organiser = PersonRef(meeting.organiser.id, meeting.organiser.name),
                        attendees = meeting.attendees.map { AttendeeRow(PersonRef(it.person.id, it.person.name), it.status.toStatus()) },
                    ),
                    rooms = data.workspace.rooms.map { RoomInput(it.id, it.name, it.color.toRoomColor()) },
                )
            },
            myPersonId = me?.id,
            timeFormat = if (me?.timeFormat == ApiTimeFormat.AmPm) TimeFormat.AmPm else TimeFormat.TwentyFourHour,
            dateFormat = when (me?.dateFormat) {
                ApiDateFormat.Usa -> DateFormat.Usa
                ApiDateFormat.British -> DateFormat.British
                else -> DateFormat.Iso
            },
        )
    }

    // A status from a newer API reads as "No response", the only state that asks nothing of anyone.
    private fun ApiAttendeeStatus.toStatus(): AttendeeStatus =
        AttendeeStatus.entries.firstOrNull { it.name == rawValue } ?: AttendeeStatus.NoResponse

    private fun ApiRoomColor?.toRoomColor(): RoomColor? =
        this?.let { api -> RoomColor.entries.firstOrNull { it.name == api.rawValue } }
}
