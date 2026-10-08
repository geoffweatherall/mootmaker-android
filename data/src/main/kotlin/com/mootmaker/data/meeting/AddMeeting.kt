package com.mootmaker.data.meeting

import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.TimeFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// The Add Meeting form's logic, ported from the webapp's addMeetingLogic.ts and kept free of Android
// and Apollo so it is unit-tested directly.

data class PersonOption(val id: String, val name: String)

data class RoomOption(val id: String, val name: String, val capacity: Int) {
    /** How the room reads in the form's room field and menu. */
    val label: String get() = "$name (capacity $capacity)"
}

/** What the form's fields choose from, and how the caller likes dates and times shown. */
data class MeetingFormReference(
    /** The signed-in caller's Person, or null for an account with none. */
    val myPersonId: String?,
    /** Sorted by name, as the webapp lists them. */
    val people: List<PersonOption>,
    /** Sorted by name. */
    val rooms: List<RoomOption>,
    val timeFormat: TimeFormat,
    val dateFormat: DateFormat,
)

/** What the API is asked to book. Times are naive local date-times (see [localDateTime]). */
data class MeetingDraft(
    val subject: String,
    val roomId: String,
    val organiserId: String,
    val attendeeIds: List<String>,
    val startTime: String,
    val endTime: String,
    /** Editing only: the version the form was loaded at. */
    val expectedVersion: String? = null,
)

/** A meeting the edit form opens on, as the API has it. */
data class ExistingMeeting(
    val id: String,
    val subject: String,
    val startTime: String,
    val endTime: String,
    val roomId: String,
    val organiserId: String,
    val attendeeIds: List<String>,
    val version: String,
)

/** The outcome of a booking: the new meeting, or every validation rule it broke, worded for the screen. */
sealed interface CreateResult {
    data class Created(val meetingId: String) : CreateResult
    data class Rejected(val messages: List<String>) : CreateResult
}

const val NO_ROOM_AVAILABLE_MESSAGE = "No suitable room is available for that time - try adjusting the attendees or time."

// --- Organiser and attendee exclusivity ---------------------------------------------------------

/** The same person can't be both, so each picker leaves out whoever is chosen in the other. */
fun organiserOptions(people: List<PersonOption>, attendeeIds: List<String>): List<PersonOption> =
    people.filter { it.id !in attendeeIds }

fun attendeeOptions(people: List<PersonOption>, organiserId: String): List<PersonOption> =
    people.filter { it.id != organiserId }

// --- Suggested-room cache -----------------------------------------------------------------------

/**
 * The ranked rooms last fetched for [key] (the slot and headcount they were fetched for) and which one
 * the field shows. [candidates] null means not fetched yet; empty means fetched and nothing qualified.
 * Staleness is judged by comparing [key] on every press rather than by a second piece of state that
 * could lag behind (the race the webapp's version once had).
 */
data class SuggestionCache(val candidates: List<RoomOption>? = null, val index: Int = 0, val key: String = "")

data class SuggestionStep(val cache: SuggestionCache, val room: RoomOption?)

fun suggestionKey(startTime: String, endTime: String, attendeeCount: Int) = "$startTime|$endTime|$attendeeCount"

/** True when the cache can't answer for [key] and the ranked list has to be fetched. */
fun SuggestionCache.needsFetch(key: String) = this.key != key || candidates == null

/**
 * Advances the cache one press for [key]. Pass [fetched] only when [needsFetch]; it is ignored when
 * the cache already holds a list for this key, which is then stepped through locally, wrapping.
 */
fun advanceSuggestion(cache: SuggestionCache, key: String, fetched: List<RoomOption>? = null): SuggestionStep {
    var candidates = if (cache.key == key) cache.candidates else null
    var index = if (cache.key == key) cache.index else 0
    if (candidates == null) {
        candidates = fetched.orEmpty()
        index = 0
    } else if (candidates.isNotEmpty()) {
        index = (index + 1) % candidates.size
    }
    return SuggestionStep(SuggestionCache(candidates, index, key), candidates.getOrNull(index))
}

// --- Times --------------------------------------------------------------------------------------

private const val QUARTER = 15
private const val LAST_QUARTER_OF_DAY = 24 * 60 - QUARTER

data class DefaultMeetingTimes(val start: LocalTime, val end: LocalTime)

/**
 * The times the form starts with: the next 15-minute boundary, for an hour. The API wants both on a
 * 15-minute boundary and refuses a meeting that spans midnight, so near the end of the day the pair is
 * clamped back to the last slots that fit (23:30 to 23:45 at the extreme) rather than rolling to
 * tomorrow, which the separate date field would not show. Both come from one instant so the clock
 * cannot tick between them.
 */
fun defaultMeetingTimes(now: LocalTime): DefaultMeetingTimes {
    val minutes = now.hour * 60 + now.minute
    val aligned = if (minutes % QUARTER == 0 && now.second == 0 && now.nano == 0) minutes else (minutes / QUARTER + 1) * QUARTER
    val end = minOf(aligned + 60, LAST_QUARTER_OF_DAY)
    val start = minOf(aligned, end - QUARTER)
    return DefaultMeetingTimes(minutesToTime(start), minutesToTime(end))
}

