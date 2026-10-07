package com.mootmaker.data.agenda

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/** Mirrors the API's TimeFormat enum. Display only: the API always speaks ISO-8601. */
enum class TimeFormat { TwentyFourHour, AmPm }

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
    val roomsById = rooms.associateBy { it.id }
    val slotByRoomId = roomColorSlots(rooms)
    fun dayFor(date: LocalDate): AgendaDay {
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
                )
            }
        return AgendaDay(date, rows)
    }
    return Agenda(today = dayFor(today), tomorrow = dayFor(today.plusDays(1)))
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
