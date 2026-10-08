package com.mootmaker.data.cache

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.RoomColor
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.api.call
import com.mootmaker.data.calendar.CalendarBounds
import com.mootmaker.data.graphql.DaysQuery
import com.mootmaker.data.graphql.MeetingByIdQuery
import com.mootmaker.data.graphql.ReferenceQuery
import com.mootmaker.data.graphql.type.AttendeeStatus as ApiAttendeeStatus
import com.mootmaker.data.graphql.type.DateFormat as ApiDateFormat
import com.mootmaker.data.graphql.type.RoomColor as ApiRoomColor
import com.mootmaker.data.graphql.type.TimeFormat as ApiTimeFormat
import com.mootmaker.data.meeting.AttendeeStatus
import java.time.LocalDate

/**
 * The store's three reads, against the API's `workspace` and `meeting` entry points. Times stay the
 * API's naive local date-time strings; nothing here converts them.
 */
class ApolloWorkspaceApi(
    private val apollo: suspend () -> ApolloClient,
    private val idToken: suspend () -> String,
) : WorkspaceApi {
    override suspend fun reference(): Reference {
        val workspace = apollo().call(ReferenceQuery(), idToken(), "Something went wrong loading your workspace.").workspace
        val me = workspace.me
        return Reference(
            me = me?.let {
                Me(
                    id = it.id,
                    name = it.name,
                    avatarUrl = it.avatarUrl,
                    timeFormat = if (it.timeFormat == ApiTimeFormat.AmPm) TimeFormat.AmPm else TimeFormat.TwentyFourHour,
                    dateFormat = when (it.dateFormat) {
                        ApiDateFormat.Usa -> DateFormat.Usa
                        ApiDateFormat.British -> DateFormat.British
                        else -> DateFormat.Iso
                    },
                )
            },
            people = workspace.people.map { CachedPerson(it.id, it.name, it.avatarUrl) },
            rooms = workspace.rooms.map { CachedRoom(it.id, it.name, it.capacity, it.color.toRoomColor()) },
            // The schema makes boundaries non-null, so there is always a bounds.
            bounds = CalendarBounds(
                earliest = LocalDate.parse(workspace.boundaries.earliestRetainedDate),
                latest = LocalDate.parse(workspace.boundaries.latestBookableDate),
            ),
        )
    }

    override suspend fun days(dates: List<LocalDate>): List<CachedDay> {
        val workspace = apollo().call(
            DaysQuery(Optional.present(dates.map { it.toString() })),
            idToken(),
            "Something went wrong loading your meetings.",
        ).workspace
        return workspace.days.map { day ->
            CachedDay(
                date = LocalDate.parse(day.date),
                meetings = day.meetings.map { m ->
                    CachedMeeting(
                        id = m.id,
                        subject = m.subject,
                        startTime = m.startTime,
                        endTime = m.endTime,
                        roomId = m.room.id,
                        organiserId = m.organiser.id,
                        attendees = m.attendees.map { CachedAttendee(it.person.id, it.status.toStatus()) },
                        version = m.version,
                    )
                },
            )
        }
    }

    override suspend fun meeting(id: String): CachedMeeting? {
        val m = apollo().call(MeetingByIdQuery(id), idToken(), "Something went wrong loading the meeting.").meeting
            ?: return null
        return CachedMeeting(
            id = m.id,
            subject = m.subject,
            startTime = m.startTime,
            endTime = m.endTime,
            roomId = m.room.id,
            organiserId = m.organiser.id,
            attendees = m.attendees.map { CachedAttendee(it.person.id, it.status.toStatus()) },
            version = m.version,
        )
    }

    // A status from a newer API reads as "No response", the only state that asks nothing of anyone.
    private fun ApiAttendeeStatus.toStatus(): AttendeeStatus =
        AttendeeStatus.entries.firstOrNull { it.name == rawValue } ?: AttendeeStatus.NoResponse

    // An unknown colour from a newer API (UNKNOWN__) falls back to the by-name slot.
    private fun ApiRoomColor?.toRoomColor(): RoomColor? =
        this?.let { api -> RoomColor.entries.firstOrNull { it.name == api.rawValue } }
}
