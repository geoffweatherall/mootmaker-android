package com.mootmaker.data.agenda

import com.mootmaker.data.meeting.AttendeeStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Mirrors the API's TimeFormat enum. Display only: the API always speaks ISO-8601. */
enum class TimeFormat { TwentyFourHour, AmPm }

/** Mirrors the API's DateFormat enum. Display only: the API always speaks ISO-8601. */
enum class DateFormat { Iso, British, Usa }

/** The eight categorical room colours, in the API's RoomColor order. */
enum class RoomColor { Blue, Orange, Aqua, Yellow, Magenta, Green, Violet, Red }

data class AgendaRow(
    val meetingId: String,
    val subject: String,
    /** Naive local date-time strings, exactly as the API sent them ("2026-10-07T09:00:00"). */
    val startTime: String,
    val endTime: String,
    val roomName: String,
    /** Index into the room palette: the room's own colour, or its position by name. */
    val roomColorSlot: Int,
    /** The viewer's own response, or null when they organise the meeting (who is implicitly going). */
    val myStatus: AttendeeStatus? = null,
)

data class AgendaDay(val date: LocalDate, val rows: List<AgendaRow>)

/** What the home screen shows when the caller has a linked Person (use cases D.22, D.23). */
data class Agenda(val today: AgendaDay, val tomorrow: AgendaDay) {
    val isEmpty: Boolean get() = today.rows.isEmpty() && tomorrow.rows.isEmpty()
}

// The API's shapes, reduced to what the agenda needs, so the logic is testable without Apollo.
data class RoomInput(val id: String, val name: String, val color: RoomColor?)
data class MeetingInput(
    val id: String,
    val subject: String,
    val startTime: String,
    val endTime: String,
    val roomId: String,
    val organiserId: String,
    val attendeeIds: List<String>,
    val organiserName: String = "",
    /** Response by attendee id; an attendee missing from here has not responded. */
    val attendeeStatuses: Map<String, AttendeeStatus> = emptyMap(),
)
data class DayInput(val date: String, val meetings: List<MeetingInput>)

/**
 * Today's and tomorrow's meetings that [personId] organises or attends, each sorted by start time.
 * The API returns every meeting on a day, so the filtering is done here, as the webapp does.
 * Fixed-width ISO strings sort correctly as strings, which is how the webapp sorts them too.
 */
fun buildAgenda(
    personId: String,
    today: LocalDate,
    days: List<DayInput>,
    rooms: List<RoomInput>,
): Agenda {
    return Agenda(
        today = agendaDay(personId, today, days, rooms),
        tomorrow = agendaDay(personId, today.plusDays(1), days, rooms),
    )
}

/** One day's meetings that [personId] organises or attends, sorted by start time. */
fun agendaDay(personId: String, date: LocalDate, days: List<DayInput>, rooms: List<RoomInput>): AgendaDay {
    val roomsById = rooms.associateBy { it.id }
    val slotByRoomId = roomColorSlots(rooms)
    val meetings = days.firstOrNull { it.date == date.toString() }?.meetings.orEmpty()
    val rows = meetings
        .filter { it.organiserId == personId || personId in it.attendeeIds }
        .sortedBy { it.startTime }
        .map {
            AgendaRow(
                meetingId = it.id,
                subject = it.subject,
                startTime = it.startTime,
                endTime = it.endTime,
                roomName = roomsById[it.roomId]?.name.orEmpty(),
                roomColorSlot = slotByRoomId[it.roomId] ?: 0,
                myStatus = if (it.organiserId == personId) null else it.attendeeStatuses[personId] ?: AttendeeStatus.NoResponse,
            )
        }
    return AgendaDay(date, rows)
}

/**
 * A room's palette slot: its explicit colour when it has one, otherwise its position in the
 * name-sorted room list (wrapping after eight), matching the webapp's roomColorFor.
 */
fun roomColorSlots(rooms: List<RoomInput>): Map<String, Int> =
    rooms.sortedBy { it.name }.mapIndexed { index, room ->
        room.id to (room.color?.ordinal ?: (index % RoomColor.entries.size))
    }.toMap()

