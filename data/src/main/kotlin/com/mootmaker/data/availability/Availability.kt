package com.mootmaker.data.availability

import com.mootmaker.data.agenda.RoomColor
import com.mootmaker.data.agenda.RoomInput
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.agenda.formatTime
import com.mootmaker.data.agenda.roomColorSlots
import java.time.LocalDate
import java.time.LocalDateTime

// The API's shapes, reduced to what the screen needs, so the logic is testable without Apollo.
data class AvailabilityRoom(val id: String, val name: String, val capacity: Int, val color: RoomColor?)
data class AvailabilityMeeting(val id: String, val subject: String, val startTime: String, val endTime: String, val roomId: String)

/** A meeting as a room card lists it. Times are naive local date-time strings, as the API sent them. */
data class Booking(val id: String, val subject: String, val startTime: String, val endTime: String)

data class RoomCard(
    val id: String,
    val name: String,
    val capacity: Int,
    /** Index into the room palette: the room's own colour, or its position by name. */
    val colorSlot: Int,
    /** Sorted by start time. */
    val bookings: List<Booking>,
)

/**
 * One card per room, sorted by name, each with only its own day's bookings in start-time order.
 * A meeting is listed under its own room and nowhere else (use case E.33); back-to-back meetings
 * stay distinct rows (E.34). Fixed-width ISO strings sort correctly as strings.
 */
fun buildRoomCards(rooms: List<AvailabilityRoom>, meetings: List<AvailabilityMeeting>): List<RoomCard> {
    val slots = roomColorSlots(rooms.map { RoomInput(it.id, it.name, it.color) })
    val byRoom = meetings.groupBy { it.roomId }
    return rooms.sortedBy { it.name }.map { room ->
        RoomCard(
            id = room.id,
            name = room.name,
            capacity = room.capacity,
            colorSlot = slots[room.id] ?: 0,
            bookings = byRoom[room.id].orEmpty().sortedBy { it.startTime }
                .map { Booking(it.id, it.subject, it.startTime, it.endTime) },
        )
    }
}

fun minutesSinceMidnight(isoLocalDateTime: String): Int {
    val time = LocalDateTime.parse(isoLocalDateTime)
    return time.hour * 60 + time.minute
}

/** A short meeting still needs to read as a visible mark, not vanish at its true proportional width. */
private const val MIN_SEGMENT_WIDTH_PERCENT = 1.5f

// The timeline bar shows 08:00-18:00; a meeting straddling an edge is clipped, one wholly outside
// is dropped (mootmaker-webapp#114).
private const val WINDOW_START_MINUTES = 8 * 60
private const val WINDOW_END_MINUTES = 18 * 60
private const val WINDOW_MINUTES = WINDOW_END_MINUTES - WINDOW_START_MINUTES

/** Percent from the left edge of the visible window, and percent width. */
data class TimelineSegment(val left: Float, val width: Float)

/**
 * One entry per booking, positioned as a percentage of the visible window. A booking entirely
 * outside it has a null entry, so the result stays index-aligned with [bookings].
 */
fun segmentsForRoom(bookings: List<Booking>): List<TimelineSegment?> = bookings.map { booking ->
    val start = minutesSinceMidnight(booking.startTime)
    val end = minutesSinceMidnight(booking.endTime)
    if (end <= WINDOW_START_MINUTES || start >= WINDOW_END_MINUTES) {
        null
    } else {
        val clippedStart = maxOf(start, WINDOW_START_MINUTES)
        val clippedEnd = minOf(end, WINDOW_END_MINUTES)
        TimelineSegment(
            left = (clippedStart - WINDOW_START_MINUTES) * 100f / WINDOW_MINUTES,
            width = maxOf((clippedEnd - clippedStart) * 100f / WINDOW_MINUTES, MIN_SEGMENT_WIDTH_PERCENT),
        )
    }
}

data class RoomStatus(val label: String, val free: Boolean, val subLabel: String)

/**
 * A room card's headline status and caption. Only today has a "now" to be busy or free relative
 * to; a future or past day gets a plain summary. [bookings] must already be sorted by start time.
 */
fun statusForRoom(bookings: List<Booking>, isToday: Boolean, nowMinutes: Int, timeFormat: TimeFormat): RoomStatus {
    if (isToday) {
        val busy = bookings.firstOrNull {
            minutesSinceMidnight(it.startTime) <= nowMinutes && nowMinutes < minutesSinceMidnight(it.endTime)
        }
        if (busy != null) {
            return RoomStatus("Busy until ${formatTime(busy.endTime, timeFormat)}", free = false, subLabel = busy.subject)
        }
        val next = bookings.firstOrNull { minutesSinceMidnight(it.startTime) > nowMinutes }
        return RoomStatus(
            "Free now",
            free = true,
            subLabel = next?.let { "Next: ${it.subject} at ${formatTime(it.startTime, timeFormat)}" } ?: "No more meetings today",
        )
    }
    if (bookings.isEmpty()) return RoomStatus("Free all day", free = true, subLabel = "No meetings booked yet.")
    val first = bookings.first()
    return RoomStatus(
        label = "${bookings.size} ${if (bookings.size == 1) "meeting" else "meetings"}",
        free = false,
        subLabel = "First: ${first.subject} at ${formatTime(first.startTime, timeFormat)}",
    )
}

/** "today" or "tomorrow" for the two near days, otherwise null (callers fall back to the weekday). */
fun dayRelativeLabel(date: LocalDate, today: LocalDate): String? = when (date) {
    today -> "today"
    today.plusDays(1) -> "tomorrow"
    else -> null
}
