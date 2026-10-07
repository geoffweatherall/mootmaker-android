package com.mootmaker.data.api

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.mootmaker.data.agenda.AgendaDay
import com.mootmaker.data.agenda.DayInput
import com.mootmaker.data.agenda.MeetingInput
import com.mootmaker.data.agenda.RoomColor
import com.mootmaker.data.agenda.RoomInput
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.calendar.CalendarBounds
import com.mootmaker.data.calendar.buildWeek
import com.mootmaker.data.calendar.sortedPeople
import com.mootmaker.data.calendar.workWeekDates
import com.mootmaker.data.graphql.PersonCalendarQuery
import com.mootmaker.data.graphql.type.RoomColor as ApiRoomColor
import com.mootmaker.data.graphql.type.TimeFormat as ApiTimeFormat
import com.mootmaker.data.meeting.PersonRef
import java.time.LocalDate

data class CalendarData(
    val timeFormat: TimeFormat,
    /** Everyone, sorted by name, for the person selector. */
    val people: List<PersonRef>,
    /** Monday to Friday of the requested week, with the person's meetings. */
    val week: List<AgendaDay>,
    /** Null when the server didn't say, which leaves navigation unbounded. */
    val bounds: CalendarBounds?,
)

interface CalendarSource {
    suspend fun load(personId: String, monday: LocalDate): CalendarData
}

/** Loads one person's working week through the `workspace` entry point: one request, five days. */
class CalendarRepository(
    private val apollo: suspend () -> ApolloClient,
    private val idToken: suspend () -> String,
) : CalendarSource {
    override suspend fun load(personId: String, monday: LocalDate): CalendarData {
        val dates = workWeekDates(monday).map { it.toString() }
        val workspace = apollo().call(
            PersonCalendarQuery(Optional.present(dates)),
            idToken(),
            "Something went wrong loading the calendar.",
        ).workspace
        val rooms = workspace.rooms.map { RoomInput(it.id, it.name, it.color.toRoomColor()) }
        val days = workspace.days.map { day ->
            DayInput(
                date = day.date,
                meetings = day.meetings.map { meeting ->
                    MeetingInput(
                        id = meeting.id,
                        subject = meeting.subject,
                        startTime = meeting.startTime,
                        endTime = meeting.endTime,
                        roomId = meeting.room.id,
                        organiserId = meeting.organiser.id,
                        attendeeIds = meeting.attendees.map { it.person.id },
                    )
                },
            )
        }
        return CalendarData(
            timeFormat = if (workspace.me?.timeFormat == ApiTimeFormat.AmPm) TimeFormat.AmPm else TimeFormat.TwentyFourHour,
            people = sortedPeople(workspace.people.map { PersonRef(it.id, it.name) }),
            week = buildWeek(personId, monday, days, rooms),
            bounds = CalendarBounds(
                earliest = LocalDate.parse(workspace.boundaries.earliestRetainedDate),
                latest = LocalDate.parse(workspace.boundaries.latestBookableDate),
            ),
        )
    }

    private fun ApiRoomColor?.toRoomColor(): RoomColor? =
        this?.let { api -> RoomColor.entries.firstOrNull { it.name == api.rawValue } }
}