/** Every start or end the form can book: all 96 quarter hours of a day (the dial rounds to one, F.41). */
val quarterHours: List<LocalTime> = (0 until 24 * 60 step QUARTER).map(::minutesToTime)

private fun minutesToTime(minutes: Int) = LocalTime.of(minutes / 60, minutes % 60)

// --- The time dial ------------------------------------------------------------------------------
// Material3's clock dial offers every minute (a drag) or every fifth one (a tap), and has no setting
// for a 15-minute step. The form corrects whatever the dial reads to the nearest quarter, so the time
// it books is always one the API accepts (issue #28, option 2).

/**
 * The quarter hour nearest [minute] (0 to 59), as a minute: 0, 15, 30 or 45. Halfway rounds up, and
 * 53 to 59 round up to 0, the top of the same hour: the dial changes only the minute, never the hour,
 * so a correction can't move the meeting to another hour or across midnight.
 */
fun nearestQuarterMinute(minute: Int): Int {
    require(minute in 0..59) { "minute $minute is not 0 to 59" }
    return (minute * 2 + QUARTER) / (QUARTER * 2) * QUARTER % 60
}

/** The time a dial reading of [hour] (0 to 23) and [minute] books: the same hour, the nearest quarter. */
fun dialTime(hour: Int, minute: Int): LocalTime = LocalTime.of(hour, nearestQuarterMinute(minute))

/** Whether the dial shows 24 hours (two rings) or 12 with AM and PM, as the form shows times. */
fun dialIs24Hour(timeFormat: TimeFormat): Boolean = timeFormat == TimeFormat.TwentyFourHour

/** [hour] (0 to 23) as a 12-hour clock face reads it: 12, then 1 to 11, morning and afternoon alike. */
fun twelveHourClockHour(hour: Int): Int {
    require(hour in 0..23) { "hour $hour is not 0 to 23" }
    return if (hour % 12 == 0) 12 else hour % 12
}

/** The hour of the day (0 to 23) that a 12-hour [clockHour] (1 to 12) means in the morning or [afternoon]. */
fun hourOfDay(clockHour: Int, afternoon: Boolean): Int {
    require(clockHour in 1..12) { "clock hour $clockHour is not 1 to 12" }
    return clockHour % 12 + if (afternoon) 12 else 0
}

private val localDateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/**
 * The API's naive local date-time: "2026-07-01T14:30:00", always with seconds (LocalDateTime.toString
 * drops them at :00, which the API's contract does not promise to accept).
 */
fun localDateTime(date: LocalDate, time: LocalTime): String = LocalDateTime.of(date, time).format(localDateTimeFormat)

// --- Error wording ------------------------------------------------------------------------------

/** User-facing text for the API's MeetingError codes (the webapp's MEETING_ERROR_MESSAGES). */
fun meetingErrorMessage(code: String): String = when (code) {
    "StartMisaligned" -> "Start time must fall on a 15 minute boundary."
    "EndMisaligned" -> "End time must fall on a 15 minute boundary."
    "SpansMultipleDays" -> "A meeting cannot span midnight - start and end time must be on the same day."
    "EndBeforeStart" -> "End time must be after the start time."
    "InsufficientCapacity" -> "The room does not have enough capacity for all attendees."
    "TimeRangeUnavailable" -> "The room already has a meeting scheduled during that time range."
    "RoomRequired" -> "Please select a room."
    "RoomNotFound" -> "The selected room could not be found."
    "OrganiserRequired" -> "Please select an organiser."
    "OrganiserNotFound" -> "The selected organiser could not be found."
    "AttendeeNotFound" -> "One or more selected attendees could not be found."
    "SubjectRequired" -> "Please enter a subject."
    "OrganiserIsAttendee" -> "The organiser cannot also be listed as an attendee."
    // Says the DAY, never the room: the day limit can refuse a booking while a room stands free.
    "DayIsFull" -> "This day is fully booked - no more meetings can be added to it, even if a room looks free."
    "TooManyAttendees" -> "That is too many attendees for one meeting."
    // Not "characters": the limit is in bytes, so emoji and accents genuinely use more of it.
    "SubjectTooLong" -> "The subject is too long. Emoji and accented characters take up more of the limit than plain letters."
    "OutsideBookableRange" -> "That date is outside the range meetings can be booked in."
    "TooManyMeetingsInOneCall" -> "Too many meetings were sent in a single request."
    "MeetingNotFound" -> "This meeting no longer exists - it may have been deleted."
    "MeetingChanged" ->
        "Someone else changed this meeting after you opened it, so your changes were not saved. Go back and open it again to see their changes, then make yours again."
    // A rule from a newer API than this build knows: still say something rather than nothing.
    else -> "The meeting could not be saved ($code)."
}