/**
 * The time portion of a naive local date-time in the viewer's format: "14:30" or "02:30 PM".
 * Parsed as [LocalDateTime], never an Instant: these strings carry no zone and must not be shifted.
 * Anything unparsable is shown as it came, as the webapp does.
 */
fun formatTime(isoLocalDateTime: String, timeFormat: TimeFormat): String {
    val time = try {
        LocalDateTime.parse(isoLocalDateTime)
    } catch (_: DateTimeParseException) {
        return isoLocalDateTime
    }
    val minute = time.minute.toString().padStart(2, '0')
    return when (timeFormat) {
        TimeFormat.TwentyFourHour -> "${time.hour.toString().padStart(2, '0')}:$minute"
        TimeFormat.AmPm -> {
            val meridiem = if (time.hour < 12) "AM" else "PM"
            val hour12 = if (time.hour % 12 == 0) 12 else time.hour % 12
            "${hour12.toString().padStart(2, '0')}:$minute $meridiem"
        }
    }
}

/**
 * The date portion of a naive local date-time in the viewer's format, zero-padded in all three
 * ("2026-10-07", "07/10/2026", "10/07/2026"). Anything unparsable is shown as it came.
 */
fun formatDate(isoLocalDateTime: String, dateFormat: DateFormat): String {
    val date = try {
        LocalDateTime.parse(isoLocalDateTime).toLocalDate()
    } catch (_: DateTimeParseException) {
        return isoLocalDateTime
    }
    val year = date.year.toString().padStart(4, '0')
    val month = date.monthValue.toString().padStart(2, '0')
    val day = date.dayOfMonth.toString().padStart(2, '0')
    return when (dateFormat) {
        DateFormat.Iso -> "$year-$month-$day"
        DateFormat.British -> "$day/$month/$year"
        DateFormat.Usa -> "$month/$day/$year"
    }
}

/** A meeting the caller has been invited to and not yet answered (use case D.107). */
data class NeedsResponseItem(
    val meetingId: String,
    val subject: String,
    val startTime: String,
    val endTime: String,
    val roomName: String,
    val roomColorSlot: Int,
    val organiserName: String,
)

/**
 * Every meeting in [days] where [personId] is an attendee, never the organiser (who is implicitly
 * going), and still has no response, soonest first.
 */
fun needsResponse(personId: String, days: List<DayInput>, rooms: List<RoomInput>): List<NeedsResponseItem> {
    val roomsById = rooms.associateBy { it.id }
    val slotByRoomId = roomColorSlots(rooms)
    return days.flatMap { it.meetings }
        .filter { it.organiserId != personId && personId in it.attendeeIds }
        .filter { (it.attendeeStatuses[personId] ?: AttendeeStatus.NoResponse) == AttendeeStatus.NoResponse }
        .sortedBy { it.startTime }
        .map {
            NeedsResponseItem(
                meetingId = it.id,
                subject = it.subject,
                startTime = it.startTime,
                endTime = it.endTime,
                roomName = roomsById[it.roomId]?.name.orEmpty(),
                roomColorSlot = slotByRoomId[it.roomId] ?: 0,
                organiserName = it.organiserName,
            )
        }
}

/** Days the first window covers: today and the two after it, as the webapp's. */
const val INITIAL_WINDOW_DAYS = 3

/** Days each "Search further ahead" adds. */
const val SEARCH_STEP_DAYS = 3

/** The last day covered once [level] searches have been made (level 0 is the initial window). */
fun windowEnd(today: LocalDate, level: Int): LocalDate = today.plusDays((INITIAL_WINDOW_DAYS - 1 + level * SEARCH_STEP_DAYS).toLong())

/** "Wed 7 Oct – Fri 9 Oct": always names both ends, so "nothing waiting" always says for when. */
fun formatRangeLabel(today: LocalDate, end: LocalDate, locale: Locale = Locale.getDefault()): String {
    val format = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    return "${today.format(format)} – ${end.format(format)}"
}
