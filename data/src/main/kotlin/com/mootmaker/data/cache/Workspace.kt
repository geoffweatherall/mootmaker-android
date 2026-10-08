package com.mootmaker.data.cache

import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.RoomColor
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.calendar.CalendarBounds
import com.mootmaker.data.meeting.AttendeeStatus
import java.time.LocalDate

/*
 * What the store holds: the API's data, reduced to plain Kotlin, in the shape every read screen can
 * build from. People and rooms are referred to by id and resolved from [Reference], so a rename
 * reaches every meeting at once (use case M.98).
 */

/** One meeting as a day holds it: every field any of the four read screens shows. */
data class CachedMeeting(
    val id: String,
    val subject: String,
    /** Naive local date-times, exactly as the API sent them ("2026-10-07T09:00:00"). Never an Instant. */
    val startTime: String,
    val endTime: String,
    val roomId: String,
    val organiserId: String,
    /** Never includes the organiser: the API leaves them out (they are implicitly going). */
    val attendees: List<CachedAttendee>,
    /** Opaque; sent back as `expectedVersion` when editing so a stale edit is refused. */
    val version: String = "",
) {
    /** The date the meeting starts on, which is the day that holds it. */
    val date: LocalDate get() = LocalDate.parse(startTime.substring(0, DATE_LENGTH))
}

data class CachedAttendee(val personId: String, val status: AttendeeStatus)

/** Every meeting starting on [date]. An empty list means the day is known to be empty. */
data class CachedDay(val date: LocalDate, val meetings: List<CachedMeeting>)

data class CachedPerson(val id: String, val name: String, val avatarUrl: String? = null)

data class CachedRoom(val id: String, val name: String, val capacity: Int, val color: RoomColor?)

/** The signed-in caller's own Person. */
data class Me(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val timeFormat: TimeFormat,
    val dateFormat: DateFormat,
)

/** What every screen shares and nothing broadcasts: who you are, everyone, every room, the window. */
data class Reference(
    /** Null for an account with no linked Person. */
    val me: Me?,
    val people: List<CachedPerson>,
    val rooms: List<CachedRoom>,
    /** Null when the server didn't say, which leaves navigation unbounded. */
    val bounds: CalendarBounds?,
)

/** The three reads the store makes. The real one talks to the API; tests pass a fake. */
interface WorkspaceApi {
    suspend fun reference(): Reference

    /** At most [MAX_DATES_PER_REQUEST] dates. Returns a day for every date asked for. */
    suspend fun days(dates: List<LocalDate>): List<CachedDay>

    /** Null when no such meeting exists, or its day has aged out of retention (use case H.73). */
    suspend fun meeting(id: String): CachedMeeting?
}

/** The API's own limit on `workspace(dates:)`. */
const val MAX_DATES_PER_REQUEST = 42

private const val DATE_LENGTH = 10
