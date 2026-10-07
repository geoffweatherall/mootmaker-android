package com.mootmaker.data.meeting

import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.RoomInput
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.agenda.roomColorSlots

/** Mirrors the API's AttendeeStatus enum. */
enum class AttendeeStatus(val label: String) {
    Going("Going"),
    NotGoing("Not going"),
    Maybe("Maybe"),
    NoResponse("No response"),
}

data class PersonRef(val id: String, val name: String)

data class AttendeeRow(val person: PersonRef, val status: AttendeeStatus)

/** A meeting as its details screen shows it. Times are naive local date-time strings. */
data class MeetingDetail(
    val id: String,
    val subject: String,
    val startTime: String,
    val endTime: String,
    val roomName: String,
    /** Index into the room palette: the room's own colour, or its position by name. */
    val roomColorSlot: Int,
    val organiser: PersonRef,
    /** Never includes the organiser: the API leaves them out (they are implicitly going). */
    val attendees: List<AttendeeRow>,
)

/**
 * What the details screen shows. [meeting] is null when no such meeting exists, or its day has
 * aged out of retention: the API answers both the same way (use case H.73).
 */
data class MeetingDetailsData(
    val meeting: MeetingDetail?,
    /** The signed-in caller's Person, or null for an account with none. */
    val myPersonId: String?,
    val timeFormat: TimeFormat,
    val dateFormat: DateFormat,
)

// The API's shapes, reduced to what the details need, so the logic is testable without Apollo.
data class MeetingRoomInput(val id: String, val name: String)
data class MeetingInput(
    val id: String,
    val subject: String,
    val startTime: String,
    val endTime: String,
    val room: MeetingRoomInput,
    val organiser: PersonRef,
    val attendees: List<AttendeeRow>,
)

/**
 * The meeting with its room's colour. The colour depends on the room's position in the whole
 * name-sorted room list, so [rooms] is every room, not just this meeting's.
 */
fun buildMeetingDetail(meeting: MeetingInput, rooms: List<RoomInput>): MeetingDetail = MeetingDetail(
    id = meeting.id,
    subject = meeting.subject,
    startTime = meeting.startTime,
    endTime = meeting.endTime,
    roomName = meeting.room.name,
    roomColorSlot = roomColorSlots(rooms)[meeting.room.id] ?: 0,
    organiser = meeting.organiser,
    attendees = meeting.attendees,
)

/** The link the Share action hands out: the webapp's page for the meeting, which opens anywhere. */
fun meetingShareUrl(siteUrl: String, meetingId: String) = "$siteUrl/meetings/$meetingId"
